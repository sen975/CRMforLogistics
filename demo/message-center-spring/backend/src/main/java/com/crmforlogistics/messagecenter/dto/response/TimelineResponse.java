package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TimelineResponse(
        List<TimelineItem> items,
        String nextCursor,
        int itemCount,
        String threadRevision
) {
    public record TimelineItem(
            String type,
            Instant occurredAt,
            String sortId,
            Map<String, Object> payload
    ) {}
}
