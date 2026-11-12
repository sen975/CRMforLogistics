package com.crmforlogistics.messagecenter.service.aitopic;

import java.time.Instant;
import java.util.Objects;
import com.crmforlogistics.messagecenter.mapper.AiTopicOwnerActivityMapper;
import org.springframework.stereotype.Service;

@Service
public class AiTopicOwnerActivityService {
    private final AiTopicOwnerActivityMapper mapper;
    private final long quietWindowSeconds;

    public AiTopicOwnerActivityService(AiTopicOwnerActivityMapper mapper, AiTopicConfigHolder config) {
        this.mapper = mapper;
        this.quietWindowSeconds = config.get().quietWindowSeconds();
    }

    public void recordActivity(AiTopicOwnerService.OwnerRef owner, Instant occurredAt) {
        Objects.requireNonNull(owner, "owner");
        advancePersisted(owner, occurredAt, quietWindowSeconds);
    }

    void advancePersisted(AiTopicOwnerService.OwnerRef owner, Instant occurredAt, long quietWindowSeconds) {
        mapper.upsertActivity(owner.type(), owner.id(), occurredAt,
                occurredAt.plusSeconds(quietWindowSeconds));
    }

    public static ActivityState advance(ActivityState current, Instant occurredAt, long quietWindowSeconds) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (quietWindowSeconds < 60 || quietWindowSeconds > 86_400) {
            throw new IllegalArgumentException("quietWindowSeconds invalid");
        }
        if (current == null) {
            return new ActivityState(occurredAt, occurredAt.plusSeconds(quietWindowSeconds), 1L);
        }
        Instant latest = current.latestEventAt().isAfter(occurredAt) ? current.latestEventAt() : occurredAt;
        Instant candidateDeadline = occurredAt.plusSeconds(quietWindowSeconds);
        Instant deadline = current.quietDeadline().isAfter(candidateDeadline)
                ? current.quietDeadline() : candidateDeadline;
        long version = latest.equals(current.latestEventAt()) && deadline.equals(current.quietDeadline())
                ? current.activityVersion() : current.activityVersion() + 1;
        return new ActivityState(latest, deadline, version);
    }

    public record ActivityState(Instant latestEventAt, Instant quietDeadline, long activityVersion) {
        public ActivityState {
            Objects.requireNonNull(latestEventAt, "latestEventAt");
            Objects.requireNonNull(quietDeadline, "quietDeadline");
            if (activityVersion < 1) throw new IllegalArgumentException("activityVersion invalid");
        }
    }
}
