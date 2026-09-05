package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
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
        return syncScope(scopeId(account), channelAccountId).result();
    }

    private SyncAttempt syncScope(UUID providerScopeId, UUID channelAccountId) {
        long started = System.nanoTime();
        WhatsAppTemplateReconciliationService.SyncResult result =
                reconciliationService.syncScope(providerScopeId, channelAccountId);
        return new SyncAttempt(new SyncResultRecord(result.pages(), result.fetched(), result.changed(),
                elapsedMs(started)), result.complete());
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

    public record SyncResultRecord(int pages, int fetched, int changed, long durationMs) {
    }

    private record SyncAttempt(SyncResultRecord result, boolean complete) { }
}
