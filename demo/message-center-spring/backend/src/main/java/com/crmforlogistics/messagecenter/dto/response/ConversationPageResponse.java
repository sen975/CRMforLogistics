package com.crmforlogistics.messagecenter.dto.response;

import java.util.List;

public record ConversationPageResponse(
        List<ConversationListItemResponse> records,
        long total,
        long size,
        long current,
        long pages,
        String nextCursor
) {}
