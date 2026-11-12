package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAccountMode;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateReconciliationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppProviderScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChatAppTemplateSyncService {
    private final WhatsAppTemplateReconciliationService reconciliationService;
    private final ChannelAccountMapper channelAccountMapper;
    private final WhatsAppProviderScopeService providerScopeService;

    @Autowired
    public ChatAppTemplateSyncService(WhatsAppTemplateReconciliationService reconciliationService,
                                      ChannelAccountMapper channelAccountMapper,
                                      WhatsAppProviderScopeService providerScopeService) {
        this.reconciliationService = Objects.requireNonNull(reconciliationService);
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
        this.providerScopeService = Objects.requireNonNull(providerScopeService);
    }

    ChatAppTemplateSyncService(WhatsAppTemplateReconciliationService reconciliationService,
                               ChannelAccountMapper channelAccountMapper) {
        this.reconciliationService = Objects.requireNonNull(reconciliationService);
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
        this.providerScopeService = null;
    }

    public SyncResultRecord runOnce() {
        long started = System.nanoTime();
        int pages = 0;
        int fetched = 0;
        int changed = 0;
        Map<UUID, List<ChannelAccountEntity>> accountsByScope = new LinkedHashMap<>();
        for (ChannelAccountEntity account : channelAccountMapper.selectActiveChatAppAccountsForSync()) {
            if (WhatsAppAccountMode.isBusinessApp(account.getOnboardingMode())) {
                SyncResultRecord privateResult = runAccount(account.getId());
                pages += privateResult.pages();
                fetched += privateResult.fetched();
                changed += privateResult.changed();
                continue;
            }
            UUID scopeId = scopeId(account);
            accountsByScope.computeIfAbsent(scopeId, ignored -> new java.util.ArrayList<>()).add(account);
        }
        for (Map.Entry<UUID, List<ChannelAccountEntity>> entry : accountsByScope.entrySet()) {
            for (ChannelAccountEntity account : entry.getValue()) {
                SyncAttempt attempt = syncScope(entry.getKey(), account.getId());
                pages += attempt.result().pages();
                fetched += attempt.result().fetched();
                changed += attempt.result().changed();
                if (attempt.complete()) {
                    break;
                }
            }
        }
        return new SyncResultRecord(pages, fetched, changed, elapsedMs(started));
    }

    public SyncResultRecord runAccount(UUID channelAccountId) {
        if (channelAccountId == null) {
            throw new IllegalArgumentException("CHATAPP_CHANNEL_ACCOUNT_NOT_FOUND");
        }
        ChannelAccountEntity account = channelAccountMapper.selectById(channelAccountId);
        if (account == null || account.getDeletedAt() != null
                || !("chatapp".equalsIgnoreCase(account.getChannelType())
                || "whatsapp".equalsIgnoreCase(account.getChannelType()))
                || !"active".equalsIgnoreCase(account.getAuthStatus())) {
            throw new IllegalArgumentException("CHATAPP_CHANNEL_ACCOUNT_NOT_FOUND");
        }
        if (WhatsAppAccountMode.isBusinessApp(account.getOnboardingMode())) {
            WhatsAppTemplateReconciliationService.SyncResult result =
                    reconciliationService.syncPrivateAccount(channelAccountId);
            return resultRecord(result, 0);
        }
        return syncScope(scopeId(account), channelAccountId).result();
    }

    public SyncResultRecord runOwnedAccount(UUID ownerId) {
        if (ownerId == null) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
        }
        java.util.List<ChannelAccountEntity> accounts =
                channelAccountMapper.findByOwnerAndChannelType(ownerId, "chatapp");
        if (accounts.isEmpty()) {
            throw new IllegalStateException("CHATAPP_CHANNEL_ACCOUNT_NOT_CONFIGURED");
        }
        if (accounts.size() > 1) {
            throw new IllegalStateException("CHATAPP_FIXED_ACCOUNT_VIOLATION");
        }
        ChannelAccountEntity account = accounts.get(0);
        if (account.getDeletedAt() != null || !"active".equalsIgnoreCase(account.getAuthStatus())) {
            throw new IllegalArgumentException("CHATAPP_CHANNEL_ACCOUNT_NOT_FOUND");
        }
        if (WhatsAppAccountMode.isBusinessApp(account.getOnboardingMode())) {
            WhatsAppTemplateReconciliationService.SyncResult result =
                    reconciliationService.syncPrivateAccount(account.getId());
            return resultRecord(result, 0);
        }
        return syncScope(scopeId(account), account.getId()).result();
    }

    private SyncAttempt syncScope(UUID providerScopeId, UUID channelAccountId) {
        long started = System.nanoTime();
        WhatsAppTemplateReconciliationService.SyncResult result =
                reconciliationService.syncScope(providerScopeId, channelAccountId);
        return new SyncAttempt(new SyncResultRecord(result.pages(), result.fetched(), result.changed(),
                elapsedMs(started), result.complete(), result.syncFailed(), result.errorCode(), result.retryable(),
                result.lastSuccessfulAt()), result.complete());
    }

    private static SyncResultRecord resultRecord(WhatsAppTemplateReconciliationService.SyncResult result,
                                                  long durationMs) {
        return new SyncResultRecord(result.pages(), result.fetched(), result.changed(), durationMs,
                result.complete(), result.syncFailed(), result.errorCode(), result.retryable(), result.lastSuccessfulAt());
    }

    private UUID scopeId(ChannelAccountEntity account) {
        if (account.getProviderScopeId() != null) {
            return account.getProviderScopeId();
        }
        if (providerScopeService == null) {
            throw new IllegalStateException("WHATSAPP_PROVIDER_SCOPE_REQUIRED");
        }
        return providerScopeService.bind(account).getId();
    }

    private static long elapsedMs(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    public record SyncResultRecord(int pages, int fetched, int changed, long durationMs,
                                   boolean complete,
                                   boolean syncFailed, String errorCode, boolean retryable,
                                   java.time.Instant lastSuccessfulAt) {
        public SyncResultRecord(int pages, int fetched, int changed, long durationMs) {
            this(pages, fetched, changed, durationMs, true, false, null, false, null);
        }
    }

    private record SyncAttempt(SyncResultRecord result, boolean complete) { }
}
