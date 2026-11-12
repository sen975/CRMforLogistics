package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import com.crmforlogistics.messagecenter.service.scheduling.AdaptivePollingScheduler;
import com.crmforlogistics.messagecenter.service.scheduling.PollingTask;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 群发广播的出站轮询入口，节拍交由 {@link AdaptivePollingScheduler} 统一驱动。
 */
@Component
@ConditionalOnProperty(
        name = "app.chatapp-broadcast-worker-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ChatAppBroadcastScheduler implements PollingTask {
    static final String TASK_NAME = "chatapp-broadcast";

    private static final int BATCH_SIZE = 10;

    private final ChatAppBroadcastWorker worker;
    private final AdaptivePollingScheduler scheduler;
    private final Duration baseline;

    public ChatAppBroadcastScheduler(ChatAppBroadcastWorker worker,
                                     AdaptivePollingScheduler scheduler,
                                     @Value("${app.chatapp-broadcast-worker-interval-ms:1000}") long baselineMs) {
        this.worker = worker;
        this.scheduler = scheduler;
        this.baseline = Duration.ofMillis(Math.max(1L, baselineMs));
    }

    @PostConstruct
    void register() {
        scheduler.register(this, baseline);
    }

    @Override
    public String name() {
        return TASK_NAME;
    }

    @Override
    public int pollOnce() {
        int recovered = worker.recoverExpiredSubmissions();
        int processed = worker.runAvailable("chatapp-broadcast-" + UUID.randomUUID(), BATCH_SIZE);
        return recovered + processed;
    }
}
