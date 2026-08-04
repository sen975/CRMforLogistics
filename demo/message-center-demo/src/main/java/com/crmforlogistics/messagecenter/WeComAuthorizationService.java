package com.crmforlogistics.messagecenter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Coordinates decrypted suite callbacks and installation persistence. */
public final class WeComAuthorizationService implements AutoCloseable {
    private final Config config;
    private final WeComAuthorizationStore store;
    private final WeComAuthorizationClient gateway;
    private final WeComChatDataPublicKeyRegistrationTrigger publicKeyRegistration;
    private final WeComAuthorizationAuditTrail auditTrail;
    private final BlockingQueue<AuthorizationEvent> queue;
    private final Map<String, Boolean> processed = new LinkedHashMap<>(128, 0.75f, true);
    private final AtomicInteger activeTasks = new AtomicInteger();
    private final Map<String, Instant> latestCancellation = new ConcurrentHashMap<>();
    private final Object installationMutationLock = new Object();
    private final Thread worker;
    private volatile boolean closed;

    public WeComAuthorizationService(Config config, WeComAuthorizationStore store,
                                     WeComAuthorizationClient gateway) {
        this(config, store, gateway, () -> {}, new WeComAuthorizationAuditTrail(config));
    }

    public WeComAuthorizationService(Config config, WeComAuthorizationStore store,
                                     WeComAuthorizationClient gateway,
                                     WeComChatDataPublicKeyRegistrationTrigger publicKeyRegistration) {
        this(config, store, gateway, publicKeyRegistration, new WeComAuthorizationAuditTrail(config));
    }

    WeComAuthorizationService(Config config, WeComAuthorizationStore store,
                              WeComAuthorizationClient gateway,
                              WeComChatDataPublicKeyRegistrationTrigger publicKeyRegistration,
                              WeComAuthorizationAuditTrail auditTrail) {
        this.config = config;
        this.store = store;
        this.gateway = gateway;
        this.publicKeyRegistration = publicKeyRegistration;
        this.auditTrail = auditTrail;
        this.queue = new ArrayBlockingQueue<>(config.wecomAuthorizationQueueCapacity());
        this.worker = new Thread(this::runWorker, "wecom-authorization-worker");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public CallbackAck handle(WeComCallbackCodec.DecodedCallback callback) {
        if (callback == null || callback.infoType() == null || callback.infoType().isBlank()) {
            return CallbackAck.retry();
        }
        try {
            return switch (callback.infoType()) {
                case "suite_ticket" -> {
                    boolean delegatedSuite = callback.suiteId().equals(config.wecomSuiteId());
                    boolean loginSuite = !config.wecomLoginSuiteId().isBlank()
                            && callback.suiteId().equals(config.wecomLoginSuiteId());
                    if ((!delegatedSuite && !loginSuite) || callback.suiteTicket().isBlank()) {
                        yield CallbackAck.retry();
                    }
                    gateway.acceptSuiteTicket(callback.suiteId(), callback.suiteTicket(), callback.timestamp());
                    if (delegatedSuite) requestPublicKeyRegistration();
                    recordAudit(callback, callback.authCorpId(), "succeeded", null);
                    yield CallbackAck.accepted();
                }
                case "create_auth", "change_auth" -> callback.suiteId().equals(config.wecomSuiteId())
                        ? enqueue(callback) : CallbackAck.retry();
                case "reset_permanent_code" -> callback.suiteId().equals(config.wecomSuiteId())
                        && !callback.authCode().isBlank() ? enqueue(callback) : CallbackAck.retry();
                case "cancel_auth" -> {
                    if (!callback.suiteId().equals(config.wecomSuiteId())
                            || callback.authCorpId().isBlank()) yield CallbackAck.retry();
                    synchronized (installationMutationLock) {
                        latestCancellation.merge(installationKey(callback), callback.timestamp(),
                                (left, right) -> left.isAfter(right) ? left : right);
                        store.updateStatus(callback.suiteId(), callback.authCorpId(),
                                WeComAuthorizationStore.AuthStatus.REVOKED);
                    }
                    recordAudit(callback, callback.authCorpId(), "succeeded", null);
                    yield CallbackAck.accepted();
                }
                default -> {
                    recordAudit(callback, callback.authCorpId(), "succeeded", null);
                    yield CallbackAck.accepted();
                }
            };
        } catch (Exception exception) {
            return CallbackAck.retry();
        }
    }

    private CallbackAck enqueue(WeComCallbackCodec.DecodedCallback callback) {
        if (closed) return CallbackAck.retry();
        String key = callback.infoType() + "\u0000" + callback.suiteId() + "\u0000"
                + callback.authCorpId() + "\u0000" + callback.authCode() + "\u0000"
                + callback.timestamp().getEpochSecond();
        synchronized (processed) {
            if (processed.containsKey(key)) return CallbackAck.accepted();
            if (!queue.offer(new AuthorizationEvent(callback, key))) return CallbackAck.retry();
            processed.put(key, Boolean.TRUE);
        }
        recordAudit(callback, callback.authCorpId(), "accepted", null);
        return CallbackAck.accepted();
    }

    private void runWorker() {
        while (!closed || !queue.isEmpty()) {
            try {
                AuthorizationEvent event = queue.poll(250, TimeUnit.MILLISECONDS);
                if (event != null) {
                    activeTasks.incrementAndGet();
                    try {
                        boolean succeeded = process(event);
                        synchronized (processed) {
                            if (!succeeded) {
                                processed.remove(event.dedupeKey());
                            } else {
                                while (processed.size() > 1024) {
                                    processed.remove(processed.keySet().iterator().next());
                                }
                            }
                        }
                    } finally {
                        activeTasks.decrementAndGet();
                    }
                }
            } catch (InterruptedException interrupted) {
                if (closed && queue.isEmpty()) return;
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
                // A single malformed callback must not stop subsequent official retries.
            }
        }
    }

    private boolean process(AuthorizationEvent event) {
        WeComCallbackCodec.DecodedCallback callback = event.callback();
        String affectedCorpId = callback.authCorpId();
        try {
            if ("create_auth".equals(callback.infoType())) {
                WeComAuthorizationGateway.PermanentCodeResponse permanent = gateway.getPermanentCode(callback.authCode());
                affectedCorpId = permanent.authCorpId();
                WeComAuthorizationGateway.AuthorizationInfo info = gateway.getAuthInfo(
                        permanent.authCorpId(), permanent.permanentCode());
                if (!permanent.authCorpId().equals(info.authCorpId())) {
                    throw new IllegalStateException("authorization corp mismatch");
                }
                synchronized (installationMutationLock) {
                    if (cancelledAtOrAfter(callback.suiteId(), permanent.authCorpId(), callback.timestamp())) {
                        return true;
                    }
                    store.upsertActive(callback.suiteId(), permanent.authCorpId(),
                            info.agents().get(0).agentId(), permanent.permanentCode());
                }
                requestPublicKeyRegistration();
            } else if ("reset_permanent_code".equals(callback.infoType())) {
                WeComAuthorizationGateway.PermanentCodeResponse permanent = gateway.getPermanentCode(
                        callback.authCode());
                affectedCorpId = permanent.authCorpId();
                store.resolveRefreshable(callback.suiteId(), permanent.authCorpId());
                WeComAuthorizationGateway.AuthorizationInfo info = gateway.getAuthInfo(
                        permanent.authCorpId(), permanent.permanentCode());
                if (!permanent.authCorpId().equals(info.authCorpId())) {
                    throw new IllegalStateException("authorization corp mismatch");
                }
                synchronized (installationMutationLock) {
                    if (cancelledAtOrAfter(callback.suiteId(), permanent.authCorpId(), callback.timestamp())) {
                        return true;
                    }
                    store.upsertActive(callback.suiteId(), permanent.authCorpId(),
                            info.agents().get(0).agentId(), permanent.permanentCode());
                }
                requestPublicKeyRegistration();
            } else if ("change_auth".equals(callback.infoType())) {
                WeComAuthorizationStore.ResolvedInstallation resolved =
                        store.resolveRefreshable(callback.suiteId(), callback.authCorpId());
                WeComAuthorizationGateway.AuthorizationInfo info = gateway.getAuthInfo(
                        callback.authCorpId(), resolved.permanentCode());
                synchronized (installationMutationLock) {
                    if (cancelledAtOrAfter(callback.suiteId(), callback.authCorpId(), callback.timestamp())) {
                        return true;
                    }
                    store.upsertActive(callback.suiteId(), callback.authCorpId(),
                            info.agents().get(0).agentId(), resolved.permanentCode());
                }
                requestPublicKeyRegistration();
            }
            recordAudit(callback, affectedCorpId, "succeeded", null);
            return true;
        } catch (Exception failure) {
            try {
                if (!affectedCorpId.isBlank()) {
                    synchronized (installationMutationLock) {
                        WeComAuthorizationStore.Installation current = store.find(
                                callback.suiteId(), affectedCorpId).orElse(null);
                        if (current != null
                                && current.authStatus() != WeComAuthorizationStore.AuthStatus.REVOKED
                                && !cancelledAtOrAfter(callback.suiteId(), affectedCorpId,
                                        callback.timestamp())) {
                            store.updateStatus(callback.suiteId(), affectedCorpId,
                                    WeComAuthorizationStore.AuthStatus.FAILED);
                        }
                    }
                }
            } catch (Exception ignored) {
                // Keep the worker alive; the next official callback can retry the installation.
            }
            recordAudit(callback, affectedCorpId, "failed", asAuthorizationFailure(failure));
            return false;
        }
    }

    private void recordAudit(WeComCallbackCodec.DecodedCallback callback, String authCorpId, String result,
                             WeComAuthorizationException failure) {
        try {
            auditTrail.record(callback, authCorpId, result, failure);
        } catch (IOException | RuntimeException auditFailure) {
            System.err.println("{\"action\":\"wecom.authorization.audit\",\"result\":\"failed\","
                    + "\"errorCode\":\"WECOM_AUTHORIZATION_AUDIT_WRITE_FAILED\"}");
        }
    }

    private static WeComAuthorizationException asAuthorizationFailure(Exception failure) {
        if (failure instanceof WeComAuthorizationException authorizationFailure) return authorizationFailure;
        return new WeComAuthorizationException("WECOM_AUTHORIZATION_EVENT_FAILED", 500,
                "企业微信授权事件处理失败");
    }

    private boolean cancelledAtOrAfter(String suiteId, String authCorpId, Instant eventTimestamp) {
        Instant cancelledAt = latestCancellation.get(suiteId + "\u0000" + authCorpId);
        return cancelledAt != null && !cancelledAt.isBefore(eventTimestamp);
    }

    private void requestPublicKeyRegistration() {
        try {
            publicKeyRegistration.requestRegistration();
        } catch (RuntimeException ignored) {
            // Public-key registration is retried by later callbacks and cannot poison installation state.
        }
    }

    private static String installationKey(WeComCallbackCodec.DecodedCallback callback) {
        return callback.suiteId() + "\u0000" + callback.authCorpId();
    }

    public int pendingCount() {
        return queue.size() + activeTasks.get();
    }

    public void close() {
        closed = true;
        try {
            worker.join(25_000);
            if (worker.isAlive()) {
                worker.interrupt();
                worker.join(1_000);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public record AuthorizationEvent(WeComCallbackCodec.DecodedCallback callback, String dedupeKey) {}
    public record CallbackAck(boolean success) {
        public static CallbackAck accepted() { return new CallbackAck(true); }
        public static CallbackAck retry() { return new CallbackAck(false); }
    }
}
