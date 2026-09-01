package com.crmforlogistics.messagecenter.dto.response;

import java.util.List;
import java.util.UUID;

/** Aggregated direct WeCom thread for one CRM contact. */
public record WeComThreadResponse(
        UUID contactId,
        List<UUID> sourceConversationIds,
        List<RelatedWeComGroupResponse> relatedGroups,
        List<MessageResponse> items,
        String nextCursor,
        int messageCount,
        String threadRevision
) {
    public WeComThreadResponse(UUID contactId, List<UUID> sourceConversationIds,
                               List<MessageResponse> items, String nextCursor,
                               int messageCount, String threadRevision) {
        this(contactId, sourceConversationIds, List.of(), items, nextCursor, messageCount, threadRevision);
    }
}
