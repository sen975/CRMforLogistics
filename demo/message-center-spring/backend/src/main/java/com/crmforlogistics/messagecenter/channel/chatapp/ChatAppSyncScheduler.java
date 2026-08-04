package com.crmforlogistics.messagecenter.channel.chatapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.chatapp-sync-enabled", havingValue = "true", matchIfMissing = true)
public class ChatAppSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChatAppSyncScheduler.class);

    private final ChatAppMessageSyncService messageSyncService;
    private final ChatAppTemplateSyncService templateSyncService;

    public ChatAppSyncScheduler(ChatAppMessageSyncService messageSyncService,
                                 ChatAppTemplateSyncService templateSyncService) {
        this.messageSyncService = messageSyncService;
        this.templateSyncService = templateSyncService;
    }

    @Scheduled(fixedDelay = 5000)
    public void syncMessages() {
        try {
            ChatAppMessageSyncService.SyncResultRecord result = messageSyncService.runOnce();
            if (result.fetched() > 0) {
                log.info("Message sync: pages={} fetched={} saved={} durationMs={}",
                        result.pages(), result.fetched(), result.saved(), result.durationMs());
            }
        } catch (Exception e) {
            log.error("Message sync failed", e);
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void syncTemplates() {
        try {
            ChatAppTemplateSyncService.SyncResultRecord result = templateSyncService.runOnce();
            if (result.fetched() > 0) {
                log.info("Template sync: pages={} fetched={} changed={} durationMs={}",
                        result.pages(), result.fetched(), result.changed(), result.durationMs());
            }
        } catch (Exception e) {
            log.error("Template sync failed", e);
        }
    }
}
