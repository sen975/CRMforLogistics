package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.dto.response.TimelineResponse;
import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ContactTimelineService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_CURSOR_BYTES = 2048;

    private final CallRecordMapper callRecordMapper;
    private final MessageMapper messageMapper;
    private final ContactIdentityMapper contactIdentityMapper;

    public ContactTimelineService(CallRecordMapper callRecordMapper,
                                  MessageMapper messageMapper,
                                  ContactIdentityMapper contactIdentityMapper) {
        this.callRecordMapper = Objects.requireNonNull(callRecordMapper, "callRecordMapper");
        this.messageMapper = Objects.requireNonNull(messageMapper, "messageMapper");
        this.contactIdentityMapper = Objects.requireNonNull(contactIdentityMapper, "contactIdentityMapper");
    }

    public TimelineResponse timeline(UUID contactId, String cursor, int limit) {
        int safeLimit = normalizeLimit(limit);
        List<ContactIdentityEntity> identities = contactIdentityMapper.findByContactId(contactId);
        Set<String> anchors = new LinkedHashSet<>();
        for (var identity : identities) {
            if (identity.getNormalizedValue() != null && !identity.getNormalizedValue().isBlank()) {
                anchors.add(identity.getChannelType() + ":" + identity.getNormalizedValue());
            }
        }

        List<TimelineItem> all = new ArrayList<>();

        for (var identity : identities) {
            List<MessageEntity> messages = messageMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageEntity>()
                            .eq(MessageEntity::getConversationId, identity.getId())
                            .orderByDesc(MessageEntity::getOccurredAt)
                            .last("LIMIT " + (safeLimit * 2)));
            for (MessageEntity message : messages) {
                String sortId = message.getId() != null ? message.getId().toString() : "";
                if (!sortId.isBlank()) {
                    all.add(TimelineItem.message(message, sortId));
                }
            }
        }

        List<CallRecordEntity> records = anchors.isEmpty()
                ? List.of()
                : callRecordMapper.listByAnchors(anchors);
        for (CallRecordEntity record : records) {
            all.add(TimelineItem.call(record));
        }

        all.sort(TIMELINE_ORDER);

        CursorBoundary boundary = decodeCursor(cursor);
        int end = all.size();
        if (boundary != null) {
            end = 0;
            while (end < all.size() && compare(all.get(end), boundary) < 0) end++;
        }

        int start = Math.max(0, end - safeLimit);
        List<TimelineItem> page = List.copyOf(all.subList(start, end));
        String nextCursor = start > 0 && !page.isEmpty() ? encodeCursor(page.get(0)) : null;

        List<TimelineResponse.TimelineItem> items = page.stream()
                .map(this::toResponse).toList();
        return new TimelineResponse(items, nextCursor, all.size(), revision(page));
    }

    private TimelineResponse.TimelineItem toResponse(TimelineItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (item.message != null) {
            payload.put("direction", item.message.getDirection());
            payload.put("channel", "");
            payload.put("text", item.message.getBodyText() != null ? item.message.getBodyText() : "");
            payload.put("status", item.message.getCurrentStatus() != null ? item.message.getCurrentStatus() : "");
        }
        if (item.callRecord != null) {
            payload.put("id", item.callRecord.getId().toString());
            payload.put("direction", item.callRecord.getDirection());
            payload.put("phonePointId", item.callRecord.getPhonePointId());
            payload.put("durationSeconds", item.callRecord.getAudioDurationSeconds());
            payload.put("state", item.callRecord.getTranscriptionState());
            payload.put("errorCode", item.callRecord.getTranscriptionErrorCode() != null
                    ? item.callRecord.getTranscriptionErrorCode() : "");
            payload.put("errorMessage", item.callRecord.getTranscriptionErrorMessage() != null
                    ? item.callRecord.getTranscriptionErrorMessage() : "");
            payload.put("errorRetryable", item.callRecord.getTranscriptionErrorRetryable() != null
                    && item.callRecord.getTranscriptionErrorRetryable());
            payload.put("attempts", item.callRecord.getTranscriptionAttempts() != null
                    ? item.callRecord.getTranscriptionAttempts() : 0);
            payload.put("version", item.callRecord.getVersion());
        }
        return new TimelineResponse.TimelineItem(
                item.type, item.occurredAt, item.sortId, payload);
    }

    static final class TimelineItem {
        final String type;
        final Instant occurredAt;
        final int typeRank;
        final String sortId;
        final MessageEntity message;
        final CallRecordEntity callRecord;

        TimelineItem(String type, Instant occurredAt, int typeRank, String sortId,
                     MessageEntity message, CallRecordEntity callRecord) {
            this.type = type;
            this.occurredAt = occurredAt;
            this.typeRank = typeRank;
            this.sortId = sortId;
            this.message = message;
            this.callRecord = callRecord;
        }

        static TimelineItem message(MessageEntity message, String sortId) {
            return new TimelineItem("message", message.getOccurredAt(), 0,
                    sortId, message, null);
        }

        static TimelineItem call(CallRecordEntity record) {
            return new TimelineItem("callRecord", record.getOccurredAt(), 1,
                    record.getId().toString(), null, record);
        }
    }

    private static final Comparator<TimelineItem> TIMELINE_ORDER = Comparator
            .comparing((TimelineItem item) -> item.occurredAt)
            .thenComparingInt(item -> item.typeRank)
            .thenComparing(item -> item.sortId);

    private static int normalizeLimit(int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, limit);
    }

    private record CursorBoundary(Instant occurredAt, int typeRank, String sortId) {}

    private static int compare(TimelineItem item, CursorBoundary cursor) {
        int value = item.occurredAt.compareTo(cursor.occurredAt());
        if (value != 0) return value;
        value = Integer.compare(item.typeRank, cursor.typeRank());
        return value != 0 ? value : item.sortId.compareTo(cursor.sortId());
    }

    private static String encodeCursor(TimelineItem item) {
        String raw = item.occurredAt.toString() + "\n" + item.typeRank + "\n" + item.sortId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static CursorBoundary decodeCursor(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length > MAX_CURSOR_BYTES) throw new IllegalArgumentException();
            String raw = new String(bytes, StandardCharsets.UTF_8);
            String[] parts = raw.split("\n", 3);
            if (parts.length != 3) throw new IllegalArgumentException();
            Instant occurredAt = Instant.parse(parts[0]);
            int typeRank = Integer.parseInt(parts[1]);
            if (typeRank != 0 && typeRank != 1) throw new IllegalArgumentException();
            return new CursorBoundary(occurredAt, typeRank, parts[2]);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid timeline cursor");
        }
    }

    private static String revision(List<TimelineItem> items) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (TimelineItem item : items) {
                digest.update((item.type != null ? item.type : "").getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update((item.sortId != null ? item.sortId : "").getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
