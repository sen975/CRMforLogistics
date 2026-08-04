package com.crmforlogistics.messagecenter.channel.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.email-sync-enabled", havingValue = "true")
public class EmailSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmailSyncScheduler.class);

    private final EmailSyncService syncService;

    public EmailSyncScheduler(EmailSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(fixedDelay = 300_000)
    public void syncEmail() {
        try {
            EmailSyncService.SyncResult result = syncService.receiveLatest();
            log.info("Email sync: {}", result.message());
        } catch (Exception e) {
            log.error("Email sync failed", e);
        }
    }
}
