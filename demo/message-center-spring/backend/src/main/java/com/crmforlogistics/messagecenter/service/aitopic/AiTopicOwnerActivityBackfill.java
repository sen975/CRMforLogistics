package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.mapper.AiTopicOwnerActivityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Bounded bootstrap for pre-existing supported sources that predate activity recording. */
@Component
public class AiTopicOwnerActivityBackfill {
    private static final Logger log = LoggerFactory.getLogger(AiTopicOwnerActivityBackfill.class);
    private static final int BATCH_SIZE = 200;

    private final AiTopicOwnerActivityMapper mapper;
    private final AiTopicOwnerActivityService activities;

    public AiTopicOwnerActivityBackfill(AiTopicOwnerActivityMapper mapper,
                                        AiTopicOwnerActivityService activities) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.activities = Objects.requireNonNull(activities, "activities");
    }

    @Scheduled(fixedDelayString = "${ai-topic.owner-activity-backfill-interval-seconds:600}000",
            initialDelay = 10_000)
    public void scheduledRun() {
        runOnce(BATCH_SIZE);
    }

    public int runOnce(int requestedLimit) {
        int limit = Math.min(BATCH_SIZE, Math.max(1, requestedLimit));
        int recorded = 0;
        for (AiTopicOwnerActivityMapper.ActivityCandidate candidate : mapper.listHistoricalCandidates(limit)) {
            try {
                activities.recordActivity(new AiTopicOwnerService.OwnerRef(
                        candidate.ownerType(), candidate.ownerId()), candidate.occurredAt());
                recorded++;
            } catch (RuntimeException e) {
                log.warn("Unable to backfill AI Topic owner activity: ownerType={}, ownerId={}",
                        candidate.ownerType(), candidate.ownerId(), e);
            }
        }
        return recorded;
    }
}
