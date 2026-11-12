package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Discriminated item used by the unified conversation workspace. */
public record ConversationListItemResponse(
        String type,
        UUID id,
        String displayName,
        String remark,
        String avatarUrl,
        List<String> channelTypes,
        Instant lastMessageAt,
        String lastText,
        int messageCount,
        int unreadCount,
        String providerConversationKey,
        int participantCount,
        boolean pinned,
        List<String> matchedTags
) {
    public static ConversationListItemResponse contact(UUID id, String displayName,
                                                        Instant lastMessageAt, String lastText,
                                                        int messageCount, int unreadCount) {
        return contact(id, displayName, null, lastMessageAt, lastText, messageCount, unreadCount);
    }

    public static ConversationListItemResponse contact(UUID id, String displayName, String remark,
                                                        Instant lastMessageAt, String lastText,
                                                        int messageCount, int unreadCount) {
        return new ConversationListItemResponse("CONTACT", id, displayName, remark, null, List.of(),
                lastMessageAt, lastText, messageCount, unreadCount, null, 0, false, List.of());
    }

    public static ConversationListItemResponse group(UUID id, String displayName,
                                                      String providerConversationKey,
                                                      Instant lastMessageAt, String lastText,
                                                      int messageCount, int unreadCount,
                                                      int participantCount) {
        return new ConversationListItemResponse("WECOM_GROUP", id, displayName, null, null, List.of("wecom"),
                lastMessageAt, lastText, messageCount, unreadCount,
                providerConversationKey, participantCount, false, List.of());
    }

    public ConversationListItemResponse withMatchedTags(List<String> tags) {
        return new ConversationListItemResponse(type, id, displayName, remark, avatarUrl, channelTypes,
                lastMessageAt, lastText, messageCount, unreadCount, providerConversationKey,
                participantCount, pinned, tags == null ? List.of() : tags);
    }
}
