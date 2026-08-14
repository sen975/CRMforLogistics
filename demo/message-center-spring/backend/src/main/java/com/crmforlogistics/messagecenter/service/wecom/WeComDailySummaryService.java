package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.channel.wecom.WeComSummaryException;
import com.crmforlogistics.messagecenter.config.AppConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComDailySummaryService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration PROGRAM_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    private final AppConfig config;
    private final InstallationProvider installations;
    private final ConversationSource conversations;
    private final WeComDailySummaryBatcher batcher;
    private final SummaryProgram program;
    private final WeComDailySummaryRepository repository;
    private final String workerId;

    @Autowired
    public WeComDailySummaryService(AppConfig config,
                                    WeComInstallationService installationService,
                                    WeComDailySummaryBatcher batcher,
                                    WeComSummaryGateway gateway,
                                    WeComDailySummaryRepository repository) {
        this(config,
                () -> installationService.resolveInstallation(
                        config.wecomSuiteId(), config.wecomLoginAuthCorpId()),
                batcher::loadDay,
                batcher,
                new SummaryProgram() {
                    @Override
                    public WeComSummaryGateway.SubmitResult submit(
                            ResolvedInstallation installation,
                            List<WeComSummaryGateway.MessageReference> messages,
                            Duration timeout) {
                        return gateway.submit(installation, messages, timeout);
                    }

                    @Override
                    public WeComSummaryGateway.PollResult poll(
                            ResolvedInstallation installation, String jobId, Duration timeout) {
                        return gateway.poll(installation, jobId, timeout);
                    }
                },
                repository,
                "summary-" + UUID.randomUUID().toString().replace("-", ""));
    }

    private WeComDailySummaryService(AppConfig config, InstallationProvider installations,
                                     ConversationSource conversations, WeComDailySummaryBatcher batcher,
                                     SummaryProgram program, WeComDailySummaryRepository repository,
                                     String workerId) {
        this.config = Objects.requireNonNull(config, "config");
        this.installations = Objects.requireNonNull(installations, "installations");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.batcher = Objects.requireNonNull(batcher, "batcher");
        this.program = Objects.requireNonNull(program, "program");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
    }

    static WeComDailySummaryService forTests(AppConfig config, InstallationProvider installations,
                                              ConversationSource conversations,
                                              WeComDailySummaryBatcher batcher, SummaryProgram program,
                                              WeComDailySummaryRepository repository, String workerId) {
        return new WeComDailySummaryService(config, installations, conversations, batcher,
                program, repository, workerId);
    }

    public DailyRunResult run(Instant now) {
        Objects.requireNonNull(now, "now");
        ResolvedInstallation installation = installations.resolve();
        LocalDate day = now.atZone(BUSINESS_ZONE).toLocalDate().minusDays(1);
        List<WeComDailySummaryBatcher.ConversationDay> days = conversations.load(day);
        int batchesCreated = 0;
        for (WeComDailySummaryBatcher.ConversationDay conversation : days) {
            List<WeComDailySummaryBatcher.SummaryBatch> batches = batcher.split(conversation);
            for (WeComDailySummaryBatcher.SummaryBatch batch : batches) {
                WeComDailySummaryRepository.DailySummaryKey key =
                        new WeComDailySummaryRepository.DailySummaryKey(
                                installation.installationId(), installation.authCorpId(),
                                batch.day(), batch.userId(), batch.externalUserId(),
                                batch.sliceStart(), batch.sliceEnd());
                repository.ensureDailyJob(key, msgidDigests(batch.messages()),
                        now.plus(Duration.ofHours(config.wecomDailySummaryMaxWaitHours())), now);
                batchesCreated++;
            }
        }
        return new DailyRunResult(day, days.size(), batchesCreated);
    }

    public WorkResult tick(Instant now) {
        Objects.requireNonNull(now, "now");
        var leased = repository.leaseNext(workerId, now, LEASE_DURATION);
        if (leased.isEmpty()) return new WorkResult("IDLE", "");
        WeComDailySummaryRepository.LeasedSummaryJob job = leased.get();
        if (!now.isBefore(job.deadline())) {
            repository.markFailed(job.jobId(), "DEADLINE_EXCEEDED", "DEADLINE_EXCEEDED", now);
            return new WorkResult("FAILED", "DEADLINE_EXCEEDED");
        }
        ResolvedInstallation installation = installations.resolve();
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
            repository.markRetry(job.jobId(), exception.code(),
                    now.plusSeconds(backoff(job.attemptCount())));
            return new WorkResult("RETRY", exception.code());
        }
    }

    private WorkResult submit(WeComDailySummaryRepository.LeasedSummaryJob job,
                              ResolvedInstallation installation,
                              List<WeComDailySummaryBatcher.MessageReference> messages,
                              Instant now) {
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
                            ResolvedInstallation installation,
                            int messageCount, Instant now) {
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
            WeComDailySummaryRepository.LeasedSummaryJob job) {
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

    private long backoff(int attemptCount) {
        long initial = config.wecomDailySummaryPollInitialSeconds();
        int shift = Math.min(attemptCount, 20);
        long candidate = initial << shift;
        return Math.min(candidate, config.wecomDailySummaryMaxBackoffSeconds());
    }

    static List<String> msgidDigests(List<WeComDailySummaryBatcher.MessageReference> messages) {
        MessageDigest hash = sha256();
        ArrayList<String> result = new ArrayList<>(messages.size());
        for (WeComDailySummaryBatcher.MessageReference message : messages) {
            result.add(HexFormat.of().formatHex(hash.digest(
                    message.msgid().getBytes(StandardCharsets.UTF_8))));
            hash.reset();
        }
        return List.copyOf(result);
    }

    static String aggregateDigest(List<String> digests) {
        MessageDigest hash = sha256();
        for (String digest : digests) {
            hash.update(digest.getBytes(StandardCharsets.US_ASCII));
            hash.update((byte) '\n');
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    interface InstallationProvider {
        ResolvedInstallation resolve();
    }

    interface ConversationSource {
        List<WeComDailySummaryBatcher.ConversationDay> load(LocalDate day);
    }

    interface SummaryProgram {
        WeComSummaryGateway.SubmitResult submit(
                ResolvedInstallation installation,
                List<WeComSummaryGateway.MessageReference> messages, Duration timeout);

        WeComSummaryGateway.PollResult poll(
                ResolvedInstallation installation, String jobId, Duration timeout);
    }

    public record DailyRunResult(LocalDate day, int conversations, int batches) {}
    public record WorkResult(String state, String code) {}
}
