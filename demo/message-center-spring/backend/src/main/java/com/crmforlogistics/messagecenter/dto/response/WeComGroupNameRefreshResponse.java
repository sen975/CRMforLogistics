package com.crmforlogistics.messagecenter.dto.response;

import com.crmforlogistics.messagecenter.entity.WeComGroupNameRefreshJobEntity;

import java.time.Instant;
import java.util.UUID;

public record WeComGroupNameRefreshResponse(UUID id, UUID sourceConversationId, String triggerSource,
                                            String status, Instant createdAt) {
    public static WeComGroupNameRefreshResponse from(WeComGroupNameRefreshJobEntity job) {
        return new WeComGroupNameRefreshResponse(job.getId(), job.getSourceConversationId(),
                job.getTriggerSource(), job.getStatus(), job.getCreatedAt());
    }
}
