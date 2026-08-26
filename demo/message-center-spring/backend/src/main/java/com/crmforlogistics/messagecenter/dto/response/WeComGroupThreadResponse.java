package com.crmforlogistics.messagecenter.dto.response;

import java.util.List;
import java.util.UUID;

/** Independent WeCom group thread; it is never merged into a contact. */
public record WeComGroupThreadResponse(
        UUID sourceConversationId,
        String providerConversationKey,
        String displayName,
        String avatarUrl,
        String openClientUrl,
        List<WeComPartyView> participants,
        List<MessageResponse> items,
        String nextCursor,
        int messageCount,
        String threadRevision
) {}
