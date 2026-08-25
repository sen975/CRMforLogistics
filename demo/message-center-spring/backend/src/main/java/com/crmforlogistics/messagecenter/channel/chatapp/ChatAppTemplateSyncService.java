package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateReconciliationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplatePermissionReconciliationService;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
public class ChatAppTemplateSyncService {
    private final WhatsAppTemplateReconciliationService reconciliationService;
    private final WhatsAppTemplatePermissionReconciliationService permissionReconciliationService;
    private final ChannelAccountMapper channelAccountMapper;

    public ChatAppTemplateSyncService(WhatsAppTemplateReconciliationService reconciliationService,
                                      WhatsAppTemplatePermissionReconciliationService permissionReconciliationService,
                                      ChannelAccountMapper channelAccountMapper) {
        this.reconciliationService = Objects.requireNonNull(reconciliationService);
        this.permissionReconciliationService = Objects.requireNonNull(permissionReconciliationService);
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
    }

    public SyncResultRecord runOnce() {
        long started = System.nanoTime();
        int pages = 0;
        int fetched = 0;
        int changed = 0;
        for (ChannelAccountEntity account : channelAccountMapper.selectActiveChatAppAccountsForSync()) {
            SyncResultRecord result = syncAccount(account.getId());
            pages += result.pages();
            fetched += result.fetched();
            changed += result.changed();
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
        return syncAccount(channelAccountId);
    }

    private SyncResultRecord syncAccount(UUID channelAccountId) {
        long started = System.nanoTime();
        WhatsAppTemplateReconciliationService.SyncResult result =
                reconciliationService.syncAccount(channelAccountId);
        permissionReconciliationService.reconcileDueTemplates(
                channelAccountId, "chatapp-template-sync-" + channelAccountId);
        return new SyncResultRecord(result.pages(), result.fetched(), result.changed(), elapsedMs(started));
    }

    private static long elapsedMs(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    public record SyncResultRecord(int pages, int fetched, int changed, long durationMs) {
    }
}
