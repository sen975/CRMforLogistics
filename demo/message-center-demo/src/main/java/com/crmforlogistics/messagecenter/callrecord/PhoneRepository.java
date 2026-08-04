package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.UnifiedContact;
import com.crmforlogistics.messagecenter.UnifiedMessageStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Global phone-record query projection backed by the call-record repository. */
public final class PhoneRepository {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_CURSOR_BYTES = 2048;
    private final CallRecordRepository records;
    private final CallRecordService service;
    private final UnifiedMessageStore contacts;

    public PhoneRepository(CallRecordRepository records, UnifiedMessageStore contacts) {
        this.records = Objects.requireNonNull(records, "records");
        this.service = null;
        this.contacts = Objects.requireNonNull(contacts, "contacts");
    }

    public PhoneRepository(CallRecordService service, UnifiedMessageStore contacts) {
        this.records = null;
        this.service = Objects.requireNonNull(service, "service");
        this.contacts = Objects.requireNonNull(contacts, "contacts");
    }

    public PhoneRecordPage page(String cursor, int limit, String query, Set<String> allowedAnchors)
            throws IOException, CallRecordException {
        Objects.requireNonNull(allowedAnchors, "allowedAnchors");
        int safeLimit = normalizeLimit(limit);
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        String queryDigits = normalizedQuery.replaceAll("[^0-9]", "");
        Cursor boundary = decodeCursor(cursor);

        Map<String, ContactProjection> projections = projections();
        List<PhoneRecord> matching = new ArrayList<>();
        List<CallRecord> snapshot = service == null
                ? records.listByAnchors(allowedAnchors) : service.list(allowedAnchors);
        for (CallRecord record : snapshot) {
            if (record.phonePointId() == null || !record.phonePointId().startsWith("phone:")) continue;
            ContactProjection projection = projections.get(record.contactAnchorPointId());
            if (projection == null) projection = projections.get(record.phonePointId());
            if (projection == null) {
                projection = new ContactProjection(record.contactAnchorPointId(),
                        record.contactAnchorPointId(), record.phonePointId());
            }
            PhoneRecord item = PhoneRecord.from(record, projection);
            if (!matches(item, normalizedQuery, queryDigits)
                    || (boundary != null && !before(item, boundary))) continue;
            matching.add(item);
        }
        matching.sort(Comparator.comparing(PhoneRecord::occurredAt, Comparator.reverseOrder())
                .thenComparing(item -> item.id().toString(), Comparator.reverseOrder()));
        int end = Math.min(safeLimit, matching.size());
        List<PhoneRecord> items = List.copyOf(matching.subList(0, end));
        String next = end < matching.size() && !items.isEmpty()
                ? encodeCursor(items.get(items.size() - 1)) : null;
        return new PhoneRecordPage(items, next, matching.size());
    }

    private Map<String, ContactProjection> projections() throws IOException {
        Map<String, ContactProjection> result = new LinkedHashMap<>();
        for (UnifiedContact contact : contacts.contacts()) {
            String displayName = firstNonBlank(contact.displayName, contact.remark, contact.id);
            for (var point : contact.points) {
                if (point != null && point.id != null && !point.id.isBlank()) {
                    result.put(point.id, new ContactProjection(contact.id, displayName, point.id));
                }
            }
            if (contact.id != null && !contact.id.isBlank()) {
                result.putIfAbsent(contact.id, new ContactProjection(contact.id, displayName, contact.id));
            }
        }
        return result;
    }

    private static boolean matches(PhoneRecord item, String query, String queryDigits) {
        if (query.isBlank()) return true;
        String phoneDigits = item.phonePointId().substring("phone:".length());
        return (!queryDigits.isBlank() && phoneDigits.contains(queryDigits))
                || item.contactDisplayName().toLowerCase(java.util.Locale.ROOT).contains(query)
                || item.note().toLowerCase(java.util.Locale.ROOT).contains(query);
    }

    private static boolean before(PhoneRecord item, Cursor cursor) {
        if (cursor == null) return true;
        int occurred = item.occurredAt().compareTo(cursor.occurredAt());
        if (occurred != 0) return occurred < 0;
        return item.id().toString().compareTo(cursor.id()) < 0;
    }

    private static int normalizeLimit(int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, limit);
    }

    private static String encodeCursor(PhoneRecord item) {
        JsonObject json = new JsonObject();
        json.addProperty("occurredAt", item.occurredAt().toString());
        json.addProperty("id", item.id().toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            if (encoded.length() > 2731) throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length > MAX_CURSOR_BYTES) throw new IllegalArgumentException();
            JsonObject object = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            Instant occurredAt = Instant.parse(object.get("occurredAt").getAsString());
            String id = object.get("id").getAsString();
            UUID.fromString(id);
            return new Cursor(occurredAt, id);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid phone repository cursor", exception);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private record Cursor(Instant occurredAt, String id) {}
    private record ContactProjection(String contactId, String displayName, String pointId) {}

    public record PhoneRecordPage(List<PhoneRecord> items, String nextCursor, int totalCount) {
        public PhoneRecordPage {
            items = items == null ? List.of() : List.copyOf(items);
            nextCursor = nextCursor == null ? "" : nextCursor;
        }
    }

    public record PhoneRecord(UUID id, String contactId, String contactAnchorPointId,
                              String contactDisplayName, String phonePointId,
                              Instant occurredAt, String direction, String note,
                              String transcriptionState, long version) {
        private static PhoneRecord from(CallRecord record, ContactProjection projection) {
            return new PhoneRecord(record.id(), projection.contactId(),
                    record.contactAnchorPointId(), projection.displayName(), record.phonePointId(),
                    record.occurredAt(), record.direction(), record.note(),
                    record.transcription().state(), record.version());
        }
    }
}
