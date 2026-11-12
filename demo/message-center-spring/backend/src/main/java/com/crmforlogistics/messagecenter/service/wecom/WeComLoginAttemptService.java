package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComLoginAttemptService {
    private final AppConfig config;
    private final Clock clock;
    private final NonceSource nonceSource;
    private final ViewerAuditSink auditTrail;
    private final WeComInstallationService installationService;
    private final Map<String, Attempt> attempts = new LinkedHashMap<>();
    private final Map<String, Replay> replays = new LinkedHashMap<>();
    private final Map<String, Deque<Long>> sourceWindows = new LinkedHashMap<>();

    @Autowired
    public WeComLoginAttemptService(AppConfig config, WeComInstallationService installationService,
                                    ViewerAuditSink audit) {
        this(config, installationService, Clock.systemUTC(),
                () -> UUID.randomUUID().toString().replace("-", ""), audit);
    }

    private WeComLoginAttemptService(AppConfig config, WeComInstallationService installationService,
                                     Clock clock, NonceSource nonceSource, ViewerAuditSink audit) {
        this.config = config;
        this.installationService = installationService;
        this.clock = clock;
        this.nonceSource = nonceSource;
        this.auditTrail = Objects.requireNonNull(audit, "audit");
    }

    static WeComLoginAttemptService forTests(AppConfig config, WeComInstallationService installationService,
                                              Clock clock, NonceSource nonceSource, ViewerAuditSink audit) {
        return new WeComLoginAttemptService(config, installationService, clock, nonceSource, audit);
    }

    public synchronized LoginAttemptResponse createAttempt() {
        return createLoginAttempt("");
    }

    public synchronized LoginAttemptResponse createLoginAttempt(String remoteAddress) {
        return create(Purpose.LOGIN, null, remoteAddress);
    }

    public synchronized LoginAttemptResponse createBindingAttempt(UUID targetUserId, String remoteAddress) {
        if (targetUserId == null) {
            throw new WeComException("WECOM_BINDING_USER_REQUIRED", 401, "当前登录用户不可用");
        }
        return create(Purpose.BIND, targetUserId, remoteAddress);
    }

    private LoginAttemptResponse create(Purpose purpose, UUID targetUserId, String remoteAddress) {
        long now = clock.instant().getEpochSecond();
        cleanupExpired(now);
        requireBounded(config.wecomSuiteId(), "企业微信 SuiteID", 128);
        if (config.wecomLoginAuthCorpId().isBlank()) {
            throw new WeComException("WECOM_LOGIN_INSTALLATION_NOT_SELECTED", 400,
                    "未配置登录首屏使用的授权企业");
        }
        String redirectUri = config.wecomLoginRedirectUri();
        if (attempts.size() + inFlightReplayCount() >= config.wecomLoginMaxPending()
                || !admitSource(remoteAddress, now)) {
            auditTrail.record("wecom.viewer.login_attempt_create", "rate_limited", "", "", "");
            throw new PendingLimitException("企业微信登录请求过于频繁，请稍后重试");
        }
        ResolvedInstallation installation = installationService.resolveInstallation(
                config.wecomSuiteId(), config.wecomLoginAuthCorpId());
        String state = nonceSource.nextNonce();
        requireBounded(state, "WeCom login state", 128);
        if (state.length() < 16 || attempts.containsKey(state)) {
            throw new IllegalStateException("WeCom login state source returned an unsafe or duplicate value");
        }
        auditTrail.record("wecom.viewer.login_attempt_create", "success", "", "", "");
        attempts.put(state, new Attempt(purpose, targetUserId, now + config.wecomLoginAttemptTtlSeconds(),
                installation.installationId(), installation.version(), installation.suiteId(),
                installation.authCorpId(), installation.agentId()));
        return new LoginAttemptResponse("CorpApp", installation.authCorpId(), installation.agentId(),
                redirectUri, state, config.wecomLoginAttemptTtlSeconds());
    }

    public synchronized InstallationBinding consume(String state) {
        return consumeContext(state, Purpose.LOGIN, null).installationBinding();
    }

    public synchronized AttemptContext consume(Purpose expectedPurpose, String state, UUID currentUserId) {
        return consumeContext(state, expectedPurpose, currentUserId);
    }

    public synchronized AttemptContext consume(String state, Purpose expectedPurpose, UUID currentUserId) {
        return consumeContext(state, expectedPurpose, currentUserId);
    }

    public <T> T executeOnce(String state, Purpose expectedPurpose, UUID currentUserId,
                             String authorizationCode, Function<AttemptContext, T> operation) {
        Objects.requireNonNull(operation, "operation");
        requireBoundedSecurity(authorizationCode, "WeCom authorization code", 512);
        ReplayClaim claim = claimReplay(state, expectedPurpose, currentUserId, fingerprint(authorizationCode));
        if (!claim.leader()) {
            return await(claim.replay().result());
        }
        try {
            T result = operation.apply(claim.context());
            completeAfterCommit(claim.replay().result(), result);
            return result;
        } catch (RuntimeException | Error failure) {
            claim.replay().result().completeExceptionally(failure);
            throw failure;
        }
    }

    private synchronized ReplayClaim claimReplay(String state, Purpose expectedPurpose, UUID currentUserId,
                                                  String codeFingerprint) {
        requireBoundedSecurity(state, "WeCom login state", 128);
        if (expectedPurpose == null) throw new SecurityException("WeCom login purpose is required");
        long now = clock.instant().getEpochSecond();
        cleanupExpired(now);
        Replay replay = replays.get(state);
        if (replay != null) {
            validateOwner(replay.purpose(), replay.targetUserId(), expectedPurpose, currentUserId);
            if (!replay.codeFingerprint().equals(codeFingerprint)) {
                throw new WeComException("WECOM_LOGIN_REPLAY_MISMATCH", 403,
                        "企业微信登录重试凭据不匹配");
            }
            if (!replay.result().isDone()) {
                throw new WeComException("WECOM_LOGIN_EXCHANGE_IN_PROGRESS", 409,
                        "企业微信登录仍在处理，请稍后重试");
            }
            auditTrail.record("wecom.viewer.login_attempt_replay", "success", "", "", "");
            return new ReplayClaim(false, null, replay);
        }

        Attempt attempt = attempts.get(state);
        if (attempt == null || now >= attempt.expiresAtEpochSecond()) {
            auditTrail.record("wecom.viewer.login_attempt_consume", "denied", "", "", "");
            throw new SecurityException("WeCom login state is expired, missing, or already used");
        }
        validateOwner(attempt.purpose(), attempt.targetUserId(), expectedPurpose, currentUserId);
        AttemptContext context = resolveContext(attempt);
        attempts.remove(state);
        if (!makeReplayRoom()) {
            throw new PendingLimitException("企业微信登录结果缓存已满，请稍后重试");
        }
        replay = new Replay(attempt.purpose(), attempt.targetUserId(), codeFingerprint,
                attempt.expiresAtEpochSecond(), new CompletableFuture<>());
        replays.put(state, replay);
        auditTrail.record("wecom.viewer.login_attempt_consume", "success", "", "", "");
        return new ReplayClaim(true, context, replay);
    }

    private AttemptContext consumeContext(String state, Purpose expectedPurpose, UUID currentUserId) {
        requireBoundedSecurity(state, "WeCom login state", 128);
        if (expectedPurpose == null) throw new SecurityException("WeCom login purpose is required");
        long now = clock.instant().getEpochSecond();
        Attempt attempt = attempts.get(state);
        if (attempt == null || now >= attempt.expiresAtEpochSecond()) {
            auditTrail.record("wecom.viewer.login_attempt_consume", "denied", "", "", "");
            throw new SecurityException("WeCom login state is expired, missing, or already used");
        }
        validateOwner(attempt.purpose(), attempt.targetUserId(), expectedPurpose, currentUserId);
        attempts.remove(state);
        AttemptContext context = resolveContext(attempt);
        auditTrail.record("wecom.viewer.login_attempt_consume", "success", "", "", "");
        return context;
    }

    private AttemptContext resolveContext(Attempt attempt) {
        ResolvedInstallation current = installationService.resolveInstallation(
                attempt.suiteId(), attempt.authCorpId());
        if (!current.installationId().equals(attempt.installationId())
                || current.version() != attempt.version()
                || !current.agentId().equals(attempt.agentId())) {
            auditTrail.record("wecom.viewer.login_attempt_consume", "denied", "", "", "");
            throw new WeComException("WECOM_INSTALLATION_CHANGED", 403,
                    "企业微信授权安装已变化，请重新扫码");
        }
        InstallationBinding binding = new InstallationBinding(current.installationId(), current.version(),
                current.suiteId(), current.authCorpId(), current.agentId());
        return new AttemptContext(attempt.purpose(), attempt.targetUserId(), attempt.installationId(),
                attempt.version(), binding);
    }

    private void validateOwner(Purpose actualPurpose, UUID targetUserId,
                               Purpose expectedPurpose, UUID currentUserId) {
        if (actualPurpose != expectedPurpose
                || (expectedPurpose == Purpose.BIND && !Objects.equals(targetUserId, currentUserId))) {
            auditTrail.record("wecom.viewer.login_attempt_consume", "denied", "", "", "");
            throw new WeComException("WECOM_LOGIN_PURPOSE_MISMATCH", 403,
                    "企业微信授权用途或绑定用户不匹配");
        }
    }

    private void cleanupExpired(long now) {
        Iterator<Map.Entry<String, Attempt>> iterator = attempts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Attempt> entry = iterator.next();
            if (now >= entry.getValue().expiresAtEpochSecond()) {
                auditTrail.record("wecom.viewer.login_attempt_consume", "denied", "", "", "");
                iterator.remove();
            }
        }
        replays.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtEpochSecond()
                && entry.getValue().result().isDone());
        sourceWindows.values().removeIf(window -> {
            while (!window.isEmpty() && now - window.peekFirst() >= 60) window.removeFirst();
            return window.isEmpty();
        });
    }

    private boolean admitSource(String remoteAddress, long now) {
        String source = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress.trim();
        int sourceCap = Math.max(1, config.wecomLoginMaxPending());
        if (!sourceWindows.containsKey(source) && sourceWindows.size() >= sourceCap) {
            sourceWindows.remove(sourceWindows.keySet().iterator().next());
        }
        int configured = config.wecomLoginAttemptRateLimitPerMinute();
        int limit = configured <= 0 ? 20 : configured;
        Deque<Long> window = sourceWindows.computeIfAbsent(source, ignored -> new ArrayDeque<>());
        while (!window.isEmpty() && now - window.peekFirst() >= 60) window.removeFirst();
        if (window.size() >= limit) return false;
        window.addLast(now);
        return true;
    }

    private static void requireBounded(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + "不能为空，且长度不能超过" + maxLength + "个字符");
        }
    }

    private static void requireBoundedSecurity(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new SecurityException(
                    name + " is required and must not exceed " + maxLength + " characters");
        }
    }

    private static String fingerprint(String authorizationCode) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(authorizationCode.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void completeAfterCommit(CompletableFuture<Object> future, Object result) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            future.complete(result);
            trimCompletedReplays();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                future.complete(result);
                trimCompletedReplays();
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    future.completeExceptionally(new WeComException(
                            "WECOM_LOGIN_EXCHANGE_ROLLED_BACK", 503,
                            "企业微信登录事务未完成，请重新扫码"));
                    trimCompletedReplays();
                }
            }
        });
    }

    private synchronized int inFlightReplayCount() {
        int count = 0;
        for (Replay replay : replays.values()) {
            if (!replay.result().isDone()) count++;
        }
        return count;
    }

    private synchronized void trimCompletedReplays() {
        int capacity = Math.max(1, config.wecomLoginMaxPending());
        while (replays.size() > capacity && evictOldestCompleted()) { }
    }

    private synchronized boolean makeReplayRoom() {
        int capacity = Math.max(1, config.wecomLoginMaxPending());
        while (replays.size() >= capacity && evictOldestCompleted()) { }
        return replays.size() < capacity;
    }

    private boolean evictOldestCompleted() {
        Iterator<Map.Entry<String, Replay>> iterator = replays.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Replay> entry = iterator.next();
            if (entry.getValue().result().isDone()) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T> T await(CompletableFuture<Object> future) {
        try {
            return (T) future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("WeCom login replay failed", cause);
        }
    }

    public record LoginAttemptResponse(String loginType, String appId, String agentId,
                                       String redirectUri, String state, int expiresIn) {}

    public record InstallationBinding(String installationId, long version, String suiteId,
                                      String authCorpId, String agentId) {}

    private record Attempt(Purpose purpose, UUID targetUserId, long expiresAtEpochSecond,
                           String installationId, long version,
                           String suiteId, String authCorpId, String agentId) {}

    private record Replay(Purpose purpose, UUID targetUserId, String codeFingerprint,
                          long expiresAtEpochSecond, CompletableFuture<Object> result) {}

    private record ReplayClaim(boolean leader, AttemptContext context, Replay replay) {}

    public enum Purpose { LOGIN, BIND }

    public record AttemptContext(Purpose purpose, UUID targetUserId, String installationId,
                                  long installationVersion, InstallationBinding installationBinding) {}

    interface NonceSource {
        String nextNonce();
    }

    public static final class PendingLimitException extends WeComException {
        public PendingLimitException(String message) {
            super("WECOM_LOGIN_ATTEMPT_RATE_LIMITED", 429, message);
        }
    }
}
