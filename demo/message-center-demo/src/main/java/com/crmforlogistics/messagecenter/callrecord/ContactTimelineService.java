package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.ContactPointUtil;
import com.crmforlogistics.messagecenter.MessageTime;
import com.crmforlogistics.messagecenter.UnifiedMessage;
import com.crmforlogistics.messagecenter.UnifiedMessageStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Projects messages and phone records into one stable, newest-first page boundary. */
public final class ContactTimelineService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_CURSOR_BYTES = 2048;

    private final UnifiedMessageStore messages;
    private final CallRecordService calls;

    public ContactTimelineService(UnifiedMessageStore messages, CallRecordService calls) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.calls = Objects.requireNonNull(calls, "calls");
    }

    public TimelinePage page(String contactId, String cursor, int limit) throws IOException, CallRecordException {
        String contact = contactId == null ? "" : contactId.trim();
        if (contact.isBlank()) throw new IllegalArgumentException("contactId is required");
        Set<String> anchors = new LinkedHashSet<>(messages.contactGroup(contact));
        List<TimelineItem> all = new ArrayList<>();
        for (UnifiedMessage message : messages.timelineMessages(contact)) {
            String id = firstNonBlank(message.id, message.sourceId);
            if (!id.isBlank()) all.add(TimelineItem.message(message, id));
        }
        for (CallRecord record : calls.list(anchors)) {
            all.add(TimelineItem.call(record));
        }
        all.sort(TIMELINE_ORDER);
        Cursor boundary = decodeCursor(cursor);
        int end = all.size();
        if (boundary != null) {
            end = 0;
            while (end < all.size() && compare(all.get(end), boundary) < 0) end++;
        }
        int safeLimit = normalizeLimit(limit);
        int start = Math.max(0, end - safeLimit);
        List<TimelineItem> items = List.copyOf(all.subList(start, end));
        String next = start > 0 && !items.isEmpty() ? encodeCursor(items.get(0)) : null;
        return new TimelinePage(items, next, all.size(), revision(all));
    }

    private static final Comparator<TimelineItem> TIMELINE_ORDER = Comparator
            .comparing(TimelineItem::occurredAt)
            .thenComparingInt(item -> item.typeRank())
            .thenComparing(TimelineItem::sortId);

    private static int normalizeLimit(int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, limit);
    }

    private static int compare(TimelineItem item, Cursor cursor) {
        int value = item.occurredAt().compareTo(cursor.occurredAt());
        if (value != 0) return value;
        value = Integer.compare(item.typeRank(), cursor.typeRank());
        return value != 0 ? value : item.sortId().compareTo(cursor.sortId());
    }

    private static String encodeCursor(TimelineItem item) {
        JsonObject json = new JsonObject();
        json.addProperty("occurredAt", item.occurredAt().toString());
        json.addProperty("typeRank", item.typeRank());
        json.addProperty("sortId", item.sortId());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            if (encoded.length() > 2_731) throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length > MAX_CURSOR_BYTES) throw new IllegalArgumentException();
            JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            String occurredAt = string(json, "occurredAt");
            String sortId = string(json, "sortId");
            int rank = json.get("typeRank").getAsInt();
            if (occurredAt.isBlank() || sortId.isBlank() || (rank != 0 && rank != 1)) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(occurredAt), rank, sortId);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid timeline cursor");
        }
    }

    private static String revision(List<TimelineItem> items) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (TimelineItem item : items) {
                digestPart(digest, item.type());
                digestPart(digest, item.sortId());
                digestPart(digest, item.occurredAt().toString());
                digestPart(digest, item.callRecordCard() == null ? "" : Long.toString(item.callRecordCard().version()));
                if (item.message() != null) {
                    digestPart(digest, item.message().channel);
                    digestPart(digest, item.message().sourceId);
                    digestPart(digest, item.message().direction);
                    digestPart(digest, item.message().timestamp);
                    digestPart(digest, item.message().text);
                    digestPart(digest, item.message().status);
                    digestPart(digest, item.message().statusTimestamp);
                    digestPart(digest, item.message().title);
                    digestPart(digest, item.message().summary);
                    digestPart(digest, item.message().bodyText);
                    digestPart(digest, item.message().mediaType);
                    digestPart(digest, item.message().mediaUrl);
                    digestPart(digest, item.message().objectKey);
                    digestPart(digest, item.message().fileName);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void digestPart(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private record Cursor(Instant occurredAt, int typeRank, String sortId) {}

    public record TimelinePage(List<TimelineItem> items, String nextCursor,
                               int itemCount, String threadRevision) {
        public TimelinePage {
            items = items == null ? List.of() : List.copyOf(items);
            threadRevision = threadRevision == null ? "" : threadRevision;
        }
    }

    public record TimelineItem(String type, Instant occurredAt, int typeRank,
                               String sortId, UnifiedMessage message,
                               CallRecordCard callRecordCard) {
        static TimelineItem message(UnifiedMessage message, String id) {
            return new TimelineItem("message", messageOccurredAt(message.timestamp), 0, id, message, null);
        }
        static TimelineItem call(CallRecord record) {
            return new TimelineItem("callRecord", record.occurredAt(), 1, record.id().toString(), null, CallRecordCard.from(record));
        }
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    static Instant messageOccurredAt(String value) {
        return MessageTime.parseInstant(value);
    }

    public record CallRecordCard(UUID id, String direction, String phonePointId,
                                 Instant occurredAt, double durationSeconds,
                                 String state, String errorCode, long version) {
        static CallRecordCard from(CallRecord record) {
            Transcription transcription = record.transcription();
            double duration = record.audio().durationSeconds();
            return new CallRecordCard(record.id(), record.direction(), record.phonePointId(),
                    record.occurredAt(), duration, transcription.state(),
                    transcription.error() == null ? "" : transcription.error().code(), record.version());
        }
    }
}
