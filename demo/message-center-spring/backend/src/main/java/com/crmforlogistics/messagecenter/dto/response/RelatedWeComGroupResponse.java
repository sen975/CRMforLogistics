package com.crmforlogistics.messagecenter.dto.response;

import java.util.UUID;

/** A related group shortcut; the group thread itself stays independent. */
public record RelatedWeComGroupResponse(
        UUID sourceConversationId,
        String displayName,
        String avatarUrl,
        int participantCount
) {}
