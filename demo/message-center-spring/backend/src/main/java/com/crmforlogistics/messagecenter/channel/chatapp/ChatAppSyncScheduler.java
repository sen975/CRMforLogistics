package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.chatapp-sync-enabled", havingValue = "true", matchIfMissing = true)
public class ChatAppSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChatAppSyncScheduler.class);

    private final ChatAppMessageSyncService messageSyncService;
    private final ChatAppTemplateSyncService templateSyncService;
    private final ChannelAccountMapper channelAccountMapper;

    public ChatAppSyncScheduler(ChatAppMessageSyncService messageSyncService,
                                 ChatAppTemplateSyncService templateSyncService,
                                 ChannelAccountMapper channelAccountMapper) {
        this.messageSyncService = messageSyncService;
        this.templateSyncService = templateSyncService;
        this.channelAccountMapper = channelAccountMapper;
    }

    @Scheduled(fixedDelay = 5000)
    public void syncMessages() {
        for (ChannelAccountEntity account : channelAccountMapper.selectActiveChatAppAccountsForSync()) {
            channelAccountMapper.updateSyncStatus(account.getId(), "syncing", null);
            try {
                ChatAppMessageSyncService.SyncResultRecord result =
                        messageSyncService.runAccount(account.getId());
                if (result.fetched() > 0) {
                    log.info("Message sync: accountId={} pages={} fetched={} saved={} durationMs={}",
                            account.getId(), result.pages(), result.fetched(), result.saved(), result.durationMs());
                }
                channelAccountMapper.updateSyncStatus(account.getId(), "success", Instant.now());
            } catch (Exception e) {
                log.error("Message sync failed: accountId={} code={}", account.getId(),
                        stableErrorCode(e, "CHATAPP_MESSAGE_HISTORY_SYNC_FAILED"));
                channelAccountMapper.updateSyncStatus(account.getId(), "failed", Instant.now());
            }
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void syncTemplates() {
        for (ChannelAccountEntity account : channelAccountMapper.selectActiveChatAppAccountsForSync()) {
            try {
                ChatAppTemplateSyncService.SyncResultRecord result =
                        templateSyncService.runAccount(account.getId());
                if (result.fetched() > 0) {
                    log.info("Template sync: accountId={} pages={} fetched={} changed={} durationMs={}",
                            account.getId(), result.pages(), result.fetched(), result.changed(), result.durationMs());
                }
            } catch (Exception e) {
                log.error("Template sync failed: accountId={} code={}", account.getId(),
                        stableErrorCode(e, "CHATAPP_TEMPLATE_SYNC_FAILED"));
            }
        }
    }

    private static String stableErrorCode(Exception error, String fallback) {
        String message = error.getMessage();
        if (message != null && message.matches("CHATAPP_[A-Z0-9_]+")) {
            return message;
        }
        return fallback;
    }
}
