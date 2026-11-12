package com.crmforlogistics.messagecenter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class WeComDailySummaryService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration PROGRAM_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    private final Config config;
    private final InstallationProvider installations;
    private final ConversationSource conversations;
    private final SummaryProgram program;
    private final WeComDailySummaryRepository repository;
    private final String workerId;

    public WeComDailySummaryService(Config config,
                                    WeComAuthorizationStore authorizationStore,
                                    WeComDailySummaryBatcher batcher,
                                    WeComSummaryGateway gateway,
                                    WeComDailySummaryRepository repository) {
        this(config,
                () -> authorizationStore.resolveActive(
                        config.wecomSuiteId(), config.wecomLoginAuthCorpId()),
                batcher::loadDay,
                new SummaryProgram() {
                    @Override
                    public WeComSummaryGateway.SubmitResult submit(
                            WeComAuthorizationStore.ResolvedInstallation installation,
                            List<WeComSummaryGateway.MessageReference> messages,
                            Duration timeout) throws WeComSummaryException {
                        return gateway.submit(installation, messages, timeout);
                    }

                    @Override
                    public WeComSummaryGateway.PollResult poll(
                            WeComAuthorizationStore.ResolvedInstallation installation,
                            String jobId, Duration timeout) throws WeComSummaryException {
                        return gateway.poll(installation, jobId, timeout);
                    }
                },
                repository,
                "summary-" + java.util.UUID.randomUUID().toString().replace("-", ""));
    }

    private WeComDailySummaryService(Config config, InstallationProvider installations,
                                     ConversationSource conversations, SummaryProgram program,
                                     WeComDailySummaryRepository repository, String workerId) {
        this.config = Objects.requireNonNull(config, "config");
        this.installations = Objects.requireNonNull(installations, "installations");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.program = Objects.requireNonNull(program, "program");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
    }

    static WeComDailySummaryService forTests(Config config, InstallationProvider installations,
                                              ConversationSource conversations, SummaryProgram program,
                                              WeComDailySummaryRepository repository, String workerId) {
        return new WeComDailySummaryService(config, installations, conversations,
                program, repository, workerId);
    }

    public DailyRunResult run(Instant now) throws Exception {
        Objects.requireNonNull(now, "now");
        WeComAuthorizationStore.ResolvedInstallation installation = installations.resolve();
        LocalDate day = now.atZone(BUSINESS_ZONE).toLocalDate().minusDays(1);
        List<WeComDailySummaryBatcher.ConversationDay> days = conversations.load(day);
        int batchesCreated = 0;
        for (WeComDailySummaryBatcher.ConversationDay conversation : days) {
            List<WeComDailySummaryBatcher.SummaryBatch> batches = split(conversation);
            for (WeComDailySummaryBatcher.SummaryBatch batch : batches) {
                WeComDailySummaryRepository.DailySummaryKey key = new WeComDailySummaryRepository.DailySummaryKey(
                        installation.installation().installationId(),
                        installation.installation().authCorpId(), batch.day(), batch.userId(),
                        batch.externalUserId(), batch.sliceStart(), batch.sliceEnd());
                repository.ensureDailyJob(key, msgidDigests(batch.messages()),
                        now.plus(Duration.ofHours(config.wecomDailySummaryMaxWaitHours())), now);
                batchesCreated++;
            }
        }
        return new DailyRunResult(day, days.size(), batchesCreated);
    }

    public WorkResult tick(Instant now) throws Exception {
        Objects.requireNonNull(now, "now");
        var leased = repository.leaseNext(workerId, now, LEASE_DURATION);
        if (leased.isEmpty()) return new WorkResult("IDLE", "");
        WeComDailySummaryRepository.LeasedSummaryJob job = leased.get();
        if (!now.isBefore(job.deadline())) {
            repository.markFailed(job.jobId(), "DEADLINE_EXCEEDED", "DEADLINE_EXCEEDED", now);
            return new WorkResult("FAILED", "DEADLINE_EXCEEDED");
        }
        WeComAuthorizationStore.ResolvedInstallation installation = installations.resolve();
        List<WeComDailySummaryBatcher.MessageReference> messages = loadSlice(job);
        if (!aggregateDigest(msgidDigests(messages)).equals(job.messageDigest())) {
            repository.markFailed(job.jobId(), "REFERENCE_DRIFT", "REFERENCE_DRIFT", now);
            return new WorkResult("FAILED", "REFERENCE_DRIFT");
        }
        try {
            if (job.wecomJobId() == null || job.wecomJobId().isBlank()) {
                return submit(job, installation, messages, now);
            }
            return poll(job, installation, messages.size(), now);
        } catch (WeComSummaryException exception) {
            if (job.attemptCount() + 1 >= config.wecomDailySummaryMaxTransientAttempts()) {
                repository.markFailed(job.jobId(), exception.code(), "RETRY_EXHAUSTED", now);
                return new WorkResult("FAILED", "RETRY_EXHAUSTED");
            }
            repository.markRetry(job.jobId(), exception.code(), now.plusSeconds(backoff(job.attemptCount())));
            return new WorkResult("RETRY", exception.code());
        }
    }

    private WorkResult submit(WeComDailySummaryRepository.LeasedSummaryJob job,
                              WeComAuthorizationStore.ResolvedInstallation installation,
                              List<WeComDailySummaryBatcher.MessageReference> messages,
                              Instant now) throws Exception {
        List<WeComSummaryGateway.MessageReference> input = messages.stream()
                .map(message -> new WeComSummaryGateway.MessageReference(
                        message.msgid(), message.secretKey())).toList();
        WeComSummaryGateway.SubmitResult result = program.submit(installation, input, PROGRAM_TIMEOUT);
        if (result.errcode() == 790040) {
            if (messages.size() < 2) {
                repository.markFailed(job.jobId(), "790040", "INPUT_TOO_LONG", now);
                return new WorkResult("FAILED", "INPUT_TOO_LONG");
            }
            int midpoint = messages.size() / 2;
            repository.replaceWithSplit(job.jobId(),
                    msgidDigests(messages.subList(0, midpoint)),
                    msgidDigests(messages.subList(midpoint, messages.size())),
                    config.wecomDailySummaryMaxBatches(), now);
            return new WorkResult("SPLIT", "790040");
        }
        if (result.errcode() != 0) {
            String code = "OFFICIAL_" + result.errcode();
            repository.markFailed(job.jobId(), code, "SUBMIT_FAILED", now);
            return new WorkResult("FAILED", code);
        }
        repository.markSubmitted(job.jobId(), result.jobId(),
                now.plusSeconds(config.wecomDailySummaryPollInitialSeconds()));
        return new WorkResult("SUBMITTED", "");
    }

    private WorkResult poll(WeComDailySummaryRepository.LeasedSummaryJob job,
                            WeComAuthorizationStore.ResolvedInstallation installation,
                            int messageCount, Instant now) throws Exception {
        WeComSummaryGateway.PollResult result = program.poll(
                installation, job.wecomJobId(), PROGRAM_TIMEOUT);
        if (result.errcode() != 0) {
            String code = "OFFICIAL_" + result.errcode();
            repository.markFailed(job.jobId(), code, "POLL_FAILED", now);
            return new WorkResult("FAILED", code);
        }
        if (result.status() == 0) {
            repository.markSubmitted(job.jobId(), job.wecomJobId(),
                    now.plusSeconds(config.wecomDailySummaryPollInitialSeconds()));
            return new WorkResult("POLLING", "");
        }
        if (result.status() == 2) {
            repository.markFailed(job.jobId(), "MODEL_FAILED", "MODEL_FAILED", now);
            return new WorkResult("FAILED", "MODEL_FAILED");
        }
        repository.markCompleted(job.jobId(), result.summary(),
                new WeComDailySummaryRepository.Coverage(messageCount, messageCount, 1, "COMPLETE"), now);
        return new WorkResult("COMPLETED", "");
    }

    private List<WeComDailySummaryBatcher.MessageReference> loadSlice(
            WeComDailySummaryRepository.LeasedSummaryJob job) throws Exception {
        WeComDailySummaryRepository.DailySummaryKey key = job.key();
        WeComDailySummaryBatcher.ConversationDay conversation = conversations.load(key.day()).stream()
                .filter(item -> item.userId().equals(key.userId())
                        && item.externalUserId().equals(key.externalUserId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("daily summary conversation missing"));
        if (key.sliceStart() < 0 || key.sliceEnd() > conversation.messages().size()
                || key.sliceStart() >= key.sliceEnd()) {
            throw new IllegalStateException("daily summary slice unavailable");
        }
        return List.copyOf(conversation.messages().subList(key.sliceStart(), key.sliceEnd()));
    }

    private List<WeComDailySummaryBatcher.SummaryBatch> split(
            WeComDailySummaryBatcher.ConversationDay day) {
        WeComDailySummaryBatcher batcher = new WeComDailySummaryBatcher(config,
                new WeComChatDataStore(config));
        return batcher.split(day);
    }

    private long backoff(int attemptCount) {
        long initial = config.wecomDailySummaryPollInitialSeconds();
        int shift = Math.min(attemptCount, 20);
        long candidate = initial << shift;
        return Math.min(candidate, config.wecomDailySummaryMaxBackoffSeconds());
    }

    static List<String> msgidDigests(List<WeComDailySummaryBatcher.MessageReference> messages)
            throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        java.util.ArrayList<String> result = new java.util.ArrayList<>(messages.size());
        for (WeComDailySummaryBatcher.MessageReference message : messages) {
            result.add(HexFormat.of().formatHex(hash.digest(
                    message.msgid().getBytes(StandardCharsets.UTF_8))));
            hash.reset();
        }
        return List.copyOf(result);
    }

    static String aggregateDigest(List<String> digests) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (String digest : digests) {
            hash.update(digest.getBytes(StandardCharsets.US_ASCII));
            hash.update((byte) '\n');
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    interface InstallationProvider {
        WeComAuthorizationStore.ResolvedInstallation resolve() throws Exception;
    }

    interface ConversationSource {
        List<WeComDailySummaryBatcher.ConversationDay> load(LocalDate day) throws Exception;
    }

    interface SummaryProgram {
        WeComSummaryGateway.SubmitResult submit(
                WeComAuthorizationStore.ResolvedInstallation installation,
                List<WeComSummaryGateway.MessageReference> messages, Duration timeout)
                throws WeComSummaryException;

        WeComSummaryGateway.PollResult poll(
                WeComAuthorizationStore.ResolvedInstallation installation,
                String jobId, Duration timeout) throws WeComSummaryException;
    }

    public record DailyRunResult(LocalDate day, int conversations, int batches) {}
    public record WorkResult(String state, String code) {}
}
