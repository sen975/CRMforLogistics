package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.mapper.AiTopicOwnerActivityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceConversationMapper;
import com.crmforlogistics.messagecenter.service.wecom.WeComMessageSummaryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class WeComSummaryTopicActivityBridge {
    private final WeComMessageSummaryRepository summaries;
    private final WeComSourceConversationMapper conversations;
    private final ContactIdentityMapper identities;
    private final AiTopicOwnerActivityMapper activities;
    private final long quietWindowSeconds;

    public WeComSummaryTopicActivityBridge(WeComMessageSummaryRepository summaries,
                                            WeComSourceConversationMapper conversations,
                                            ContactIdentityMapper identities,
                                            AiTopicOwnerActivityMapper activities,
                                            AiTopicConfigHolder config) {
        this(summaries, conversations, identities, activities, config.get().quietWindowSeconds());
    }

    WeComSummaryTopicActivityBridge(WeComMessageSummaryRepository summaries,
                                    WeComSourceConversationMapper conversations,
                                    ContactIdentityMapper identities,
                                    AiTopicOwnerActivityMapper activities,
                                    long quietWindowSeconds) {
        this.summaries = summaries;
        this.conversations = conversations;
        this.identities = identities;
        this.activities = activities;
        this.quietWindowSeconds = quietWindowSeconds;
    }

    public AiTopicOwnerService.OwnerRef resolveOwner(UUID sourceConversationId) {
        var source = conversations.selectById(sourceConversationId);
        if (source == null) throw new IllegalArgumentException("source conversation not found");
        UUID contactId = null;
        if (!"GROUP".equalsIgnoreCase(source.getConversationType())) {
            var identity = identities.selectById(source.getContactIdentityId());
            if (identity == null) throw new IllegalArgumentException("contact identity not found");
            contactId = identity.getContactId();
        }
        return ownerFor(source.getConversationType(), contactId, source.getId());
    }

    public static AiTopicOwnerService.OwnerRef ownerFor(String conversationType,
                                                        UUID contactId,
                                                        UUID sourceConversationId) {
        if ("GROUP".equalsIgnoreCase(conversationType)) return AiTopicOwnerService.group(sourceConversationId);
        return AiTopicOwnerService.contact(Objects.requireNonNull(contactId, "contactId"));
    }

    @Transactional
    public void completeSummary(WeComMessageSummaryRepository.LeasedJob job,
                                String summary, String rawResponseJson,
                                String validationStage, Instant completedAt) {
        Objects.requireNonNull(job, "job");
        summaries.markCompleted(job.id(), summary, rawResponseJson, validationStage, completedAt);
        var owner = resolveOwner(job.sourceConversationId());
        Instant occurredAt = Instant.ofEpochSecond(job.sendTime());
        activities.upsertActivity(owner.type(), owner.id(), occurredAt,
                occurredAt.plusSeconds(quietWindowSeconds));
    }
}
