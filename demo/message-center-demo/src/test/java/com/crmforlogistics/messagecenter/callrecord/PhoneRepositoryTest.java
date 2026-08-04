package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;
import com.crmforlogistics.messagecenter.UnifiedMessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhoneRepositoryTest {
    private static final Instant BASE = Instant.parse("2026-08-04T10:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void returnsEmptyPageForNoAuthorizedRecords() throws Exception {
        Fixture fixture = fixture();
        try (fixture.records) {
            PhoneRepository repository = new PhoneRepository(fixture.records, fixture.contacts);
            assertEquals(List.of(), repository.page("", 20, "", Set.of()).items());
        }
    }

    @Test
    void sortsByOccurredAtAndIdAndUsesStableCursor() throws Exception {
        Fixture fixture = fixture();
        try (fixture.records) {
            save(fixture, "13800000000", BASE.minusSeconds(30), "older");
            UUID newest = save(fixture, "13800000000", BASE, "newest");
            PhoneRepository repository = new PhoneRepository(fixture.records, fixture.contacts);

            PhoneRepository.PhoneRecordPage first = repository.page("", 1, "", Set.of("phone:13800000000"));
            assertEquals(List.of(newest), first.items().stream().map(PhoneRepository.PhoneRecord::id).toList());
            PhoneRepository.PhoneRecordPage second = repository.page(
                    first.nextCursor(), 1, "", Set.of("phone:13800000000"));
            assertEquals(1, second.items().size());
            assertEquals("older", second.items().get(0).note());
        }
    }

    @Test
    void searchesPhoneDisplayNameAndNoteAndExcludesUnauthorizedAnchors() throws Exception {
        Fixture fixture = fixture();
        try (fixture.records) {
            save(fixture, "13800000000", BASE, "回电确认卸货");
            save(fixture, "13900000000", BASE.minusSeconds(1), "不应返回");
            PhoneRepository repository = new PhoneRepository(fixture.records, fixture.contacts);

            assertEquals(1, repository.page("", 20, "卸货", Set.of("phone:13800000000")).items().size());
            assertEquals(1, repository.page("", 20, "采购", Set.of("phone:13800000000")).items().size());
            assertEquals(1, repository.page("", 20, "138-0000-0000", Set.of("phone:13800000000")).items().size());
            assertEquals(0, repository.page("", 20, "", Set.of("phone:13800000000")).items().stream()
                    .filter(item -> "phone:13900000000".equals(item.phonePointId())).count());
        }
    }

    private Fixture fixture() throws Exception {
        Path data = tempDir.resolve("message-data");
        Files.createDirectories(data.resolve("email"));
        Files.createDirectories(data.resolve("chatapp"));
        Config config = new Config(Map.of(
                "DATA_DIR", data.toString(),
                "EMAIL_DATA_DIR", data.resolve("email").toString(),
                "CHATAPP_DATA_FILE", data.resolve("chatapp/messages.jsonl").toString(),
                "CALL_RECORD_DATA_DIR", tempDir.resolve("records").toString()));
        UnifiedMessageStore contacts = new UnifiedMessageStore(config);
        contacts.ensurePhoneContact("", "13800000000", "采购联系人");
        contacts.ensurePhoneContact("", "13900000000", "运输联系人");
        return new Fixture(FileCallRecordRepository.open(config, Clock.fixed(BASE, ZoneOffset.UTC)), contacts, config);
    }

    private static UUID save(Fixture fixture, String phone, Instant occurredAt, String note) throws Exception {
        UUID id = UUID.randomUUID();
        CallRecord record = new CallRecord(id, "phone:" + phone, "phone:" + phone,
                "inbound", occurredAt, occurredAt, "actor", id.toString(),
                new AudioAsset("audio/" + id + ".mp3", "call.mp3", 3,
                        "a".repeat(64), "audio/mpeg", 2),
                new Transcription("queued", "sensevoice", 0, null, occurredAt, null, null),
                List.of(), null, 1, note);
        Files.createDirectories(fixture.config.callRecordDataDir().resolve("audio"));
        Files.write(fixture.config.callRecordDataDir().resolve(record.audio().relativePath()), new byte[]{1, 2, 3});
        fixture.records.saveNew(record);
        return id;
    }

    private record Fixture(FileCallRecordRepository records, UnifiedMessageStore contacts, Config config) {}
}
