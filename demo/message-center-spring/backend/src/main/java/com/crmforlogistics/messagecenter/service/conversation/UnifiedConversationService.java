package com.crmforlogistics.messagecenter.service.conversation;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.crmforlogistics.messagecenter.dto.response.ConversationListItemResponse;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class UnifiedConversationService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ConversationMapper conversationMapper;

    public UnifiedConversationService(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    public Page<ConversationListItemResponse> list(UUID userId, String search, String cursor, int limit) {
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        CursorPair pair = decodeCursor(cursor);
        List<ConversationMapper.UnifiedConversationRow> rows = conversationMapper.listUnified(
                userId, search, pair.sortAt(), pair.sortKey(), safeLimit);
        List<ConversationListItemResponse> records = rows.stream()
                .map(ConversationMapper.UnifiedConversationRow::toResponse)
                .toList();
        Page<ConversationListItemResponse> page = new Page<>(1, safeLimit, false);
        page.setRecords(records);
        page.setTotal(records.size());
        return page;
    }

    public static String encodeCursor(Instant sortAt, String sortKey) {
        if (sortAt == null || sortKey == null) return null;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((sortAt + "|" + sortKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static CursorPair decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return new CursorPair(null, null);
        String raw = cursor;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // Accept the plain form for internal callers and diagnostics.
        }
        String[] parts = raw.split("\\|", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("invalid conversation cursor");
        }
        try {
            return new CursorPair(parts[0], parts[1]);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid conversation cursor", e);
        }
    }

    private record CursorPair(String sortAt, String sortKey) {}
}
