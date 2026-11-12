package com.crmforlogistics.messagecenter.service.conversation;

import com.crmforlogistics.messagecenter.dto.request.SearchMode;
import com.crmforlogistics.messagecenter.dto.response.ConversationListItemResponse;
import com.crmforlogistics.messagecenter.dto.response.ConversationPageResponse;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.service.contact.ContactTagMatchResolver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UnifiedConversationService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ConversationMapper conversationMapper;
    private final ContactTagMatchResolver tagMatchResolver;

    public UnifiedConversationService(ConversationMapper conversationMapper,
                                      ContactTagMatchResolver tagMatchResolver) {
        this.conversationMapper = conversationMapper;
        this.tagMatchResolver = tagMatchResolver;
    }

    public ConversationPageResponse list(UUID userId, String search, SearchMode searchMode,
                                         String cursor, int limit) {
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        CursorKey key = decodeCursor(cursor);
        List<ConversationMapper.UnifiedConversationRow> rows = conversationMapper.listUnified(
                userId, search, searchMode.isTag(), key.pinned(), key.sortRank(), key.sortAt(),
                key.sortKey(), safeLimit + 1);
        boolean hasMore = rows.size() > safeLimit;
        List<ConversationMapper.UnifiedConversationRow> visibleRows = hasMore
                ? rows.subList(0, safeLimit) : rows;
        List<ConversationListItemResponse> records = visibleRows.stream()
                .map(ConversationMapper.UnifiedConversationRow::toResponse)
                .toList();
        records = withMatchedTags(userId, search, searchMode, records);
        String nextCursor = hasMore && !visibleRows.isEmpty()
                ? encodeCursor(visibleRows.get(visibleRows.size() - 1)) : null;
        return new ConversationPageResponse(records, records.size(), safeLimit, 1, 1, nextCursor);
    }

    private List<ConversationListItemResponse> withMatchedTags(
            UUID userId, String search, SearchMode searchMode,
            List<ConversationListItemResponse> records) {
        List<UUID> contactIds = records.stream()
                .filter(record -> "CONTACT".equals(record.type()))
                .map(ConversationListItemResponse::id)
                .toList();
        Map<UUID, List<String>> matched =
                tagMatchResolver.matchNamesByContact(userId, contactIds, search, searchMode);
        if (matched.isEmpty()) {
            return records;
        }
        return records.stream()
                .map(record -> record.withMatchedTags(matched.getOrDefault(record.id(), List.of())))
                .toList();
    }

    static String encodeCursor(ConversationMapper.UnifiedConversationRow row) {
        if (row == null || row.sortAt() == null || row.sortKey() == null) return null;
        String rank = row.sortRank() == null ? "_" : row.sortRank().toString();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(((row.pinned() ? "1" : "0") + "|" + rank + "|"
                        + row.sortAt() + "|" + row.sortKey())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static CursorKey decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return new CursorKey(null, null, null, null);
        if (cursor.length() > 1024) throw new IllegalArgumentException("invalid conversation cursor");
        String raw = cursor;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // Accept the plain form for internal callers and diagnostics.
        }
        String[] parts = raw.split("\\|", 4);
        if (parts.length != 4 || !(parts[0].equals("0") || parts[0].equals("1"))
                || parts[2].isBlank() || parts[3].isBlank()) {
            throw new IllegalArgumentException("invalid conversation cursor");
        }
        try {
            Instant.parse(parts[2]);
            Long rank = parts[1].equals("_") ? null : Long.parseLong(parts[1]);
            return new CursorKey(parts[0].equals("1"), rank, parts[2], parts[3]);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid conversation cursor", e);
        }
    }

    private record CursorKey(Boolean pinned, Long sortRank, String sortAt, String sortKey) {}
}
