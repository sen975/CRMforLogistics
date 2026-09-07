package com.crmforlogistics.messagecenter.dto.response;

import java.util.UUID;

public record ConversationPreferenceResponse(String targetType, UUID targetId, boolean pinned,
                                             boolean hidden) {}
