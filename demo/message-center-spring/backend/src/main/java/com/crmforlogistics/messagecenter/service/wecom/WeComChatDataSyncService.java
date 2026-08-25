package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComChatDataSyncService {
    private final AppConfig config;
    private final WeComChatDataGateway gateway;
    private final WeComChatDataStore store;
    private final ViewerAuditSink audit;
    private final WeComInstallationService installationService;
    private final WeComStartupGate startupGate;
    private final Map<String, ReentrantLock> installationLocks = new ConcurrentHashMap<>();
    private final Map<String, RateWindow> userRates = new ConcurrentHashMap<>();
    private volatile WeComChatDataCrypto crypto;

    public WeComChatDataSyncService(AppConfig config, WeComChatDataGateway gateway,
                                    WeComChatDataStore store, ViewerAuditSink audit,
                                    WeComInstallationService installationService,
                                    WeComStartupGate startupGate) {
        this.config = config;
        this.gateway = gateway;
        this.store = store;
        this.audit = audit;
        this.installationService = installationService;
        this.startupGate = startupGate;
    }

    public SyncResult syncSystem() throws WeComChatDataException {
        startupGate.requireOpen();
        String authCorpId = config.wecomLoginAuthCorpId();
        if (authCorpId == null || authCorpId.isBlank()) {
            throw new WeComChatDataException("WECOM_CHATDATA_NOT_CONFIGURED", 503,
                    "企业微信会话同步尚未配置");
        }
        ResolvedInstallation installation = installationService.resolveInstallation(
                config.wecomSuiteId(), authCorpId);
        return sync(new ViewerSyncContext("system:auto-sync", installation));
    }

    public SyncResult sync(ViewerSyncContext context) throws WeComChatDataException {
        requireConfigured(context);
        enforceRate(context.wecomUserId());
        ResolvedInstallation installation = context.installation();
        ReentrantLock lock = installationLocks.computeIfAbsent(installation.installationId(),
                ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            WeComChatDataException busy = new WeComChatDataException("WECOM_CHATDATA_SYNC_BUSY", 429,
                    "企业微信会话正在同步，请稍后重试");
            auditFailure(busy, "wecom.viewer.chatdata_sync", "rate_limited", context.wecomUserId());
            throw busy;
        }
        try {
            long deadline = System.nanoTime()
                    + Duration.ofSeconds(config.wecomChatDataSyncTimeoutSeconds()).toNanos();
            recordAudit("wecom.viewer.chatdata_sync_start", "success", context.wecomUserId());
            ensureRemaining(deadline);
            WeComChatDataStore.SyncKey key = new WeComChatDataStore.SyncKey(
                    installation.installationId(), installation.version(),
                    config.wecomChatDataProgramId(), config.wecomChatDataAbilityId(),
                    installation.authCorpId());
            String cursor = store.cursor(key);
            ensureRemaining(deadline);
            int stored = 0;
            int skipped = 0;
            for (int pageNumber = 1; pageNumber <= config.wecomChatDataSyncMaxPages(); pageNumber++) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) throw timeout();
                WeComChatDataGateway.ProgramPage page = gateway.sync(context.installation(), cursor,
                        config.wecomChatDataSyncLimit(), Duration.ofNanos(remainingNanos));
                List<WeComChatDataStore.DecryptedMessage> decrypted = new ArrayList<>(page.messages().size());
                for (WeComChatDataGateway.EncryptedMessage message : page.messages()) {
                    String secret = decrypt(message.publicKeyVersion(), message.encryptedSecretKey());
                    decrypted.add(new WeComChatDataStore.DecryptedMessage(message, secret));
                    ensureRemaining(deadline);
                }
                ensureRemaining(deadline);
                WeComChatDataStore.PublishResult published = store.publishPage(key, page.nextCursor(),
                        List.copyOf(decrypted));
                ensureRemaining(deadline);
                stored += published.stored();
                skipped += published.skipped();
                cursor = page.nextCursor();
                if (!page.hasMore()) {
                    ensureRemaining(deadline);
                    recordAudit("wecom.viewer.chatdata_sync", "success", context.wecomUserId());
                    ensureRemaining(deadline);
                    return new SyncResult(pageNumber, stored, skipped);
                }
            }
            WeComChatDataException incomplete = new WeComChatDataException(
                    "WECOM_CHATDATA_SYNC_INCOMPLETE", 409,
                    "仍有历史消息待同步，请再次点击继续");
            auditFailure(incomplete, "wecom.viewer.chatdata_sync", "failed", context.wecomUserId());
            throw incomplete;
        } catch (WeComChatDataException exception) {
            if (!"WECOM_CHATDATA_SYNC_INCOMPLETE".equals(exception.code())) {
                auditFailure(exception, "wecom.viewer.chatdata_sync", "failed", context.wecomUserId());
            }
            throw exception;
        } catch (Exception exception) {
            WeComChatDataException mapped = new WeComChatDataException(
                    "WECOM_CHATDATA_PROGRAM_ERROR", 502,
                    "企业微信专区程序调用失败", exception);
            auditFailure(mapped, "wecom.viewer.chatdata_sync", "failed", context.wecomUserId());
            throw mapped;
        } finally {
            lock.unlock();
        }
    }

    private synchronized String decrypt(int publicKeyVersion, String encryptedSecretKey) {
        if (crypto == null) crypto = new WeComChatDataCrypto(config);
        return crypto.decryptSecretKey(publicKeyVersion, encryptedSecretKey);
    }

    private void requireConfigured(ViewerSyncContext context) {
        Path privateKey = Path.of(config.wecomChatDataPrivateKeyFile());
        boolean configured = context != null && context.installation() != null
                && !config.wecomChatDataProgramId().isBlank()
                && !config.wecomChatDataAbilityId().isBlank()
                && privateKey.isAbsolute()
                && Files.isRegularFile(privateKey);
        if (!configured) {
            throw new WeComChatDataException("WECOM_CHATDATA_NOT_CONFIGURED", 503,
                    "企业微信会话同步尚未配置");
        }
    }

    private synchronized void enforceRate(String userId) {
        long now = System.currentTimeMillis() / 1000;
        userRates.entrySet().removeIf(entry -> now - entry.getValue().windowStartedAt() >= 60);
        RateWindow current = userRates.get(userId);
        if (current == null || now - current.windowStartedAt() >= 60) {
            userRates.put(userId, new RateWindow(now, 1));
            return;
        }
        if (current.count() >= config.wecomViewerSessionRateLimit()) {
            WeComChatDataException limited = new WeComChatDataException(
                    "WECOM_CHATDATA_SYNC_BUSY", 429,
                    "企业微信会话正在同步，请稍后重试");
            auditFailure(limited, "wecom.viewer.chatdata_sync", "rate_limited", userId);
            throw limited;
        }
        userRates.put(userId, new RateWindow(current.windowStartedAt(), current.count() + 1));
    }

    private void recordAudit(String action, String result, String userId) throws WeComChatDataException {
        try {
            audit.record(action, result, userId, "", "");
        } catch (Exception exception) {
            throw new WeComChatDataException("WECOM_CHATDATA_STORE_FAILED", 500,
                    "企业微信会话审计写入失败", exception);
        }
    }

    private void auditFailure(WeComChatDataException primary, String action, String result, String userId) {
        try {
            audit.recordDiagnostic(action, result, userId, "", "",
                    primary.code(), primary.upstreamErrcode(), primary.upstreamPath(),
                    primary.upstreamHttpStatus(), primary.upstreamHint());
        } catch (Exception auditFailure) {
            primary.addSuppressed(auditFailure);
        }
    }

    private static WeComChatDataException timeout() {
        return new WeComChatDataException("WECOM_CHATDATA_TIMEOUT", 504,
                "企业微信会话同步超时");
    }

    private static void ensureRemaining(long deadline) {
        if (deadline - System.nanoTime() <= 0) throw timeout();
    }

    public record SyncResult(int pages, int stored, int skipped) {}
    private record RateWindow(long windowStartedAt, int count) {}
}
