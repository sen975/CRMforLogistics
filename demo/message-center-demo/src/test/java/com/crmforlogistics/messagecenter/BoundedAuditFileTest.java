package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedAuditFileTest {
    @TempDir
    Path tempDir;

    @Test
    void rotatesBeforeOverflowAndKeepsWholeLines() throws Exception {
        AuditFileSettings settings = settings(4_096, 32_768);
        try (BoundedAuditFile file = open(settings, fixed("2026-08-13T01:00:00Z"))) {
            for (int index = 0; index < 40; index++) {
                file.append(line(index, "x".repeat(160)), BoundedAuditFile.Durability.REQUIRED);
            }
        }

        List<Path> archives = archives("2026-08-13");
        assertFalse(archives.isEmpty());
        assertEquals(40, readAllJsonLines(settings.file()).size()
                + archives.stream().mapToInt(this::gzipLineCount).sum());
        assertTrue(Files.size(settings.file()) <= 4_096L);
        assertTrue(archives.stream().allMatch(path -> path.getFileName().toString()
                .matches("audit\\.2026-08-13\\.\\d{3}\\.jsonl\\.gz")));
        try (var paths = Files.list(tempDir)) {
            assertTrue(paths.noneMatch(path -> path.toString().endsWith(".tmp")
                    || path.toString().endsWith(".rotating")));
        }
    }

    @Test
    void rotatesByFirstLineUtcDate() throws Exception {
        AuditFileSettings settings = settings(4_096, 32_768);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-13T23:59:59Z"));
        try (BoundedAuditFile file = open(settings, clock)) {
            file.append(lineAt(1, "2026-08-13T23:59:59Z", "before"),
                    BoundedAuditFile.Durability.REQUIRED);
            clock.set(Instant.parse("2026-08-14T00:00:00Z"));
            file.append(lineAt(2, "2026-08-14T00:00:00Z", "after"),
                    BoundedAuditFile.Durability.REQUIRED);
        }

        assertEquals(1, archives("2026-08-13").size());
        assertEquals(1, gzipLineCount(archives("2026-08-13").get(0)));
        assertEquals(2, JsonParser.parseString(Files.readString(settings.file())).getAsJsonObject()
                .get("id").getAsInt());
    }

    @Test
    void validatesWholeUtf8JsonObjectWithUtcOccurredAt() throws Exception {
        AuditFileSettings settings = settings(4_096, 32_768);
        try (BoundedAuditFile file = open(settings, fixed("2026-08-13T01:00:00Z"))) {
            assertCode("AUDIT_CURRENT_CORRUPTED", () -> file.append(
                    "{}".getBytes(StandardCharsets.UTF_8), BoundedAuditFile.Durability.REQUIRED));
            assertCode("AUDIT_CURRENT_CORRUPTED", () -> file.append(
                    "{\"occurredAt\":\"2026-08-13T01:00:00Z\"}\n{}\n"
                            .getBytes(StandardCharsets.UTF_8), BoundedAuditFile.Durability.REQUIRED));
            assertCode("AUDIT_CURRENT_CORRUPTED", () -> file.append(
                    new byte[]{(byte) 0xc3, (byte) 0x28, (byte) '\n'},
                    BoundedAuditFile.Durability.REQUIRED));
            assertCode("AUDIT_CURRENT_CORRUPTED", () -> file.append(
                    "[]\n".getBytes(StandardCharsets.UTF_8), BoundedAuditFile.Durability.REQUIRED));
            assertCode("AUDIT_CURRENT_CORRUPTED", () -> file.append(
                    "{\"id\":1}\n".getBytes(StandardCharsets.UTF_8),
                    BoundedAuditFile.Durability.REQUIRED));
            assertCode("AUDIT_CURRENT_CORRUPTED", () -> file.append(
                    "{\"occurredAt\":\"2026-08-13T01:00:00+01:00\"}\n"
                            .getBytes(StandardCharsets.UTF_8), BoundedAuditFile.Durability.REQUIRED));
        }
        assertEquals(0L, Files.size(settings.file()));
    }

    @Test
    void rejectsOneLineLargerThanCurrentFileLimit() throws Exception {
        AuditFileSettings settings = settings(4_096, 32_768);
        byte[] oversized = line(1, "x".repeat(4_096));
        try (BoundedAuditFile file = open(settings, fixed("2026-08-13T01:00:00Z"))) {
            assertCode("AUDIT_EVENT_TOO_LARGE", () ->
                    file.append(oversized, BoundedAuditFile.Durability.REQUIRED));
        }
    }

    @Test
    void bestEffortAppendUsesNonMetadataDurabilityForce() throws Exception {
        AuditFileSettings settings = settings(4_096, 32_768);
        try (BoundedAuditFile file = open(settings, fixed("2026-08-13T01:00:00Z"))) {
            file.append(line(1, "best-effort"), BoundedAuditFile.Durability.BEST_EFFORT);
        }
        assertEquals(1, readAllJsonLines(settings.file()).size());
    }

    @Test
    void rejectsMalformedOversizedInputBeforeUtf8OrJsonParsing() throws Exception {
        AuditFileSettings settings = settings(4_096, 32_768);
        byte[] oversized = new byte[4_097];
        oversized[0] = (byte) 0xc3;
        oversized[1] = (byte) 0x28;
        oversized[oversized.length - 1] = '\n';
        try (BoundedAuditFile file = open(settings, fixed("2026-08-13T01:00:00Z"))) {
            assertCode("AUDIT_EVENT_TOO_LARGE", () ->
                    file.append(oversized, BoundedAuditFile.Durability.REQUIRED));
        }
    }

    @Test
    void doesNotReportPathIoFailureAsWriterLockContention() throws Exception {
        Path parentFile = tempDir.resolve("not-a-directory");
        Files.writeString(parentFile, "occupied", StandardCharsets.UTF_8);
        AuditFileSettings settings = new AuditFileSettings(parentFile.resolve("audit.jsonl"),
                30, 4_096L, 32_768L, 4_096L, Duration.ofMinutes(5));

        assertCode("AUDIT_ROTATION_FAILED", () -> open(
                settings, fixed("2026-08-13T01:00:00Z")));
    }

    @Test
    void rejectsCorruptedCurrentFileWithoutChangingIt() throws Exception {
        AuditFileSettings settings = settings(4_096, 32_768);
        Files.createDirectories(settings.file().getParent());
        Files.writeString(settings.file(), "not-json\n", StandardCharsets.UTF_8);

        try (BoundedAuditFile file = open(settings, fixed("2026-08-13T01:00:00Z"))) {
            assertCode("AUDIT_CURRENT_CORRUPTED", () -> file.append(
                    line(1, "valid"), BoundedAuditFile.Durability.REQUIRED));
        }
        assertEquals("not-json\n", Files.readString(settings.file()));
    }

    @Test
    void rejectsCurrentFileAlreadyBeyondConfiguredLimitBeforeReadingOrRotating() throws Exception {
        AuditFileSettings settings = settings(4_096, 32_768);
        byte[] existing = line(1, "x".repeat(4_096));
        Files.createDirectories(settings.file().getParent());
        Files.write(settings.file(), existing);

        try (BoundedAuditFile file = open(settings, fixed("2026-08-13T01:00:00Z"))) {
            assertCode("AUDIT_CURRENT_CORRUPTED", () -> file.append(
                    line(2, "valid"), BoundedAuditFile.Durability.REQUIRED));
        }
        assertEquals(existing.length, Files.size(settings.file()));
        assertTrue(archives("2026-08-13").isEmpty());
    }

    @Test
    void skipsExistingArchiveWithoutOverwritingIt() throws Exception {
        AuditFileSettings settings = settings(4_096, 32_768);
        Files.createDirectories(settings.file().getParent());
        Files.writeString(settings.file(), new String(line(1, "x".repeat(3_900)),
                StandardCharsets.UTF_8));
        Path archive = tempDir.resolve("audit.2026-08-13.001.jsonl.gz");
        Files.writeString(archive, "reserved", StandardCharsets.UTF_8);
        Path rotating = tempDir.resolve("audit.2026-08-13.001.jsonl.rotating");
        Files.writeString(rotating, "reserved", StandardCharsets.UTF_8);

        try (BoundedAuditFile file = open(settings, fixed("2026-08-13T01:00:00Z"))) {
            file.append(line(2, "x".repeat(200)), BoundedAuditFile.Durability.REQUIRED);
        }
        assertEquals("reserved", Files.readString(archive));
        assertEquals("reserved", Files.readString(rotating));
        assertTrue(Files.exists(tempDir.resolve("audit.2026-08-13.002.jsonl.gz")));
    }

    @Test
    void continuesArchiveSequenceBeyondNineHundredNinetyNine() throws Exception {
        AuditFileSettings settings = settings(4_096, 32_768);
        Files.createDirectories(settings.file().getParent());
        Files.write(settings.file(), line(1, "x".repeat(3_900)));
        for (int sequence = 1; sequence <= 999; sequence++) {
            Files.createFile(tempDir.resolve("audit.2026-08-13."
                    + String.format("%03d", sequence) + ".jsonl.gz"));
        }

        try (BoundedAuditFile file = open(settings, fixed("2026-08-13T01:00:00Z"))) {
            file.append(line(2, "x".repeat(200)), BoundedAuditFile.Durability.REQUIRED);
        }
        assertTrue(Files.exists(tempDir.resolve("audit.2026-08-13.1000.jsonl.gz")));
    }

    @Test
    void sharesOneHandleInsideJvmAndRejectsSecondWriterProcess() throws Exception {
        AuditFileSettings settings = settings(4_096, 32_768);
        Clock clock = fixed("2026-08-13T01:00:00Z");
        AuditDiskSpaceProbe probe = AuditDiskSpaceProbe.system();
        BoundedAuditFile first = BoundedAuditFile.acquire("viewer", settings, clock, probe);
        BoundedAuditFile second = BoundedAuditFile.acquire("viewer", settings, clock, probe);
        first.append(line(1, "a"), BoundedAuditFile.Durability.REQUIRED);
        second.append(line(2, "b"), BoundedAuditFile.Durability.REQUIRED);
        assertEquals(2, Files.readAllLines(first.path()).size());
        assertEquals(3, runLockProbe(first.path()));
        second.close();
        assertEquals(3, runLockProbe(first.path()));
        first.close();
        assertEquals(0, runLockProbe(first.path()));
    }

    @Test
    void rejectsConflictingRegistryFingerprints() throws Exception {
        AuditFileSettings settings = settings(4_096, 32_768);
        Clock clock = fixed("2026-08-13T01:00:00Z");
        AuditDiskSpaceProbe probe = AuditDiskSpaceProbe.system();
        try (BoundedAuditFile ignored = BoundedAuditFile.acquire("viewer", settings, clock, probe)) {
            assertCode("AUDIT_SETTINGS_CONFLICT", () -> BoundedAuditFile.acquire(
                    "viewer", settings(4_096, 65_536), clock, probe));
            assertCode("AUDIT_SETTINGS_CONFLICT", () -> BoundedAuditFile.acquire(
                    "authorization", settings, clock, probe));
            assertCode("AUDIT_SETTINGS_CONFLICT", () -> BoundedAuditFile.acquire(
                    "viewer", settings, fixed("2026-08-14T01:00:00Z"), probe));
            assertCode("AUDIT_SETTINGS_CONFLICT", () -> BoundedAuditFile.acquire(
                    "viewer", settings, clock, directory -> Long.MAX_VALUE));
        }
    }

    @Test
    void sharesEquivalentFixedClockAndSystemProbeConfiguration() throws Exception {
        AuditFileSettings settings = settings(4_096, 32_768);
        try (BoundedAuditFile first = open(settings, fixed("2026-08-13T01:00:00Z"));
             BoundedAuditFile second = open(settings, fixed("2026-08-13T01:00:00Z"))) {
            first.append(line(1, "a"), BoundedAuditFile.Durability.REQUIRED);
            second.append(line(2, "b"), BoundedAuditFile.Durability.REQUIRED);
        }
        assertEquals(2, Files.readAllLines(settings.file()).size());
    }

    @Test
    void exposesRealStartupLifecycleTransitions() throws Exception {
        AuditFileSettings settings = settings(4_096, 32_768);
        try (BoundedAuditFile file = open(settings, fixed("2026-08-13T01:00:00Z"))) {
            assertEquals(BoundedAuditFile.StartupState.STARTUP_CHECKS_PENDING, file.startupState());
            file.prepareForAuthorizationRecovery();
            assertEquals(BoundedAuditFile.StartupState.AUTHORIZATION_RECOVERY_PREPARED,
                    file.startupState());
            file.enableRetentionCleanup();
            assertEquals(BoundedAuditFile.StartupState.RETENTION_CLEANUP_ENABLED,
                    file.startupState());
            assertThrows(IllegalStateException.class, file::enableRetentionCleanup);
            assertThrows(IllegalStateException.class, file::prepareForAuthorizationRecovery);
        }
        try (BoundedAuditFile file = open(settings, fixed("2026-08-13T01:00:00Z"))) {
            assertEquals(BoundedAuditFile.StartupState.STARTUP_CHECKS_PENDING, file.startupState());
            file.enableRetentionCleanup();
            assertEquals(BoundedAuditFile.StartupState.RETENTION_CLEANUP_ENABLED,
                    file.startupState());
        }
    }

    @Test
    void appendsEightHundredConcurrentWholeJsonLines() throws Exception {
        AuditFileSettings settings = settings(4_096, 262_144);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try (BoundedAuditFile file = open(settings, fixed("2026-08-13T01:00:00Z"))) {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                int workerId = worker;
                futures.add(executor.submit(() -> {
                    for (int index = 0; index < 100; index++) {
                        file.append(line(workerId * 100 + index, "payload"),
                                BoundedAuditFile.Durability.REQUIRED);
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        List<String> lines = allLines(settings.file(), archives("2026-08-13"));
        Set<Integer> ids = new HashSet<>();
        for (String line : lines) {
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            assertTrue(ids.add(json.get("id").getAsInt()));
        }
        assertEquals(800, lines.size());
        assertEquals(800, ids.size());
    }

    private BoundedAuditFile open(AuditFileSettings settings, Clock clock)
            throws AuditStorageException {
        return BoundedAuditFile.acquire("viewer", settings, clock, AuditDiskSpaceProbe.system());
    }

    private AuditFileSettings settings(long fileMaxBytes, long streamMaxBytes) {
        return new AuditFileSettings(tempDir.resolve("audit.jsonl"), 30, fileMaxBytes,
                streamMaxBytes, 4_096L, Duration.ofMinutes(5));
    }

    private static Clock fixed(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static byte[] line(int id, String payload) {
        return lineAt(id, "2026-08-13T01:00:00Z", payload);
    }

    private static byte[] lineAt(int id, String occurredAt, String payload) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("occurredAt", occurredAt);
        json.addProperty("payload", payload);
        return (json + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private List<Path> archives(String date) throws IOException {
        try (var paths = Files.list(tempDir)) {
            return paths.filter(path -> path.getFileName().toString()
                            .matches("audit\\." + date + "\\.\\d{3}\\.jsonl\\.gz"))
                    .sorted()
                    .toList();
        }
    }

    private static List<String> readAllJsonLines(Path path) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    private int gzipLineCount(Path archive) {
        try {
            return gzipLines(archive).size();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static List<String> allLines(Path current, List<Path> archives) throws IOException {
        List<String> lines = new ArrayList<>(readAllJsonLines(current));
        for (Path archive : archives) {
            lines.addAll(gzipLines(archive));
        }
        return lines;
    }

    private static List<String> gzipLines(Path archive) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(archive));
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     input, StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }

    private static void assertCode(String expected, ThrowingAction action) {
        AuditStorageException exception = assertThrows(AuditStorageException.class, action::run);
        assertEquals(expected, exception.code());
    }

    private static int runLockProbe(Path path) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                BoundedAuditFileLockProbe.class.getName(), path.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS));
        return process.exitValue();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
