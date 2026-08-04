package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallRecordServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
    private static final String EMAIL = "email:buyer@example.com";
    private static final String PHONE = "phone:8613800000000";

    @TempDir
    Path tempDir;

    @Test
    void bindsToSelectedPhoneAndCreatesExactlyOnceWithoutReadingReplay() throws Exception {
        try (TestContext context = open(
                contactId -> List.of(EMAIL, "phone:+86 138-0000-0000"), 4)) {
            CallRecordService.CreateCallRecordCommand command = command(
                    "phone:+86 138-0000-0000", "request-1");

            CallRecord first;
            try (InputStream input = fixture()) {
                first = context.service().create(command, input);
            }
            CallRecord replay = context.service().create(command, unreadableStream());

            assertEquals(first.id(), replay.id());
            assertEquals(PHONE, first.contactAnchorPointId());
            assertEquals(PHONE, first.phonePointId());
            assertEquals(1, context.repository().listByAnchors(Set.of(PHONE)).size());
            assertEquals(1, publishedAudioCount());
        }
    }

    @Test
    void requiresPhonePointWhenContactGroupHasPhoneIdentities() throws Exception {
        try (TestContext context = open(
                contactId -> List.of(EMAIL, "phone:+86 138-0000-0000"), 4)) {
            CallRecordException missing = assertThrows(CallRecordException.class,
                    () -> context.service().create(commandWithoutPhone("missing-phone-required"),
                            unreadableStream()));
            assertEquals("PHONE_CONTACT_REQUIRED", missing.code());
            assertEquals(400, missing.httpStatus());
        }
    }

    @Test
    void validatesAndRevisesNoteWithUnicodeCodePointAndVersionRules() throws Exception {
        try (TestContext context = open(contactId -> List.of(EMAIL, PHONE), 4)) {
            CallRecordService.CreateCallRecordCommand withNote =
                    new CallRecordService.CreateCallRecordCommand(
                            EMAIL, PHONE, "inbound", NOW, "note-create",
                            "call.mp3", "audio/mpeg", "zhangsan", "初始备注");
            CallRecord created;
            try (InputStream input = fixture()) {
                created = context.service().create(withNote, input);
            }
            assertEquals("初始备注", created.note());

            CallRecord revised = context.service().reviseNote(
                    created.id(), "😀".repeat(4_000), "editor", created.version());
            assertEquals(4_000, revised.note().codePointCount(0, revised.note().length()));
            assertEquals(created.version() + 1, revised.version());

            CallRecordException stale = assertThrows(CallRecordException.class,
                    () -> context.service().reviseNote(
                            created.id(), "stale", "editor", created.version()));
            assertEquals("CALL_RECORD_VERSION_CONFLICT", stale.code());

            CallRecordException tooLong = assertThrows(CallRecordException.class,
                    () -> context.service().reviseNote(
                            created.id(), "😀".repeat(4_001), "editor", revised.version()));
            assertEquals("CALL_RECORD_NOTE_INVALID", tooLong.code());

            CallRecord cleared = context.service().reviseNote(
                    created.id(), "   ", "editor", revised.version());
            assertEquals("", cleared.note());
        }
    }

    @Test
    void validatesContactBindingAndUsesPrimaryWhenTheGroupHasNoPhone() throws Exception {
        try (TestContext withPhone = open(
                contactId -> List.of(EMAIL, "phone:+86 138-0000-0000"), 4)) {
            assertCode("PHONE_CONTACT_REQUIRED", () -> withPhone.service().create(
                    commandWithoutPhone("missing-phone"), unreadableStream()));
            assertCode("CONTACT_BINDING_INVALID", () -> withPhone.service().create(
                    command("phone:+86 139-0000-0000", "foreign-phone"),
                    unreadableStream()));
            assertCode("CALL_RECORD_INPUT_INVALID", () -> withPhone.service().create(
                    new CallRecordService.CreateCallRecordCommand(
                            EMAIL, "phone:+86 138-0000-0000", "sideways", NOW,
                            "bad-direction", "call.mp3", "audio/mpeg", "zhangsan"),
                    unreadableStream()));
            assertCode("CALL_RECORD_INPUT_INVALID", () -> withPhone.service().create(
                    new CallRecordService.CreateCallRecordCommand(
                            EMAIL, "phone:+86 138-0000-0000", "inbound",
                            NOW.plusSeconds(301), "future", "call.mp3",
                            "audio/mpeg", "zhangsan"), unreadableStream()));
            CallRecordException auth = assertThrows(CallRecordException.class,
                    () -> withPhone.service().create(
                            new CallRecordService.CreateCallRecordCommand(
                                    EMAIL, "phone:+86 138-0000-0000", "inbound", NOW,
                                    "missing-actor", "call.mp3", "audio/mpeg", " "),
                            unreadableStream()));
            assertEquals("AUTH_REQUIRED", auth.code());
            assertEquals(401, auth.httpStatus());
        }

        Path withPhoneRoot = tempDir.resolve("with-phone-primary");
        try (TestContext withPhonePrimary = open(withPhoneRoot,
                contactId -> List.of(EMAIL, "phone:+86 138-0000-0000", "wecom:buyer-1"), 4)) {
            CallRecord created;
            try (InputStream input = fixture()) {
                created = withPhonePrimary.service().create(command("", "request-primary"), input);
            }
            assertEquals(PHONE, created.contactAnchorPointId());
            assertEquals(PHONE, created.phonePointId());
        }
    }

    @Test
    void enforcesRequestAndQueueBoundsBeforeReadingAudio() throws Exception {
        try (TestContext context = open(contactId -> List.of(EMAIL, PHONE), 2)) {
            String acceptedRequestId = "请".repeat(255);
            try (InputStream input = fixture()) {
                assertEquals(acceptedRequestId,
                        context.service().create(new CallRecordService.CreateCallRecordCommand(
                                        EMAIL, PHONE, "inbound", NOW, acceptedRequestId,
                                        "录音.mp3", "audio/mpeg", "张".repeat(128)), input)
                                .clientRequestId());
            }
            assertCode("CALL_RECORD_INPUT_INVALID", () -> context.service().create(
                    command("", "r".repeat(256)), unreadableStream()));
        }

        Path queueRoot = tempDir.resolve("queue");
        try (TestContext context = open(queueRoot, contactId -> List.of(EMAIL, PHONE), 1)) {
            try (InputStream input = fixture()) {
                context.service().create(command("", "queued-1"), input);
            }
            assertCode("TRANSCRIPTION_QUEUE_FULL", () -> context.service().create(
                    command("", "queued-2"), unreadableStream()));
            assertFalse(Files.exists(queueRoot.resolve("tmp").resolve("queued-2.upload")));
        }
    }

    @Test
    void preparesIdempotentWinnerAndQueueDecisionBeforeAudioStage() throws Exception {
        try (TestContext context = open(tempDir.resolve("prepare"),
                contactId -> List.of(EMAIL, PHONE), 1)) {
            CallRecordService.CreateCallRecordCommand first = command("", "prepared-1");
            try (InputStream input = fixture()) {
                context.service().create(first, input);
            }

            try (CallRecordService.PreparedCreate replay = context.service().prepareCreate(first)) {
                assertEquals("prepared-1", replay.existing().clientRequestId());
            }

            CallRecordException full = assertThrows(CallRecordException.class,
                    () -> context.service().prepareCreate(command("", "prepared-2")));
            assertEquals("TRANSCRIPTION_QUEUE_FULL", full.code());
        }
    }

    @Test
    void reservesQueueCapacityAcrossInFlightPreparedCreatesAndReleasesOnAbort()
            throws Exception {
        try (TestContext context = open(tempDir.resolve("reservation"),
                contactId -> List.of(EMAIL, PHONE), 1)) {
            CallRecordService.CreateCallRecordCommand reserved =
                    command("", "reserved-1");
            try (CallRecordService.PreparedCreate first = context.service()
                    .prepareCreate(reserved)) {
                CallRecordException duplicate = assertThrows(CallRecordException.class,
                        () -> context.service().prepareCreate(reserved));
                assertEquals("TRANSCRIPTION_QUEUE_FULL", duplicate.code());
                assertQueueFull(context, "reserved-2");
            }

            try (CallRecordService.PreparedCreate released = context.service()
                    .prepareCreate(command("", "reserved-2"))) {
                assertEquals(null, released.existing());
            }
        }
    }

    private static void assertQueueFull(TestContext context, String requestId) {
        CallRecordException full = assertThrows(CallRecordException.class,
                () -> context.service().prepareCreate(command("", requestId)));
        assertEquals("TRANSCRIPTION_QUEUE_FULL", full.code());
    }

    @Test
    void countsManualRetryAgainstInFlightCreateReservations() throws Exception {
        try (TestContext context = open(tempDir.resolve("retry-reservation"),
                contactId -> List.of(EMAIL, PHONE), 1)) {
            CallRecord created = create(context.service(), "failed-for-reservation");
            CallRecord processing = CallRecordStateMachine.lease(
                    created, "worker", NOW, NOW.plusSeconds(60));
            context.repository().replace(processing, created.version());
            CallRecord failed = CallRecordStateMachine.fail(
                    processing, processing.transcription().lease().id(),
                    new CallRecordError("FUNASR_REJECTED", "rejected", false), NOW, 3);
            failed = context.repository().replace(failed, processing.version());

            try (CallRecordService.PreparedCreate ignored = context.service()
                    .prepareCreate(command("", "in-flight-create"))) {
                CallRecord failedRecord = failed;
                CallRecordException full = assertThrows(CallRecordException.class,
                        () -> context.service().retry(
                                failedRecord.id(), "editor", "retry-reserved"));
                assertEquals("TRANSCRIPTION_QUEUE_FULL", full.code());
            }

            assertEquals("queued", context.service().retry(
                    failed.id(), "editor", "retry-after-release").transcription().state());
        }
    }

    @Test
    void rejectsMissingOrClosedPreparedCreateWithStructuredFailure() throws Exception {
        try (TestContext context = open(tempDir.resolve("invalid-prepare"),
                contactId -> List.of(EMAIL, PHONE), 1)) {
            assertCode("CALL_RECORD_PREPARE_INVALID",
                    () -> context.service().create(
                            (CallRecordService.PreparedCreate) null, null));
            CallRecordService.PreparedCreate closed = context.service()
                    .prepareCreate(command("", "closed-prepare"));
            closed.close();
            assertCode("CALL_RECORD_PREPARE_INVALID",
                    () -> context.service().create(closed, null));
        }
    }

    @Test
    void scopesReadsAndOwnsRetryAndRevisionStateChanges() throws Exception {
        try (TestContext context = open(contactId -> List.of(EMAIL, PHONE), 8)) {
            CallRecord failedSource = create(context.service(), "failed-source");
            CallRecord processing = CallRecordStateMachine.lease(
                    failedSource, "worker-1", NOW, NOW.plusSeconds(60));
            context.repository().replace(processing, failedSource.version());
            CallRecord failed = CallRecordStateMachine.fail(
                    processing, processing.transcription().lease().id(),
                    new CallRecordError("FUNASR_REJECTED", "Rejected", false),
                    NOW.plusSeconds(1), 3);
            context.repository().replace(failed, processing.version());

            CallRecord retried = context.service().retry(
                    failed.id(), "editor-1", "manual-retry-1");
            assertEquals("queued", retried.transcription().state());
            assertEquals(0, retried.transcription().attempts());
            assertCode("CALL_RECORD_STATE_INVALID", () -> context.service().retry(
                    retried.id(), "editor-1", "manual-retry-2"));

            CallRecord revisionSource = create(context.service(), "revision-source");
            CallRecord revisionProcessing = CallRecordStateMachine.lease(
                    revisionSource, "worker-2", NOW, NOW.plusSeconds(60));
            context.repository().replace(revisionProcessing, revisionSource.version());
            TranscriptionResult result = new TranscriptionResult(
                    "sensevoice", 1.0, "原始转录",
                    List.of(new TranscriptSegment(0.0, 1.0, "原始转录")),
                    NOW.plusSeconds(1));
            CallRecord completed = CallRecordStateMachine.complete(
                    revisionProcessing, revisionProcessing.transcription().lease().id(),
                    result, NOW.plusSeconds(1));
            context.repository().replace(completed, revisionProcessing.version());

            String maximumRevision = "修".repeat(100_000);
            CallRecord revised = context.service().revise(
                    completed.id(), maximumRevision, "editor-2", completed.version());
            assertEquals("原始转录", revised.transcription().result().originalText());
            assertEquals(maximumRevision, revised.revisions().get(0).text());
            assertEquals("editor-2", revised.revisions().get(0).editedBy());
            assertCode("TRANSCRIPT_VERSION_CONFLICT", () -> context.service().revise(
                    completed.id(), "stale", "editor-2", completed.version()));

            assertEquals(failed.id(), context.service().detail(
                    failed.id(), Set.of(PHONE)).id());
            assertTrue(context.service().list(Set.of(PHONE)).stream()
                    .anyMatch(record -> record.id().equals(failed.id())));
            assertCode("CALL_RECORD_FORBIDDEN", () -> context.service().detail(
                    failed.id(), Set.of("email:other@example.com")));
        }
    }

    private TestContext open(CallRecordService.ContactGroups groups, int queueCapacity)
            throws Exception {
        return open(tempDir, groups, queueCapacity);
    }

    private TestContext open(Path root, CallRecordService.ContactGroups groups,
                             int queueCapacity) throws Exception {
        Config config = new Config(Map.of(
                "CALL_RECORD_DATA_DIR", root.toString(),
                "CALL_RECORD_QUEUE_CAPACITY", Integer.toString(queueCapacity),
                "CALL_RECORD_MAX_RECORDS", "100"));
        FileCallRecordRepository repository =
                FileCallRecordRepository.open(config, fixedClock());
        CallRecordService service = new CallRecordService(
                repository, new LocalAudioStore(config), groups, config, fixedClock());
        return new TestContext(repository, service);
    }

    private static CallRecordService.CreateCallRecordCommand command(
            String phonePointId, String requestId) {
        return new CallRecordService.CreateCallRecordCommand(
                EMAIL, phonePointId.isBlank() ? PHONE : phonePointId, "inbound", NOW, requestId,
                "call.mp3", "audio/mpeg", "zhangsan");
    }

    private static CallRecordService.CreateCallRecordCommand commandWithoutPhone(String requestId) {
        return new CallRecordService.CreateCallRecordCommand(
                EMAIL, "", "inbound", NOW, requestId,
                "call.mp3", "audio/mpeg", "zhangsan");
    }

    private static CallRecord create(CallRecordService service, String requestId)
            throws Exception {
        try (InputStream input = fixture()) {
            return service.create(command("", requestId), input);
        }
    }

    private long publishedAudioCount() throws IOException {
        Path audio = tempDir.resolve("audio");
        if (!Files.exists(audio)) return 0;
        try (var files = Files.list(audio)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".mp3"))
                    .count();
        }
    }

    private static InputStream fixture() {
        InputStream input = CallRecordServiceTest.class.getResourceAsStream(
                "/callrecord/short-ding.mp3");
        if (input == null) throw new IllegalStateException("MP3 fixture is missing");
        return input;
    }

    private static InputStream unreadableStream() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("audio stream must not be read");
            }
        };
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static void assertCode(String expected, ThrowingOperation operation) {
        CallRecordException error = assertThrows(CallRecordException.class, operation::run);
        assertEquals(expected, error.code());
    }

    private record TestContext(
            FileCallRecordRepository repository,
            CallRecordService service) implements AutoCloseable {
        @Override
        public void close() throws CallRecordException {
            repository.close();
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
