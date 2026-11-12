package com.crmforlogistics.messagecenter.dto.response;

import java.util.List;

public record ThreadResponse(
        List<MessageResponse> items,
        String nextCursor,
        int messageCount,
        String threadRevision
) {}
