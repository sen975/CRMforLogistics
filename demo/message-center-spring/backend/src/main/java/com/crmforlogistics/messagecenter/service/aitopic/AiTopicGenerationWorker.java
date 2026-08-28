package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.entity.AiTopicGenerationJobEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicGenerationJobMapper;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

@Component
public class AiTopicGenerationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AiTopicGenerationWorker.class);
    private static final int MAX_ERROR_MESSAGE = 950;
    private final AiTopicGenerationJobMapper jobs;
    private final AiTopicService service;
    private final TopicAiGateway gateway;
    private final AiTopicConfigHolder config;

    public AiTopicGenerationWorker(AiTopicGenerationJobMapper jobs, AiTopicService service, TopicAiGateway gateway, AiTopicConfigHolder config) {
        this.jobs = jobs; this.service = service; this.gateway = gateway; this.config = config;
    }

    public int runOnce() {
        int processed = 0;
        for (AiTopicGenerationJobEntity job : jobs.listRunnable(Instant.now(), config.get().workerConcurrency())) {
            String owner = UUID.randomUUID().toString();
            if (jobs.claim(job.getId(), owner, Instant.now().plusSeconds(config.get().leaseSeconds())) == 0) continue;
            try {
                service.generate(job, job.getCreatedByUserId(), gateway);
                jobs.finish(job.getId(), owner, "COMPLETED", null, null, Instant.now(), Instant.now());
                service.publishSnapshotCompleted();
            } catch (AiTopicException error) {
                int attempt = (job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1;
                boolean retry = error.retryable() && attempt < config.get().maxAttempts();
                long backoffSeconds = Math.min(900, 30L << Math.min(5, Math.max(0, attempt - 1)));
                LOG.warn("event=ai_topic_generation_failed jobId={} errorCode={} diagnostic={} retry={}",
                        job.getId(), error.code(), error.diagnostic(), retry);
                jobs.finish(job.getId(), owner, retry ? "RETRY_WAIT" : "FAILED", error.code(), truncate(error.diagnostic(), MAX_ERROR_MESSAGE), retry ? Instant.now().plusSeconds(backoffSeconds) : Instant.now(), retry ? null : Instant.now());
                if (!retry) service.publishSnapshotCompleted();
            } catch (Exception error) {
                String detail = error.getClass().getSimpleName() + ": " + (error.getMessage() == null ? "" : error.getMessage());
                LOG.error("event=ai_topic_generation_unexpected jobId={}", job.getId(), error);
                jobs.finish(job.getId(), owner, "FAILED", "AI_GENERATION_FAILED", truncate(detail, MAX_ERROR_MESSAGE), Instant.now(), Instant.now());
                service.publishSnapshotCompleted();
            }
            processed++;
        }
        return processed;
    }

    static String truncate(String value, int max) {
        if (value == null) return null;
        if (value.codePointCount(0, value.length()) <= max) return value;
        int keep = Math.max(0, max - 3);
        return value.substring(0, value.offsetByCodePoints(0, keep)) + "...";
    }
}
