package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class JdbcWeComDailySummaryRepositoryIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.5-bookworm");

    private static Database database;
    private static JdbcWeComDailySummaryRepository repository;

    @BeforeAll
    static void openDatabase() throws Exception {
        Path password = Files.createTempFile("message-center-summary-it", ".txt");
        Files.writeString(password, POSTGRES.getPassword(), StandardCharsets.UTF_8);
        password.toFile().deleteOnExit();
        database = Database.open(new Config(Map.of(
                "DATABASE_URL", POSTGRES.getJdbcUrl(),
                "DATABASE_USER", POSTGRES.getUsername(),
                "DATABASE_PASSWORD_FILE", password.toString())));
        database.migrate();
        repository = new JdbcWeComDailySummaryRepository(database);
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void ensuresLeasesResumesAndCompletesOneFinalSummaryIdempotently() throws Exception {
        Instant now = Instant.parse("2026-07-30T00:00:00Z");
        WeComDailySummaryRepository.DailySummaryKey key = key("external-a", 0, 2);
        List<String> digests = List.of(digest("msg-1"), digest("msg-2"));

        repository.ensureDailyJob(key, digests, now.plus(Duration.ofHours(24)), now);
        repository.ensureDailyJob(key, digests, now.plus(Duration.ofHours(24)), now);

        WeComDailySummaryRepository.LeasedSummaryJob first = repository
                .leaseNext("worker-a", now, Duration.ofMinutes(1)).orElseThrow();
        assertEquals(key, first.key());
        assertEquals(2, first.messageCount());
        assertEquals("PENDING", first.status());
        assertTrue(repository.leaseNext("worker-b", now, Duration.ofMinutes(1)).isEmpty());

        repository.markSubmitted(first.jobId(), "wecom-job-1", now.plusSeconds(30));
        assertTrue(repository.leaseNext("worker-b", now.plusSeconds(29), Duration.ofMinutes(1)).isEmpty());
        WeComDailySummaryRepository.LeasedSummaryJob resumed = repository
                .leaseNext("worker-b", now.plusSeconds(31), Duration.ofMinutes(1)).orElseThrow();
        assertEquals("SUBMITTED", resumed.status());
        assertEquals("wecom-job-1", resumed.wecomJobId());

        repository.markSubmitted(resumed.jobId(), "wecom-job-1", now.plusSeconds(60));
        assertTrue(repository.leaseNext("worker-c", now.plusSeconds(59), Duration.ofMinutes(1)).isEmpty());
        resumed = repository.leaseNext("worker-c", now.plusSeconds(61),
                Duration.ofMinutes(1)).orElseThrow();

        WeComDailySummaryRepository.Coverage coverage =
                new WeComDailySummaryRepository.Coverage(2, 2, 1, "COMPLETE");
        repository.markCompleted(resumed.jobId(), "已确认装运时间", coverage, now.plusSeconds(32));
        repository.markCompleted(resumed.jobId(), "不得覆盖", coverage, now.plusSeconds(33));

        WeComDailySummaryRepository.DailySummary summary = repository.findSummary(
                new WeComDailySummaryRepository.DailyConversationKey(
                        key.installationId(), key.authCorpId(), key.day(), key.userId(),
                        key.externalUserId())).orElseThrow();
        assertEquals("已确认装运时间", summary.summary());
        assertEquals("COMPLETE", summary.completeness());
        assertEquals(2, summary.messageCount());
        assertEquals(1L, countForExternal("wecom_daily_summary_jobs", "external-a"));
        assertEquals(1L, countForExternal("wecom_daily_summaries", "external-a"));
    }

    @Test
    void rejectsDigestDriftAndSupportsRetryAndFailureStates() throws Exception {
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        WeComDailySummaryRepository.DailySummaryKey retryKey = key("external-retry", 0, 1);
        repository.ensureDailyJob(retryKey, List.of(digest("retry")), now.plusSeconds(3600), now);
        WeComDailySummaryRepository.LeasedSummaryJob leased = repository
                .leaseNext("worker-a", now, Duration.ofMinutes(1)).orElseThrow();

        repository.markRetry(leased.jobId(), "TEMPORARY", now.plusSeconds(10));
        assertTrue(repository.leaseNext("worker-b", now.plusSeconds(9), Duration.ofMinutes(1)).isEmpty());
        WeComDailySummaryRepository.LeasedSummaryJob retried = repository
                .leaseNext("worker-b", now.plusSeconds(11), Duration.ofMinutes(1)).orElseThrow();
        assertEquals(1, retried.attemptCount());
        repository.markFailed(retried.jobId(), "MODEL_FAILED", "FAILED", now.plusSeconds(12));

        assertThrows(Exception.class, () -> repository.ensureDailyJob(
                retryKey, List.of(digest("different")), now.plusSeconds(3600), now));
        assertTrue(repository.leaseNext("worker-c", now.plusSeconds(20), Duration.ofMinutes(1)).isEmpty());
    }

    @Test
    void persistsFailureDrivenSplitAndLeasesExpiredJobsForTerminalHandling() throws Exception {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        WeComDailySummaryRepository.DailySummaryKey splitKey = key("external-split", 0, 2);
        String firstDigest = digest("split-1");
        String secondDigest = digest("split-2");
        repository.ensureDailyJob(splitKey, List.of(firstDigest, secondDigest),
                now.plusSeconds(60), now);
        WeComDailySummaryRepository.LeasedSummaryJob parent = repository
                .leaseNext("worker-a", now, Duration.ofMinutes(1)).orElseThrow();

        repository.replaceWithSplit(parent.jobId(), List.of(firstDigest), List.of(secondDigest),
                32, now.plusSeconds(1));

        WeComDailySummaryRepository.LeasedSummaryJob left = repository
                .leaseNext("worker-b", now.plusSeconds(2), Duration.ofMinutes(1)).orElseThrow();
        WeComDailySummaryRepository.LeasedSummaryJob right = repository
                .leaseNext("worker-c", now.plusSeconds(2), Duration.ofMinutes(1)).orElseThrow();
        List<WeComDailySummaryRepository.LeasedSummaryJob> children =
                java.util.stream.Stream.of(left, right)
                        .sorted(java.util.Comparator.comparingInt(item -> item.key().sliceStart()))
                        .toList();
        assertEquals(List.of(0, 1), children.stream().map(item -> item.key().sliceStart()).toList());
        assertEquals(List.of(1, 2), children.stream().map(item -> item.key().sliceEnd()).toList());

        repository.markFailed(left.jobId(), "TEST", "FAILED", now.plusSeconds(3));
        repository.markFailed(right.jobId(), "TEST", "FAILED", now.plusSeconds(3));

        WeComDailySummaryRepository.DailySummaryKey expiredKey = key("external-expired", 0, 1);
        repository.ensureDailyJob(expiredKey, List.of(digest("expired")),
                now.plusSeconds(5), now);
        WeComDailySummaryRepository.LeasedSummaryJob expired = repository
                .leaseNext("worker-d", now.plusSeconds(6), Duration.ofMinutes(1)).orElseThrow();
        assertTrue(expired.deadline().isBefore(now.plusSeconds(6)));
        repository.markFailed(expired.jobId(), "DEADLINE_EXCEEDED", "DEADLINE_EXCEEDED",
                now.plusSeconds(6));
    }

    @Test
    void aggregatesCoverageAndSummaryAcrossAllCompletedLeafBatches() throws Exception {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        WeComDailySummaryRepository.DailySummaryKey firstKey = key("external-multi", 0, 1);
        WeComDailySummaryRepository.DailySummaryKey secondKey = key("external-multi", 1, 3);
        repository.ensureDailyJob(firstKey, List.of(digest("multi-1")),
                now.plusSeconds(3600), now);
        repository.ensureDailyJob(secondKey, List.of(digest("multi-2"), digest("multi-3")),
                now.plusSeconds(3600), now);

        WeComDailySummaryRepository.LeasedSummaryJob first = repository
                .leaseNext("worker-a", now, Duration.ofMinutes(1)).orElseThrow();
        repository.markSubmitted(first.jobId(), "multi-job-1", now.plusSeconds(1));
        repository.markCompleted(first.jobId(), batchSummary(first),
                new WeComDailySummaryRepository.Coverage(
                        first.messageCount(), first.messageCount(), 1, "COMPLETE"),
                now.plusSeconds(2));
        WeComDailySummaryRepository.LeasedSummaryJob second = repository
                .leaseNext("worker-b", now.plusSeconds(3), Duration.ofMinutes(1)).orElseThrow();
        repository.markSubmitted(second.jobId(), "multi-job-2", now.plusSeconds(4));
        repository.markCompleted(second.jobId(), batchSummary(second),
                new WeComDailySummaryRepository.Coverage(
                        second.messageCount(), second.messageCount(), 1, "COMPLETE"),
                now.plusSeconds(5));

        WeComDailySummaryRepository.DailySummary summary = repository.findSummary(
                new WeComDailySummaryRepository.DailyConversationKey(
                        firstKey.installationId(), firstKey.authCorpId(), firstKey.day(),
                        firstKey.userId(), firstKey.externalUserId())).orElseThrow();
        assertEquals("第一批摘要\n\n第二批摘要", summary.summary());
        assertEquals(3, summary.messageCount());
        assertEquals(3, summary.completedMessageCount());
        assertEquals(2, summary.batchCount());
        assertEquals(2, summary.completedBatchCount());
        assertEquals("COMPLETE", summary.completeness());
    }

    @Test
    void persistsPartialCoverageWhenOneLeafBatchFails() throws Exception {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        WeComDailySummaryRepository.DailySummaryKey firstKey = key("external-partial", 0, 1);
        WeComDailySummaryRepository.DailySummaryKey secondKey = key("external-partial", 1, 3);
        repository.ensureDailyJob(firstKey, List.of(digest("partial-1")),
                now.plusSeconds(3600), now);
        repository.ensureDailyJob(secondKey, List.of(digest("partial-2"), digest("partial-3")),
                now.plusSeconds(3600), now);

        WeComDailySummaryRepository.LeasedSummaryJob completed = repository
                .leaseNext("worker-a", now, Duration.ofMinutes(1)).orElseThrow();
        repository.markSubmitted(completed.jobId(), "partial-job", now.plusSeconds(1));
        repository.markCompleted(completed.jobId(), "可用批次摘要",
                new WeComDailySummaryRepository.Coverage(
                        completed.messageCount(), completed.messageCount(), 1, "COMPLETE"),
                now.plusSeconds(2));
        WeComDailySummaryRepository.LeasedSummaryJob failed = repository
                .leaseNext("worker-b", now.plusSeconds(3), Duration.ofMinutes(1)).orElseThrow();
        repository.markFailed(failed.jobId(), "MODEL_FAILED", "MODEL_FAILED",
                now.plusSeconds(4));

        WeComDailySummaryRepository.DailySummary summary = repository.findSummary(
                new WeComDailySummaryRepository.DailyConversationKey(
                        firstKey.installationId(), firstKey.authCorpId(), firstKey.day(),
                        firstKey.userId(), firstKey.externalUserId())).orElseThrow();
        assertEquals("可用批次摘要", summary.summary());
        assertEquals(3, summary.messageCount());
        assertEquals(completed.messageCount(), summary.completedMessageCount());
        assertEquals(2, summary.batchCount());
        assertEquals(1, summary.completedBatchCount());
        assertEquals("PARTIAL", summary.completeness());
    }

    @Test
    void schemaContainsNoRawMessageOrSecretColumns() throws Exception {
        List<String> columns = database.read(connection -> {
            try (var statement = connection.prepareStatement("""
                    select column_name from information_schema.columns
                    where table_name in ('wecom_daily_summary_jobs','wecom_daily_summaries')
                    order by column_name
                    """)) {
                try (ResultSet rows = statement.executeQuery()) {
                    java.util.ArrayList<String> result = new java.util.ArrayList<>();
                    while (rows.next()) result.add(rows.getString(1));
                    return List.copyOf(result);
                }
            }
        });

        assertFalse(columns.contains("secret_key"));
        assertFalse(columns.contains("message_body"));
        assertFalse(columns.contains("raw_messages"));
        assertFalse(columns.contains("msgid"));
        assertTrue(columns.contains("message_digest"));
        assertTrue(columns.contains("summary"));
    }

    private static WeComDailySummaryRepository.DailySummaryKey key(
            String externalUserId, int sliceStart, int sliceEnd) {
        return new WeComDailySummaryRepository.DailySummaryKey(
                "installation-1", "ww-corp", LocalDate.of(2026, 7, 29),
                "employee-a", externalUserId, sliceStart, sliceEnd);
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String batchSummary(WeComDailySummaryRepository.LeasedSummaryJob job) {
        return job.key().sliceStart() == 0 ? "第一批摘要" : "第二批摘要";
    }

    private static long countForExternal(String table, String externalUserId) throws Exception {
        return database.read(connection -> {
            try (var statement = connection.prepareStatement(
                    "select count(*) from " + table + " where external_user_id=?")) {
                statement.setString(1, externalUserId);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        });
    }
}
