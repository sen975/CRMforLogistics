package com.crmforlogistics.messagecenter.service.aitopic;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiTopicOperationScheduler {
    private final AiTopicOperationWorker worker;
    public AiTopicOperationScheduler(AiTopicOperationWorker worker) { this.worker = worker; }
    @Scheduled(fixedDelayString = "${ai-topic.poll-interval-seconds:30}000")
    public void run() { worker.runOnce(); }
}
