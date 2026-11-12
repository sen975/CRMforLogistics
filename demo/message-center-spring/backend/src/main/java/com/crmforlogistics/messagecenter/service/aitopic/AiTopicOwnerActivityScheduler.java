package com.crmforlogistics.messagecenter.service.aitopic;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AiTopicOwnerActivityScheduler {
    private final AiTopicOwnerActivityWorker worker;

    public AiTopicOwnerActivityScheduler(AiTopicOwnerActivityWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${ai-topic.owner-activity-poll-interval-seconds:30}000", initialDelay = 5000)
    public void run() {
        worker.runOnce(Instant.now(), 20);
    }
}
