package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

@Service
public class AiTopicActivityRecorder {
    private final AiTopicOwnerService owners;
    private final AiTopicOwnerActivityService activities;

    public AiTopicActivityRecorder(AiTopicOwnerService owners,
                                   AiTopicOwnerActivityService activities) {
        this.owners = Objects.requireNonNull(owners, "owners");
        this.activities = Objects.requireNonNull(activities, "activities");
    }

    public void recordConversation(ConversationEntity conversation, Instant occurredAt) {
        record(owners.resolveConversation(conversation), occurredAt);
    }

    public void recordContact(java.util.UUID contactId, Instant occurredAt) {
        record(AiTopicOwnerService.contact(contactId), occurredAt);
    }

    public void recordCall(String contactAnchorPointId, Instant occurredAt) {
        record(owners.resolveContactAnchor(contactAnchorPointId), occurredAt);
    }

    private void record(AiTopicOwnerService.OwnerRef owner, Instant occurredAt) {
        activities.recordActivity(owner, Objects.requireNonNull(occurredAt, "occurredAt"));
    }
}
