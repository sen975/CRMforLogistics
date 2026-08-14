package com.crmforlogistics.messagecenter;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Coordinates required authorization auditing and installation persistence. */
public final class WeComAuthorizationService implements AutoCloseable {
    private static final String INTERRUPTED = "WECOM_AUTHORIZATION_PROCESS_INTERRUPTED";
    private static final String RECONCILIATION_REQUIRED =
            "WECOM_AUTHORIZATION_AUDIT_RECONCILIATION_REQUIRED";
    private final Config config;
    private final WeComAuthorizationStore store;
    private final WeComAuthorizationClient gateway;
    private final WeComChatDataPublicKeyRegistrationTrigger publicKeyRegistration;
    private final WeComAuthorizationAuditTrail auditTrail;
    private final BlockingQueue<AuthorizationEvent> queue;
    private final AtomicInteger activeTasks = new AtomicInteger();
    private final Object installationMutationLock = new Object();
    private final Object gateLock = new Object();
    private final Thread worker;
    private final BoundedAuditFile ownedAuthorizationWriter;
    private volatile boolean gateFailed;
    private volatile String gateErrorCode;
    private FailedFinal failedFinal;
    private volatile boolean closed;

    public WeComAuthorizationService(Config config, WeComAuthorizationStore store,
                                     WeComAuthorizationClient gateway) {
        this(config, store, gateway, () -> {}, openStandaloneAudit(config));
    }

    public WeComAuthorizationService(Config config, WeComAuthorizationStore store,
                                     WeComAuthorizationClient gateway,
                                     WeComChatDataPublicKeyRegistrationTrigger publicKeyRegistration) {
        this(config, store, gateway, publicKeyRegistration, openStandaloneAudit(config));
    }

    private WeComAuthorizationService(Config config, WeComAuthorizationStore store,
                                      WeComAuthorizationClient gateway,
                                      WeComChatDataPublicKeyRegistrationTrigger publicKeyRegistration,
                                      StandaloneAudit standalone) {
        this(config, store, gateway, publicKeyRegistration, standalone.trail(),
                standalone.writer()::enableRetentionCleanup, standalone.writer());
    }

    WeComAuthorizationService(Config config, WeComAuthorizationStore store,
                              WeComAuthorizationClient gateway,
                              WeComChatDataPublicKeyRegistrationTrigger publicKeyRegistration,
                              WeComAuthorizationAuditTrail auditTrail) {
        this(config, store, gateway, publicKeyRegistration, auditTrail, () -> {}, null);
    }

    WeComAuthorizationService(Config config, WeComAuthorizationStore store,
                              WeComAuthorizationClient gateway,
                              WeComChatDataPublicKeyRegistrationTrigger publicKeyRegistration,
                              WeComAuthorizationAuditTrail auditTrail,
                              Runnable enableRetentionCleanup) {
        this(config, store, gateway, publicKeyRegistration, auditTrail,
                enableRetentionCleanup, null);
    }

    private WeComAuthorizationService(Config config, WeComAuthorizationStore store,
                                      WeComAuthorizationClient gateway,
                                      WeComChatDataPublicKeyRegistrationTrigger publicKeyRegistration,
                                      WeComAuthorizationAuditTrail auditTrail,
                                      Runnable enableRetentionCleanup,
                                      BoundedAuditFile ownedAuthorizationWriter) {
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.gateway = java.util.Objects.requireNonNull(gateway, "gateway");
        this.publicKeyRegistration = java.util.Objects.requireNonNull(
                publicKeyRegistration, "publicKeyRegistration");
        this.auditTrail = java.util.Objects.requireNonNull(auditTrail, "auditTrail");
        this.ownedAuthorizationWriter = ownedAuthorizationWriter;
        this.queue = new ArrayBlockingQueue<>(config.wecomAuthorizationQueueCapacity());
        recoverOpenAttemptsAtStartup();
        if (!gateFailed) {
            enableRetentionCleanup.run();
        }
        this.worker = new Thread(this::runWorker, "wecom-authorization-worker");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public CallbackAck handle(WeComCallbackCodec.DecodedCallback callback) {
        if (!validCallback(callback) || closed) return CallbackAck.retry();
        if (gateFailed && !recoverAfterAuditFailure()) return CallbackAck.retry();
        final WeComAuthorizationAuditTrail.BeginResult begin;
        try {
            begin = auditTrail.begin(callback);
        } catch (AuditStorageException | RuntimeException failure) {
            reportAuditFailure(storageCode(failure));
            return CallbackAck.retry();
        }
        if (begin.disposition() == WeComAuthorizationAuditTrail.BeginDisposition.ALREADY_SUCCEEDED
                || begin.disposition()
                == WeComAuthorizationAuditTrail.BeginDisposition.OPEN_ALREADY_ACCEPTED) {
            return CallbackAck.accepted();
        }
        WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt = begin.attempt();
        if (isQueued(callback)) {
            if (!queue.offer(new AuthorizationEvent(callback, attempt))) {
                closeFailed(attempt, callback.authCorpId(), failure(
                        "WECOM_AUTHORIZATION_QUEUE_FULL", 503));
                return CallbackAck.retry();
            }
            return CallbackAck.accepted();
        }
        return processSynchronously(callback, attempt);
    }

    private CallbackAck processSynchronously(
            WeComCallbackCodec.DecodedCallback callback,
            WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt) {
        return switch (callback.infoType()) {
            case "suite_ticket" -> processSuiteTicket(callback, attempt);
            case "cancel_auth" -> processCancellation(callback, attempt);
            case "create_auth", "change_auth", "reset_permanent_code" -> {
                closeFailed(attempt, callback.authCorpId(), failure(
                        "WECOM_AUTHORIZATION_CALLBACK_INVALID", 400));
                yield CallbackAck.retry();
            }
            default -> processNoMutation(callback, attempt);
        };
    }

    private CallbackAck processSuiteTicket(
            WeComCallbackCodec.DecodedCallback callback,
            WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt) {
        boolean delegatedSuite = callback.suiteId().equals(config.wecomSuiteId());
        boolean loginSuite = !config.wecomLoginSuiteId().isBlank()
                && callback.suiteId().equals(config.wecomLoginSuiteId());
        if ((!delegatedSuite && !loginSuite) || callback.suiteTicket().isBlank()) {
            closeFailed(attempt, callback.authCorpId(), failure(
                    "WECOM_AUTHORIZATION_CALLBACK_INVALID", 400));
            return CallbackAck.retry();
        }
        try {
            auditTrail.pending(attempt, "", WeComAuthorizationStore.AuthStatus.ACTIVE, 0);
        } catch (AuditStorageException | RuntimeException pendingFailure) {
            closeFailed(attempt, "", asAuthorizationFailure(pendingFailure));
            return CallbackAck.retry();
        }
        try {
            gateway.acceptSuiteTicket(callback.suiteId(), callback.suiteTicket(), callback.timestamp());
            if (!closeSucceeded(attempt, "")) return CallbackAck.retry();
            if (delegatedSuite) requestPublicKeyRegistration();
            return CallbackAck.accepted();
        } catch (Exception failure) {
            closeFailed(attempt, "", asAuthorizationFailure(failure));
            return CallbackAck.retry();
        }
    }

    private CallbackAck processCancellation(
            WeComCallbackCodec.DecodedCallback callback,
            WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt) {
        if (!callback.suiteId().equals(config.wecomSuiteId())
                || callback.authCorpId().isBlank()) {
            closeFailed(attempt, callback.authCorpId(), failure(
                    "WECOM_AUTHORIZATION_CALLBACK_INVALID", 400));
            return CallbackAck.retry();
        }
        try {
            synchronized (installationMutationLock) {
                WeComAuthorizationStore.Installation current = store.find(
                        callback.suiteId(), callback.authCorpId()).orElseThrow(() -> failure(
                                "WECOM_INSTALLATION_NOT_FOUND", 403));
                auditTrail.pending(attempt, callback.authCorpId(),
                        WeComAuthorizationStore.AuthStatus.REVOKED, current.version());
                store.updateStatusForEvent(callback.suiteId(), callback.authCorpId(),
                        WeComAuthorizationStore.AuthStatus.REVOKED, attempt.eventId(),
                        callback.timestamp());
            }
            return closeSucceeded(attempt, callback.authCorpId())
                    ? CallbackAck.accepted() : CallbackAck.retry();
        } catch (Exception failure) {
            closeFailed(attempt, callback.authCorpId(), asAuthorizationFailure(failure));
            return CallbackAck.retry();
        }
    }

    private CallbackAck processNoMutation(
            WeComCallbackCodec.DecodedCallback callback,
            WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt) {
        try {
            auditTrail.pending(attempt, callback.authCorpId(),
                    WeComAuthorizationStore.AuthStatus.ACTIVE, 0);
            return closeSucceeded(attempt, callback.authCorpId())
                    ? CallbackAck.accepted() : CallbackAck.retry();
        } catch (Exception failure) {
            closeFailed(attempt, callback.authCorpId(), asAuthorizationFailure(failure));
            return CallbackAck.retry();
        }
    }

    private void runWorker() {
        while (!closed || !queue.isEmpty()) {
            try {
                AuthorizationEvent event = queue.poll(250, TimeUnit.MILLISECONDS);
                if (event == null) continue;
                activeTasks.incrementAndGet();
                try {
                    processQueued(event);
                } finally {
                    activeTasks.decrementAndGet();
                }
            } catch (InterruptedException interrupted) {
                if (closed && queue.isEmpty()) return;
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
                // The attempt remains visible for startup reconciliation.
            }
        }
    }

    private void processQueued(AuthorizationEvent event) {
        WeComCallbackCodec.DecodedCallback callback = event.callback();
        String affectedCorpId = callback.authCorpId();
        String[] resolvedCorpId = {affectedCorpId};
        boolean pendingWritten = false;
        try {
            PreparedMutation mutation = prepareMutation(callback, value -> resolvedCorpId[0] = value);
            affectedCorpId = mutation.authCorpId();
            synchronized (installationMutationLock) {
                WeComAuthorizationStore.Installation current = store.find(
                        callback.suiteId(), mutation.authCorpId()).orElse(null);
                long expectedVersion = current == null ? 0 : current.version();
                auditTrail.pending(event.auditAttempt(), mutation.authCorpId(),
                        WeComAuthorizationStore.AuthStatus.ACTIVE, expectedVersion);
                pendingWritten = true;
                store.upsertActiveForEvent(callback.suiteId(), mutation.authCorpId(),
                        mutation.agentId(), mutation.permanentCode(),
                        event.auditAttempt().eventId(), callback.timestamp());
            }
            if (closeSucceeded(event.auditAttempt(), affectedCorpId)) {
                requestPublicKeyRegistration();
            }
        } catch (Exception failure) {
            closeFailed(event.auditAttempt(), resolvedCorpId[0], asAuthorizationFailure(failure));
            if (pendingWritten && gateFailed) {
                reportAuditFailure(gateErrorCode);
            }
        }
    }

    private PreparedMutation prepareMutation(WeComCallbackCodec.DecodedCallback callback,
                                             Consumer<String> resolvedCorpId)
            throws WeComAuthorizationException {
        if ("create_auth".equals(callback.infoType())) {
            WeComAuthorizationGateway.PermanentCodeResponse permanent =
                    gateway.getPermanentCode(callback.authCode());
            resolvedCorpId.accept(permanent.authCorpId());
            WeComAuthorizationGateway.AuthorizationInfo info = gateway.getAuthInfo(
                    permanent.authCorpId(), permanent.permanentCode());
            requireSameCorp(permanent.authCorpId(), info.authCorpId());
            return new PreparedMutation(permanent.authCorpId(),
                    firstAgent(info), permanent.permanentCode());
        }
        if ("reset_permanent_code".equals(callback.infoType())) {
            WeComAuthorizationGateway.PermanentCodeResponse permanent =
                    gateway.getPermanentCode(callback.authCode());
            resolvedCorpId.accept(permanent.authCorpId());
            store.resolveRefreshable(callback.suiteId(), permanent.authCorpId());
            WeComAuthorizationGateway.AuthorizationInfo info = gateway.getAuthInfo(
                    permanent.authCorpId(), permanent.permanentCode());
            requireSameCorp(permanent.authCorpId(), info.authCorpId());
            return new PreparedMutation(permanent.authCorpId(),
                    firstAgent(info), permanent.permanentCode());
        }
        WeComAuthorizationStore.ResolvedInstallation resolved =
                store.resolveRefreshable(callback.suiteId(), callback.authCorpId());
        WeComAuthorizationGateway.AuthorizationInfo info = gateway.getAuthInfo(
                callback.authCorpId(), resolved.permanentCode());
        requireSameCorp(callback.authCorpId(), info.authCorpId());
        return new PreparedMutation(callback.authCorpId(),
                firstAgent(info), resolved.permanentCode());
    }

    void recoverOpenAttemptsAtStartup() {
        for (AuthorizationAuditIndex.OpenAttempt open : auditTrail.index().openAttempts()) {
            try {
                if (open.phase() == AuthorizationAuditIndex.Phase.ACCEPTED
                        || open.attempt().action().equals("wecom.authorization.suite_ticket")) {
                    auditTrail.failed(open.attempt(), open.authCorpId(), interruptedFailure());
                    continue;
                }
                WeComAuthorizationStore.Installation current = open.authCorpId() == null ? null
                        : store.find(open.suiteId(), open.authCorpId()).orElse(null);
                if (current != null && open.attempt().eventId()
                        .equals(current.lastAuthorizationEventId())) {
                    auditTrail.succeeded(open.attempt(), open.authCorpId());
                } else {
                    failGate(RECONCILIATION_REQUIRED, null);
                }
            } catch (Exception failure) {
                failGate(storageCode(failure), null);
            }
        }
    }

    boolean recoverAfterAuditFailure() {
        synchronized (gateLock) {
            if (!gateFailed) return true;
            if (failedFinal == null) return false;
            try {
                if (failedFinal.succeeded()) {
                    auditTrail.succeeded(failedFinal.attempt(), failedFinal.authCorpId());
                } else {
                    auditTrail.failed(failedFinal.attempt(), failedFinal.authCorpId(),
                            failedFinal.failure());
                }
                failedFinal = null;
                gateFailed = false;
                gateErrorCode = null;
                return true;
            } catch (AuditStorageException | RuntimeException failure) {
                reportAuditFailure(storageCode(failure));
                return false;
            }
        }
    }

    private boolean closeSucceeded(
            WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt,
            String authCorpId) {
        try {
            auditTrail.succeeded(attempt, authCorpId);
            return true;
        } catch (AuditStorageException | RuntimeException failure) {
            failGate(storageCode(failure), new FailedFinal(
                    attempt, authCorpId, true, null));
            return false;
        }
    }

    private void closeFailed(
            WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt,
            String authCorpId, WeComAuthorizationException failure) {
        try {
            auditTrail.failed(attempt, authCorpId, failure);
        } catch (AuditStorageException | RuntimeException auditFailure) {
            failGate(storageCode(auditFailure), new FailedFinal(
                    attempt, authCorpId, false, failure));
        }
    }

    private void failGate(String errorCode, FailedFinal finalAttempt) {
        synchronized (gateLock) {
            gateFailed = true;
            gateErrorCode = errorCode;
            if (finalAttempt != null) failedFinal = finalAttempt;
        }
        reportAuditFailure(errorCode);
    }

    private static void reportAuditFailure(String errorCode) {
        System.err.println("{\"event\":\"wecom.audit.write\","
                + "\"stream\":\"wecom-authorization\",\"status\":\"failed\","
                + "\"errorCode\":\"" + safeErrorCode(errorCode) + "\"}");
    }

    private static String safeErrorCode(String errorCode) {
        return errorCode != null && errorCode.matches("[A-Z0-9_]{1,128}")
                ? errorCode : "AUDIT_ROTATION_FAILED";
    }

    private static String storageCode(Exception failure) {
        return failure instanceof AuditStorageException storage
                ? storage.code() : "AUDIT_ROTATION_FAILED";
    }

    private static WeComAuthorizationException interruptedFailure() {
        return failure(INTERRUPTED, 500);
    }

    private static WeComAuthorizationException failure(String code, int status) {
        return new WeComAuthorizationException(code, status,
                "企业微信授权事件处理失败");
    }

    private static WeComAuthorizationException asAuthorizationFailure(Exception failure) {
        if (failure instanceof WeComAuthorizationException authorizationFailure) {
            return authorizationFailure;
        }
        if (failure instanceof AuditStorageException storageFailure) {
            return failure(safeErrorCode(storageFailure.code()), 500);
        }
        return failure("WECOM_AUTHORIZATION_EVENT_FAILED", 500);
    }

    private static boolean validCallback(WeComCallbackCodec.DecodedCallback callback) {
        return callback != null && callback.infoType() != null
                && !callback.infoType().isBlank() && callback.timestamp() != null
                && callback.suiteId() != null && !callback.suiteId().isBlank();
    }

    private boolean isQueued(WeComCallbackCodec.DecodedCallback callback) {
        if (!callback.suiteId().equals(config.wecomSuiteId())) return false;
        return switch (callback.infoType()) {
            case "create_auth", "change_auth" -> true;
            case "reset_permanent_code" -> !callback.authCode().isBlank();
            default -> false;
        };
    }

    private static void requireSameCorp(String expected, String actual)
            throws WeComAuthorizationException {
        if (!expected.equals(actual)) {
            throw failure("WECOM_AUTHORIZATION_CORP_MISMATCH", 502);
        }
    }

    private static String firstAgent(WeComAuthorizationGateway.AuthorizationInfo info)
            throws WeComAuthorizationException {
        if (info.agents() == null || info.agents().isEmpty()) {
            throw failure("WECOM_AUTHORIZATION_AGENT_MISSING", 502);
        }
        return info.agents().get(0).agentId();
    }

    private void requestPublicKeyRegistration() {
        try {
            publicKeyRegistration.requestRegistration();
        } catch (RuntimeException ignored) {
            // A later callback retries registration without changing installation truth.
        }
    }

    private static StandaloneAudit openStandaloneAudit(Config config) {
        try {
            config.validateAuditConfiguration();
            AuditFileSettings settings = config.authorizationAuditSettings();
            Clock clock = Clock.systemUTC();
            BoundedAuditFile writer = BoundedAuditFile.acquire(
                    "wecom-authorization", settings, clock, AuditDiskSpaceProbe.system());
            try {
                writer.prepareForAuthorizationRecovery();
                WeComAuthorizationAuditTrail trail = WeComAuthorizationAuditTrail.open(
                        settings, clock, line -> writer.append(
                                line.getBytes(StandardCharsets.UTF_8),
                                BoundedAuditFile.Durability.REQUIRED));
                return new StandaloneAudit(writer, trail);
            } catch (Exception failure) {
                try {
                    writer.close();
                } catch (AuditStorageException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        } catch (Exception failure) {
            throw new IllegalStateException("authorization audit startup failed", failure);
        }
    }

    public int pendingCount() {
        return queue.size() + activeTasks.get();
    }

    @Override
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
        } finally {
            if (ownedAuthorizationWriter != null) {
                try {
                    ownedAuthorizationWriter.close();
                } catch (AuditStorageException failure) {
                    reportAuditFailure(failure.code());
                }
            }
        }
    }

    public record AuthorizationEvent(
            WeComCallbackCodec.DecodedCallback callback,
            WeComAuthorizationAuditTrail.AuthorizationAuditAttempt auditAttempt) {}

    public record CallbackAck(boolean success) {
        public static CallbackAck accepted() { return new CallbackAck(true); }
        public static CallbackAck retry() { return new CallbackAck(false); }
    }

    private record PreparedMutation(String authCorpId, String agentId, String permanentCode) {}
    private record FailedFinal(WeComAuthorizationAuditTrail.AuthorizationAuditAttempt attempt,
                               String authCorpId, boolean succeeded,
                               WeComAuthorizationException failure) {}
    private record StandaloneAudit(BoundedAuditFile writer,
                                   WeComAuthorizationAuditTrail trail) {}
}
