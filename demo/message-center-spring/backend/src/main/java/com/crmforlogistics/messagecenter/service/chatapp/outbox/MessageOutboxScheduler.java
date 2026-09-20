package com.crmforlogistics.messagecenter.service.chatapp.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.chatapp-outbox-enabled", havingValue = "true", matchIfMissing = true)
public class MessageOutboxScheduler {
    private final MessageOutboxWorker worker;

    public MessageOutboxScheduler(MessageOutboxWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelay = 1000)
    public void deliver() {
        worker.recoverExpiredProcessing();
        worker.claimAndProcess(20);
    }
}
