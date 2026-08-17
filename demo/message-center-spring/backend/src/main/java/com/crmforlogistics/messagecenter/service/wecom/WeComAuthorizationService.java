package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComCallbackCodec;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/** Coordinates required audit, bounded callback work and installation mutation. */
@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComAuthorizationService {
    private static final String RECONCILIATION_REQUIRED =
            "WECOM_AUTHORIZATION_AUDIT_RECONCILIATION_REQUIRED";

    private final AppConfig config;
    private final WeComAuthorizationAuditTrail audit;
    private final WeComInstallationService installations;
    private final WeComAuthorizationGateway gateway;
    private final WeComAuthorizationMutationService mutations;
    private final WeComStartupGate startupGate;
    private final BlockingQueue<AuthorizationEvent> queue;
    private final Thread worker;
    private final Object lifecycle = new Object();
    private int activeAdmissions;
    private volatile boolean closed;

    public WeComAuthorizationService(AppConfig config,
                                     WeComAuthorizationAuditTrail audit,
                                     WeComInstallationService installations,
                                     WeComAuthorizationGateway gateway,
                                     WeComAuthorizationMutationService mutations,
                                     WeComStartupGate startupGate) {
        this(config, audit, installations, gateway, mutations, startupGate,
                new ArrayBlockingQueue<>(config.wecomAuthorizationQueueCapacity()), true);
    }

    WeComAuthorizationService(AppConfig config,
                              WeComAuthorizationAuditTrail audit,
                              WeComInstallationService installations,
                              WeComAuthorizationGateway gateway,
                              WeComAuthorizationMutationService mutations,
                              WeComStartupGate startupGate,
                              BlockingQueue<AuthorizationEvent> queue,
                              boolean startWorker) {
        this.config = Objects.requireNonNull(config, "config");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.installations = Objects.requireNonNull(installations, "installations");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.startupGate = Objects.requireNonNull(startupGate, "startupGate");
        this.queue = Objects.requireNonNull(queue, "queue");
        if (startWorker) {
            worker = new Thread(this::runWorker, "wecom-authorization-worker");
            worker.setDaemon(true);
            worker.start();
        } else {
            worker = null;
        }
    }

    public CallbackAck handle(WeComCallbackCodec.DecodedCallback callback) {
        if (!valid(callback) || !beginAdmission()) return CallbackAck.retry();
        try {
            return handleAdmitted(callback);
        } finally {
            endAdmission();
        }
    }

    private CallbackAck handleAdmitted(WeComCallbackCodec.DecodedCallback callback) {
        try {
            startupGate.requireOpen();
        } catch (WeComException unavailable) {
            return CallbackAck.retry();
        }
        final WeComAuthorizationAuditTrail.BeginResult begin;
        try {
            begin = audit.begin(callback);
        } catch (RuntimeException auditFailure) {
            return CallbackAck.retry();
        }
        if (begin.disposition() == WeComAuthorizationAuditTrail.BeginDisposition.ALREADY_SUCCEEDED) {
            return CallbackAck.accepted();
        }
        if (begin.disposition() == WeComAuthorizationAuditTrail.BeginDisposition.OPEN_ALREADY_ACCEPTED) {
            return CallbackAck.retry();
        }
        WeComAuthorizationAuditTrail.Attempt attempt = begin.attempt();
        if (isQueued(callback)) {
            if (!queue.offer(new AuthorizationEvent(callback, attempt))) {
                closeFailed(attempt, callback.authCorpId(), queueFull());
                return CallbackAck.retry();
            }
            return CallbackAck.accepted();
        }
        return processSynchronously(callback, attempt);
    }

    boolean runNext() {
        AuthorizationEvent event = queue.poll();
        if (event == null) return false;
        processQueued(event);
        return true;
    }

    private CallbackAck processSynchronously(
            WeComCallbackCodec.DecodedCallback callback,
            WeComAuthorizationAuditTrail.Attempt attempt) {
        return switch (callback.infoType()) {
            case "suite_ticket" -> processSuiteTicket(callback, attempt);
            case "cancel_auth" -> processCancellation(callback, attempt);
            case "create_auth", "change_auth", "reset_permanent_code" -> {
                closeFailed(attempt, callback.authCorpId(), invalidCallback());
                yield CallbackAck.retry();
            }
            default -> {
                closeFailed(attempt, callback.authCorpId(), new WeComException(
                        "WECOM_CALLBACK_UNKNOWN_INFOTYPE", 400,
                        "未知的 InfoType: " + callback.infoType()));
                yield CallbackAck.retry();
            }
        };
    }

    private CallbackAck processSuiteTicket(
            WeComCallbackCodec.DecodedCallback callback,
            WeComAuthorizationAuditTrail.Attempt attempt) {
        boolean delegatedSuite = callback.suiteId().equals(config.wecomSuiteId());
        String loginSuiteId = config.wecomLoginSuiteId();
        boolean loginSuite = loginSuiteId != null && !loginSuiteId.isBlank()
                && callback.suiteId().equals(loginSuiteId);
        if ((!delegatedSuite && !loginSuite) || callback.suiteTicket().isBlank()) {
            closeFailed(attempt, callback.authCorpId(), invalidCallback());
            return CallbackAck.retry();
        }
        try {
            audit.pending(attempt, "", "ACTIVE", 0L);
            gateway.acceptSuiteTicket(callback.suiteId(), callback.suiteTicket(), callback.timestamp());
            audit.succeeded(attempt, "");
            return CallbackAck.accepted();
        } catch (RuntimeException failure) {
            closeFailed(attempt, "", authorizationFailure(failure));
            return CallbackAck.retry();
        }
    }

    private CallbackAck processCancellation(
            WeComCallbackCodec.DecodedCallback callback,
            WeComAuthorizationAuditTrail.Attempt attempt) {
        if (!callback.suiteId().equals(config.wecomSuiteId()) || callback.authCorpId().isBlank()) {
            closeFailed(attempt, callback.authCorpId(), invalidCallback());
            return CallbackAck.retry();
        }
        try {
            WeComInstallationEntity current = installations.find(
                    callback.suiteId(), callback.authCorpId());
            if (current == null) {
                throw new WeComException("WECOM_INSTALLATION_NOT_FOUND", 403,
                        "未找到企业微信授权安装记录");
            }
            long expectedVersion = version(current);
            audit.pending(attempt, callback.authCorpId(), "REVOKED", expectedVersion);
            mutations.revoke(attempt, callback, current, expectedVersion);
            return CallbackAck.accepted();
        } catch (RuntimeException failure) {
            closeFailed(attempt, callback.authCorpId(), authorizationFailure(failure));
            return CallbackAck.retry();
        }
    }

    private void processQueued(AuthorizationEvent event) {
        WeComCallbackCodec.DecodedCallback callback = event.callback();
        String[] authCorpId = {callback.authCorpId()};
        try {
            startupGate.requireOpen();
            PreparedMutation prepared = prepare(callback, value -> authCorpId[0] = value);
            WeComInstallationEntity current = installations.find(callback.suiteId(), authCorpId[0]);
            if ("reset_permanent_code".equals(callback.infoType())) {
                if (current == null) {
                    throw new WeComException("WECOM_INSTALLATION_NOT_FOUND", 403,
                            "未找到企业微信授权安装记录");
                }
                if ("REVOKED".equals(current.getAuthStatus())) {
                    throw new WeComException("WECOM_INSTALLATION_INACTIVE", 403,
                            "企业微信授权安装已撤销或失效");
                }
            }
            long expectedVersion = current == null ? 0L : version(current);
            startupGate.requireOpen();
            audit.pending(event.attempt(), authCorpId[0], "ACTIVE", expectedVersion);
            mutations.applyActive(event.attempt(), callback, authCorpId[0], prepared.agentId(),
                    prepared.permanentCode(), expectedVersion);
        } catch (RuntimeException failure) {
            closeFailed(event.attempt(), authCorpId[0], authorizationFailure(failure));
        }
    }

    private PreparedMutation prepare(WeComCallbackCodec.DecodedCallback callback,
                                     Consumer<String> resolvedCorpId) {
        if ("create_auth".equals(callback.infoType())) {
            return fromAuthCode(callback, resolvedCorpId);
        }
        if ("reset_permanent_code".equals(callback.infoType())) {
            return fromResetAuthCode(callback, resolvedCorpId);
        }
        if (!"change_auth".equals(callback.infoType()) || callback.authCorpId().isBlank()) {
            throw invalidCallback();
        }
        ResolvedInstallation resolved = installations.resolveRefreshable(
                callback.suiteId(), callback.authCorpId());
        var info = gateway.getAuthInfo(callback.authCorpId(), resolved.permanentCode());
        requireSameCorp(callback.authCorpId(), info.authCorpId());
        return new PreparedMutation(callback.authCorpId(), firstAgent(info), resolved.permanentCode());
    }

    private PreparedMutation fromAuthCode(WeComCallbackCodec.DecodedCallback callback,
                                          Consumer<String> resolvedCorpId) {
        if (callback.authCode().isBlank()) throw invalidCallback();
        var permanent = gateway.getPermanentCode(callback.authCode());
        resolvedCorpId.accept(permanent.authCorpId());
        var info = gateway.getAuthInfo(permanent.authCorpId(), permanent.permanentCode());
        requireSameCorp(permanent.authCorpId(), info.authCorpId());
        return new PreparedMutation(permanent.authCorpId(), firstAgent(info), permanent.permanentCode());
    }

    private PreparedMutation fromResetAuthCode(WeComCallbackCodec.DecodedCallback callback,
                                               Consumer<String> resolvedCorpId) {
        if (callback.authCode().isBlank()) throw invalidCallback();
        if (!callback.authCorpId().isBlank()) {
            installations.resolveRefreshable(callback.suiteId(), callback.authCorpId());
        }
        var permanent = gateway.getPermanentCode(callback.authCode());
        resolvedCorpId.accept(permanent.authCorpId());
        if (!callback.authCorpId().isBlank()) {
            requireSameCorp(callback.authCorpId(), permanent.authCorpId());
        } else {
            installations.resolveRefreshable(callback.suiteId(), permanent.authCorpId());
        }
        var info = gateway.getAuthInfo(permanent.authCorpId(), permanent.permanentCode());
        requireSameCorp(permanent.authCorpId(), info.authCorpId());
        return new PreparedMutation(permanent.authCorpId(), firstAgent(info), permanent.permanentCode());
    }

    private void runWorker() {
        while (workerShouldContinue()) {
            try {
                AuthorizationEvent event = queue.poll(250, TimeUnit.MILLISECONDS);
                if (event != null) processQueued(event);
            } catch (InterruptedException interrupted) {
                if (!workerShouldContinue()) return;
            } catch (RuntimeException unexpected) {
                failGate();
            }
        }
    }

    private boolean beginAdmission() {
        synchronized (lifecycle) {
            if (closed) return false;
            activeAdmissions++;
            return true;
        }
    }

    private void endAdmission() {
        synchronized (lifecycle) {
            activeAdmissions--;
            lifecycle.notifyAll();
        }
    }

    private boolean workerShouldContinue() {
        synchronized (lifecycle) {
            return !closed || activeAdmissions > 0 || !queue.isEmpty();
        }
    }

    private void closeFailed(WeComAuthorizationAuditTrail.Attempt attempt,
                             String authCorpId, WeComException failure) {
        try {
            audit.failed(attempt, authCorpId == null ? "" : authCorpId, failure);
        } catch (RuntimeException auditFailure) {
            failGate();
        }
    }

    private void failGate() {
        startupGate.fail(RECONCILIATION_REQUIRED,
                "企业微信授权审计未闭合，需要人工对账");
    }

    private boolean isQueued(WeComCallbackCodec.DecodedCallback callback) {
        if (!callback.suiteId().equals(config.wecomSuiteId())) return false;
        return switch (callback.infoType()) {
            case "create_auth", "change_auth" -> true;
            case "reset_permanent_code" -> !callback.authCode().isBlank();
            default -> false;
        };
    }

    private static String firstAgent(WeComAuthorizationGateway.AuthorizationInfo info) {
        List<WeComAuthorizationGateway.AuthorizedAgent> agents = info.agents();
        if (agents != null) {
            for (WeComAuthorizationGateway.AuthorizedAgent agent : agents) {
                if (agent != null && agent.agentId() != null && !agent.agentId().isBlank()) {
                    return agent.agentId();
                }
            }
        }
        throw new WeComException("WECOM_AUTHORIZATION_AGENT_MISSING", 502,
                "企业微信授权信息缺少 AgentID");
    }

    private static void requireSameCorp(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new WeComException("WECOM_AUTHORIZATION_CORP_MISMATCH", 502,
                    "企业微信授权企业信息不一致");
        }
    }

    private static long version(WeComInstallationEntity entity) {
        return entity.getVersion() == null ? 0L : entity.getVersion();
    }

    private static boolean valid(WeComCallbackCodec.DecodedCallback callback) {
        return callback != null && callback.timestamp() != null
                && callback.suiteId() != null && !callback.suiteId().isBlank()
                && callback.infoType() != null && !callback.infoType().isBlank();
    }

    private static WeComException invalidCallback() {
        return new WeComException("WECOM_AUTHORIZATION_CALLBACK_INVALID", 400,
                "企业微信授权回调字段不完整");
    }

    private static WeComException queueFull() {
        return new WeComException("WECOM_AUTHORIZATION_QUEUE_FULL", 503,
                "企业微信授权队列已满，请重试");
    }

    private static WeComException authorizationFailure(RuntimeException failure) {
        if (failure instanceof WeComException weComFailure) return weComFailure;
        return new WeComException("WECOM_AUTHORIZATION_EVENT_FAILED", 500,
                "企业微信授权事件处理失败", failure);
    }

    @PreDestroy
    public void close() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        synchronized (lifecycle) {
            closed = true;
            while (activeAdmissions > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    TimeUnit.NANOSECONDS.timedWait(lifecycle, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (worker == null) return;
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public record AuthorizationEvent(WeComCallbackCodec.DecodedCallback callback,
                                     WeComAuthorizationAuditTrail.Attempt attempt) {}

    public record CallbackAck(boolean success) {
        public static CallbackAck accepted() { return new CallbackAck(true); }
        public static CallbackAck retry() { return new CallbackAck(false); }
    }

    private record PreparedMutation(String authCorpId, String agentId, String permanentCode) {}
}
