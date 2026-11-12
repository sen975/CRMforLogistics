package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.entity.AiTopicOperationJobEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicOperationJobMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class AiTopicOperationWorker {
    private final AiTopicOperationJobMapper jobs;
    private final AiTopicService service;
    private final AiTopicConfigHolder config;

    public AiTopicOperationWorker(AiTopicOperationJobMapper jobs, AiTopicService service, AiTopicConfigHolder config) {
        this.jobs = jobs;
        this.service = service;
        this.config = config;
    }

    public int runOnce() {
        int processed = 0;
        for (AiTopicOperationJobEntity job : jobs.listRunnable(Instant.now(), config.get().workerConcurrency())) {
            String owner = UUID.randomUUID().toString();
            if (jobs.claim(job.getId(), owner, Instant.now().plusSeconds(config.get().leaseSeconds())) == 0) continue;
            try {
                service.applyOperation(job);
                jobs.finish(job.getId(), owner, "COMPLETED", null, null, Instant.now());
            } catch (AiTopicException error) {
                jobs.finish(job.getId(), owner, "FAILED", error.code(), error.diagnostic(), Instant.now());
            } catch (Exception error) {
                jobs.finish(job.getId(), owner, "FAILED", "TOPIC_OPERATION_FAILED", error.getMessage(), Instant.now());
            } finally {
                service.publishSnapshotCompleted();
            }
            processed++;
        }
        return processed;
    }
}
