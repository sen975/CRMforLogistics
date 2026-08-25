package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "app.chatapp-broadcast-worker-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ChatAppBroadcastScheduler {
    private static final int BATCH_SIZE = 10;

    private final ChatAppBroadcastWorker worker;

    public ChatAppBroadcastScheduler(ChatAppBroadcastWorker worker) {
        this.worker = worker;
    }

    @Scheduled(
            fixedDelayString = "${app.chatapp-broadcast-worker-interval-ms:1000}",
            initialDelayString = "${app.chatapp-broadcast-worker-initial-delay-ms:1000}")
    public void run() {
        worker.recoverExpiredSubmissions();
        worker.runAvailable("chatapp-broadcast-" + UUID.randomUUID(), BATCH_SIZE);
    }
}
