package com.crmforlogistics.messagecenter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository {
    UUID getOrCreateConversation(UUID accountId, UUID identityId) throws Exception;
    MessageWriteResult insert(MessageDraft draft) throws Exception;
    void appendStatus(UUID messageId, MessageStatusEvent event) throws Exception;
    List<UnifiedMessage> thread(UUID userId, UUID conversationId, MessageCursor cursor, int limit) throws Exception;
    List<UnifiedMessage> unifiedTimeline(UUID userId, UUID contactId, MessageCursor cursor, int limit) throws Exception;
    UnifiedTimelineSnapshot unifiedTimelinePage(UUID userId, UUID contactId, MessageCursor cursor, int limit) throws Exception;
    int unifiedTimelineCount(UUID userId, UUID contactId) throws Exception;
    String unifiedTimelineRevision(UUID userId, UUID contactId) throws Exception;
    Optional<UnifiedMessage> findAuthorized(UUID userId, UUID messageId) throws Exception;
}

record MessageCursor(Instant occurredAt, UUID id) {}
record UnifiedTimelineSnapshot(List<UnifiedMessage> fetched, int messageCount, String threadRevision) {}

record MessageDraft(UUID conversationId, UUID channelAccountId, UUID sourceEventId,
                    String providerMessageId, String clientRequestId, String direction,
                    String messageKind, String subject, String bodyText, String bodyHtml,
                    Instant occurredAt, boolean countsAsUnread, UUID createdByUserId) {}

record MessageWriteResult(UUID messageId, boolean inserted) {}

record MessageStatusEvent(String status, Instant occurredAt, String providerEventId,
                          String reasonCode, String reasonMessage) {}
