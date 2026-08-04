package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;
import com.crmforlogistics.messagecenter.UnifiedMessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContactTimelineServiceTest {
    private static final String CONTACT = "email:buyer@example.com";
    private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void interleavesMessagesAndCallCardsByOccurredAtWithStableCursor() throws Exception {
        Fixture fixture = fixture();
        CallRecord queued = call("c1", "2026-07-30T09:05:00Z", "completed", 4);
        fixture.repository.records.add(queued);
        ContactTimelineService service = fixture.service;

        ContactTimelineService.TimelinePage latest = service.page(CONTACT, "", 2);
        assertEquals(List.of(callSortId("c1"), "email:m2"), latest.items().stream()
                .map(ContactTimelineService.TimelineItem::sortId).toList());
        assertEquals("callRecord", latest.items().get(0).type());

        ContactTimelineService.TimelinePage older = service.page(CONTACT, latest.nextCursor(), 2);
        assertEquals(List.of("email:m1"), older.items().stream()
                .map(ContactTimelineService.TimelineItem::sortId).toList());
    }

    @Test
    void callVersionChangesThreadRevisionWithoutChangingMessageCount() throws Exception {
        Fixture fixture = fixture();
        CallRecord queued = call("c1", "2026-07-30T09:05:00Z", "queued", 0);
        fixture.repository.records.add(queued);
        ContactTimelineService service = fixture.service;

        ContactTimelineService.TimelinePage before = service.page(CONTACT, "", 10);
        CallRecord completed = call("c1", "2026-07-30T09:05:00Z", "completed", 2);
        fixture.repository.records.set(0, completed);
        ContactTimelineService.TimelinePage after = service.page(CONTACT, "", 10);
        assertEquals(before.itemCount(), after.itemCount());
        assertNotEquals(before.threadRevision(), after.threadRevision());
    }

    @Test
    void rejectsMalformedCursorAndClampsPageLimit() throws Exception {
        Fixture fixture = fixture();
        ContactTimelineService service = fixture.service;
        assertThrows(IllegalArgumentException.class, () -> service.page(CONTACT, "not-a-cursor", 10));
        assertEquals(2, service.page(CONTACT, "", 0).items().size());
        assertEquals(2, service.page(CONTACT, "", 100).items().size());
    }

    @Test
    void usesMessageBeforeCallForEqualTimestamps() throws Exception {
        Fixture fixture = fixture();
        fixture.repository.records.add(call("same", "2026-07-30T09:00:00Z", "queued", 1));

        assertEquals(List.of("email:m1", callSortId("same")),
                fixture.service.page(CONTACT, "", 10).items().stream()
                        .filter(item -> item.occurredAt().equals(Instant.parse("2026-07-30T09:00:00Z")))
                        .map(ContactTimelineService.TimelineItem::sortId).toList());
    }

    @Test
    void reusesMessageTimeParsingForLegacyMessageTimestamps() throws Exception {
        assertEquals(Instant.parse("2026-07-30T09:00:00Z"),
                ContactTimelineService.messageOccurredAt(
                "Wed Jul 30 09:00:00 UTC 2026"));
    }

    @Test
    void projectsPhoneOnlyContactTimelineAndReloadsCurrentNote() throws Exception {
        Path root = tempDir.resolve("phone-only");
        Files.createDirectories(root.resolve("email"));
        Config config = new Config(Map.of(
                "DATA_DIR", root.toString(),
                "EMAIL_DATA_DIR", root.resolve("email").toString(),
                "CHATAPP_DATA_FILE", root.resolve("messages.jsonl").toString(),
                "CALL_RECORD_DATA_DIR", root.resolve("calls").toString()));
        UnifiedMessageStore store = new UnifiedMessageStore(config);
        String phone = "phone:13800000000";
        store.ensurePhoneContact("", "13800000000", "电话采购");
        UUID id = UUID.randomUUID();
        CallRecord record = new CallRecord(id, phone, phone, "inbound", NOW, NOW,
                "actor", "phone-only-request",
                new AudioAsset("audio/" + id + ".mp3", "call.mp3", 3,
                        "a".repeat(64), "audio/mpeg", 2),
                new Transcription("queued", "sensevoice", 0, null, NOW, null, null),
                List.of(), null, 1, "重启后仍可见");
        Files.createDirectories(config.callRecordDataDir().resolve("audio"));
        Files.write(config.callRecordDataDir().resolve(record.audio().relativePath()), new byte[]{1, 2, 3});
        try (FileCallRecordRepository repository = FileCallRecordRepository.open(config,
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            repository.saveNew(record);
        }
        try (FileCallRecordRepository restarted = FileCallRecordRepository.open(config,
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            assertEquals("重启后仍可见", restarted.find(id).orElseThrow().note());
            CallRecordService calls = new CallRecordService(restarted,
                    new LocalAudioStore(config), store::contactGroup, config,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            ContactTimelineService timeline = new ContactTimelineService(store, calls);
            ContactTimelineService.TimelinePage page = timeline.page(phone, "", 10);
            assertEquals(List.of(id.toString()), page.items().stream()
                    .map(ContactTimelineService.TimelineItem::sortId).toList());
            assertEquals("callRecord", page.items().get(0).type());
        }
    }

    private Fixture fixture() throws Exception {
        Path emailDir = tempDir.resolve("email");
        Files.createDirectories(emailDir);
        Path inbox = emailDir.resolve("inbox.jsonl");
        Files.writeString(inbox,
                "{\"id\":\"m1\",\"direction\":\"in\",\"contactEmail\":\"buyer@example.com\",\"sentDate\":\"2026-07-30T09:00:00Z\",\"bodyText\":\"one\"}\n"
                        + "{\"id\":\"m2\",\"direction\":\"in\",\"contactEmail\":\"buyer@example.com\",\"sentDate\":\"2026-07-30T09:10:00Z\",\"bodyText\":\"two\"}\n",
                StandardCharsets.UTF_8);
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "EMAIL_DATA_DIR", emailDir.toString(),
                "CHATAPP_DATA_FILE", tempDir.resolve("messages.jsonl").toString(),
                "CHATAPP_TEMPLATE_FILE", tempDir.resolve("templates.json").toString()));
        UnifiedMessageStore store = new UnifiedMessageStore(config);
        InMemoryRepository repository = new InMemoryRepository();
        CallRecordService calls = new CallRecordService(
                repository, new LocalAudioStore(config), id -> List.of(CONTACT), config,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(new ContactTimelineService(store, calls), repository);
    }

    private static CallRecord call(String id, String occurredAt, String state, long version) {
        UUID uuid = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
        TranscriptionResult result = "completed".equals(state)
                ? new TranscriptionResult("sensevoice", 4.0, "hello",
                List.of(new TranscriptSegment(0, 4, "hello")), NOW)
                : null;
        return new CallRecord(uuid, CONTACT, "", "inbound", Instant.parse(occurredAt), NOW,
                "actor", id, new AudioAsset("audio/" + id + ".mp3", id + ".mp3", 1,
                "sha", "audio/mpeg", 4.0), new Transcription(state, "sensevoice",
                "completed".equals(state) ? 1 : 0, null, NOW, result,
                null), List.of(), null, version);
    }

    private static String callSortId(String id) {
        return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record Fixture(ContactTimelineService service, InMemoryRepository repository) {}

    private static final class InMemoryRepository implements CallRecordRepository {
        private final List<CallRecord> records = new ArrayList<>();

        @Override public Optional<CallRecord> find(UUID id) { return records.stream().filter(r -> r.id().equals(id)).findFirst(); }
        @Override public Optional<CallRecord> findByIdempotency(String anchor, String request) { return Optional.empty(); }
        @Override public List<CallRecord> listByAnchors(Set<String> anchors) { return records.stream().filter(r -> anchors.contains(r.contactAnchorPointId())).toList(); }
        @Override public int countPending() { return 0; }
        @Override public void saveNew(CallRecord record) { records.add(record); }
        @Override public CallRecord replace(CallRecord replacement, long expectedVersion) { records.set(0, replacement); return replacement; }
        @Override public List<CallRecord> recoverProcessing(Instant now) { return List.of(); }
        @Override public List<CallRecord> listRunnable(Instant now, int limit) { return List.of(); }
        @Override public void close() {}
    }
}
