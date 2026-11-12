package com.crmforlogistics.messagecenter.dto.request;

import java.util.UUID;

public record ConversationPreferenceRequest(String targetType, UUID targetId) {
    public record OrderRequest(String sourceType, UUID sourceId,
                               String targetType, UUID targetId,
                               String placement) {}
}
