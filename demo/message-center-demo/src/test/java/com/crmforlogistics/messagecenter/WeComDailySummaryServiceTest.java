package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComDailySummaryServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-29T16:05:00Z");

    @Test
    void createsOnlyPreviousBeijingDayJobsIdempotently() throws Exception {
        FakeRepository repository = new FakeRepository();
        WeComDailySummaryService service = service(repository, day -> List.of(
                conversation(day, "external-b", 2), conversation(day, "external-c", 1)),
                new FakeProgram());

        WeComDailySummaryService.DailyRunResult first = service.run(NOW);
        WeComDailySummaryService.DailyRunResult second = service.run(NOW);

        assertEquals(LocalDate.of(2026, 7, 29), first.day());
        assertEquals(2, first.conversations());
        assertEquals(2, repository.jobs.size());
        assertEquals(2, second.conversations());
        assertTrue(repository.jobs.keySet().stream().allMatch(key -> key.day().equals(first.day())));
    }

    @Test
    void submitsThenResumesPollingUntilCompletion() throws Exception {
        FakeRepository repository = new FakeRepository();
        FakeProgram program = new FakeProgram();
        WeComDailySummaryBatcher.ConversationDay conversation =
                conversation(LocalDate.of(2026, 7, 29), "external-b", 2);
        WeComDailySummaryService service = service(repository, day -> List.of(conversation), program);
        service.run(NOW);
        repository.leaseStatus = "PENDING";
        program.submitResults.add(new WeComSummaryGateway.SubmitResult(0, 0, "job-1"));

        service.tick(NOW.plusSeconds(1));

        assertEquals("job-1", repository.wecomJobId);
        repository.leaseStatus = "SUBMITTED";
        program.pollResults.add(new WeComSummaryGateway.PollResult(0, 0, "job-1", ""));
        program.pollResults.add(new WeComSummaryGateway.PollResult(0, 1, "job-1", "已确认装运时间"));
        service.tick(NOW.plusSeconds(31));
        service.tick(NOW.plusSeconds(61));

        assertEquals("已确认装运时间", repository.completedSummary);
        assertEquals(2, program.pollCalls);
    }

    @Test
    void splitsInputTooLongAndFailsSingleMessageOrOfficialFailure() throws Exception {
        FakeRepository repository = new FakeRepository();
        FakeProgram program = new FakeProgram();
        WeComDailySummaryService service = service(repository,
                day -> List.of(conversation(day, "external-b", 2)), program);
        service.run(NOW);
        repository.leaseStatus = "PENDING";
        program.submitResults.add(new WeComSummaryGateway.SubmitResult(790040, 2, ""));

        service.tick(NOW.plusSeconds(1));

        assertEquals(List.of(1, 1), repository.splitSizes);

        FakeRepository oneRepository = new FakeRepository();
        FakeProgram oneProgram = new FakeProgram();
        WeComDailySummaryService oneService = service(oneRepository,
                day -> List.of(conversation(day, "external-c", 1)), oneProgram);
        oneService.run(NOW);
        oneRepository.leaseStatus = "PENDING";
        oneProgram.submitResults.add(new WeComSummaryGateway.SubmitResult(790040, 2, ""));
        oneService.tick(NOW.plusSeconds(1));
        assertEquals("INPUT_TOO_LONG", oneRepository.failedState);

        FakeRepository failedRepository = new FakeRepository();
        FakeProgram failedProgram = new FakeProgram();
        WeComDailySummaryService failedService = service(failedRepository,
                day -> List.of(conversation(day, "external-d", 1)), failedProgram);
        failedService.run(NOW);
        failedRepository.leaseStatus = "SUBMITTED";
        failedRepository.wecomJobId = "job-failed";
        failedProgram.pollResults.add(new WeComSummaryGateway.PollResult(0, 2, "job-failed", ""));
        failedService.tick(NOW.plusSeconds(1));
        assertEquals("MODEL_FAILED", failedRepository.failedState);
    }

    @Test
    void boundsTransientRetriesAndExpiresAfterDeadline() throws Exception {
        FakeRepository retryRepository = new FakeRepository();
        FakeProgram retryProgram = new FakeProgram();
        retryProgram.failure = new WeComSummaryException(
                "WECOM_SUMMARY_TIMEOUT", 504, "safe timeout");
        WeComDailySummaryService retryService = service(retryRepository,
                day -> List.of(conversation(day, "external-b", 1)), retryProgram);
        retryService.run(NOW);
        retryRepository.leaseStatus = "PENDING";
        retryRepository.attemptCount = 19;
        retryService.tick(NOW.plusSeconds(1));
        assertEquals("RETRY_EXHAUSTED", retryRepository.failedState);

        FakeRepository expiredRepository = new FakeRepository();
        WeComDailySummaryService expiredService = service(expiredRepository,
                day -> List.of(conversation(day, "external-c", 1)), new FakeProgram());
        expiredService.run(NOW);
        expiredRepository.leaseStatus = "PENDING";
        expiredRepository.deadline = NOW.plusSeconds(5);
        expiredService.tick(NOW.plusSeconds(6));
        assertEquals("DEADLINE_EXCEEDED", expiredRepository.failedState);
    }

    private static WeComDailySummaryService service(
            FakeRepository repository, WeComDailySummaryService.ConversationSource source,
            FakeProgram program) {
        Config config = new Config(Map.of(
                "WECOM_CHATDATA_PROGRAM_ID", "program-1",
                "WECOM_DAILY_SUMMARY_MAX_TRANSIENT_ATTEMPTS", "20"));
        return WeComDailySummaryService.forTests(config, () -> installation(), source,
                program, repository, "worker-1");
    }

    private static WeComDailySummaryBatcher.ConversationDay conversation(
            LocalDate day, String externalUserId, int count) {
        List<WeComDailySummaryBatcher.MessageReference> messages = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            messages.add(new WeComDailySummaryBatcher.MessageReference(
                    externalUserId + "-msg-" + index, "secret-" + index, index + 1, "2"));
        }
        return new WeComDailySummaryBatcher.ConversationDay(
                day, "employee-a", externalUserId, List.copyOf(messages));
    }

    private static WeComAuthorizationStore.ResolvedInstallation installation() {
        return new WeComAuthorizationStore.ResolvedInstallation(
                new WeComAuthorizationStore.Installation("installation-1", "dk-suite", "ww-corp",
                        "1000001", "encrypted", WeComAuthorizationStore.AuthStatus.ACTIVE,
                        NOW, NOW, NOW, 1), "permanent-code");
    }

    private static final class FakeProgram implements WeComDailySummaryService.SummaryProgram {
        final ArrayDeque<WeComSummaryGateway.SubmitResult> submitResults = new ArrayDeque<>();
        final ArrayDeque<WeComSummaryGateway.PollResult> pollResults = new ArrayDeque<>();
        WeComSummaryException failure;
        int pollCalls;

        @Override
        public WeComSummaryGateway.SubmitResult submit(
                WeComAuthorizationStore.ResolvedInstallation installation,
                List<WeComSummaryGateway.MessageReference> messages, Duration timeout)
                throws WeComSummaryException {
            if (failure != null) throw failure;
            return submitResults.removeFirst();
        }

        @Override
        public WeComSummaryGateway.PollResult poll(
                WeComAuthorizationStore.ResolvedInstallation installation,
                String jobId, Duration timeout) throws WeComSummaryException {
            pollCalls++;
            if (failure != null) throw failure;
            return pollResults.removeFirst();
        }
    }

    private static final class FakeRepository implements WeComDailySummaryRepository {
        final Map<DailySummaryKey, List<String>> jobs = new LinkedHashMap<>();
        String leaseStatus;
        String wecomJobId;
        int attemptCount;
        Instant deadline = NOW.plus(Duration.ofHours(24));
        String completedSummary;
        String failedState;
        List<Integer> splitSizes = List.of();
        UUID jobId = UUID.randomUUID();

        @Override
        public void ensureDailyJob(DailySummaryKey key, List<String> digests,
                                   Instant deadline, Instant now) {
            jobs.putIfAbsent(key, List.copyOf(digests));
            this.deadline = deadline;
        }

        @Override
        public Optional<LeasedSummaryJob> leaseNext(String owner, Instant now, Duration lease) throws Exception {
            if (leaseStatus == null || jobs.isEmpty()) return Optional.empty();
            DailySummaryKey key = jobs.keySet().iterator().next();
            String status = leaseStatus;
            leaseStatus = null;
            String digest = WeComDailySummaryService.aggregateDigest(jobs.get(key));
            return Optional.of(new LeasedSummaryJob(jobId, key, digest, key.sliceEnd() - key.sliceStart(),
                    status, wecomJobId, attemptCount, deadline));
        }

        @Override public void markSubmitted(UUID id, String job, Instant next) {
            wecomJobId = job;
            leaseStatus = "SUBMITTED";
        }
        @Override public void markCompleted(UUID id, String summary, Coverage coverage, Instant now) {
            completedSummary = summary;
            leaseStatus = null;
        }
        @Override public void markRetry(UUID id, String code, Instant next) {}
        @Override public void markFailed(UUID id, String code, String state, Instant now) {
            failedState = state;
            leaseStatus = null;
        }
        @Override public void replaceWithSplit(UUID id, List<String> left, List<String> right,
                                               int maxBatches, Instant now) {
            splitSizes = List.of(left.size(), right.size());
            leaseStatus = null;
        }
        @Override public Optional<DailySummary> findSummary(DailyConversationKey key) {
            return Optional.empty();
        }
    }
}
