package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.mapper.AiTopicOwnerActivityMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
public class AiTopicOwnerActivityWorker {
    private final AiTopicOwnerActivityMapper mapper;
    private final AiTopicService topics;
    private final String workerId = "topic-owner-" + UUID.randomUUID();

    public AiTopicOwnerActivityWorker(AiTopicOwnerActivityMapper mapper, AiTopicService topics) {
        this.mapper = mapper;
        this.topics = topics;
    }

    public int runOnce(Instant now, int limit) {
        int processed = 0;
        for (AiTopicOwnerActivityMapper.ActivityRow row : mapper.listDue(now, Math.min(100, Math.max(1, limit)))) {
            if (mapper.lease(row.ownerType(), row.ownerId(), row.activityVersion(), workerId,
                    now.plus(Duration.ofMinutes(2)), now) == 1) {
                boolean handedOff = false;
                try {
                    topics.enqueueAutomaticGeneration(new AiTopicOwnerService.OwnerRef(row.ownerType(), row.ownerId()));
                    if (mapper.markGenerating(row.ownerType(), row.ownerId(), row.activityVersion(), workerId) != 1) {
                        throw new IllegalStateException("AI Topic owner activity lease was lost");
                    }
                    handedOff = true;
                    processed++;
                } finally {
                    if (!handedOff) {
                        mapper.release(row.ownerType(), row.ownerId(), row.activityVersion(), workerId);
                    }
                }
            }
        }
        return processed;
    }
}
