package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataMessageEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.channel.wecom.WeComSummaryException;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank() and '${app.wecom-message-summary-enabled:false}' == 'true'")
public class WeComMessageSummaryWorker {
    private static final Logger log = LoggerFactory.getLogger(WeComMessageSummaryWorker.class);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration PROGRAM_TIMEOUT = Duration.ofSeconds(15);

    private final WeComMessageSummaryRepository repository;
    private final WeComChatDataMessageMapper messageMapper;
    private final WeComCredentialProtector credentials;
    private final WeComSummaryGateway gateway;
    private final InstallationResolver installations;
    private final String workerId;
    private final int batchSize;
    private final int maxConcurrency;
    private final int maxAttempts;
    private final int maxBackoffSeconds;
    private final int pollIntervalSeconds;

    private final com.crmforlogistics.messagecenter.service.aitopic.WeComSummaryTopicActivityBridge topicActivityBridge;

    @Autowired
    public WeComMessageSummaryWorker(AppConfig config,
                                    WeComInstallationService installationService,
                                    WeComMessageSummaryRepository repository,
                                    WeComChatDataMessageMapper messageMapper,
                                    WeComCredentialProtector credentials,
                                    WeComSummaryGateway gateway,
                                    ObjectProvider<com.crmforlogistics.messagecenter.service.aitopic.WeComSummaryTopicActivityBridge> topicActivityBridge) {
        this(repository, messageMapper, credentials, gateway,
                (authCorpId) -> installationService.resolveInstallation(config.wecomSuiteId(), authCorpId),
                "message-summary-" + UUID.randomUUID().toString().replace("-", ""),
                config.wecomMessageSummaryBatchSize(), config.wecomMessageSummaryMaxConcurrency(),
                config.wecomMessageSummaryMaxTransientAttempts(), config.wecomMessageSummaryMaxBackoffSeconds(),
                config.wecomMessageSummaryPollIntervalSeconds(),
                topicActivityBridge.getIfAvailable());
    }

    WeComMessageSummaryWorker(AppConfig config,
                              WeComMessageSummaryRepository repository,
                              WeComChatDataMessageMapper messageMapper,
                              WeComCredentialProtector credentials,
                              WeComSummaryGateway gateway,
                              InstallationResolver installations,
                              String workerId) {
        this(repository, messageMapper, credentials, gateway, installations, workerId,
                config.wecomMessageSummaryBatchSize(), config.wecomMessageSummaryMaxConcurrency(),
                config.wecomMessageSummaryMaxTransientAttempts(), config.wecomMessageSummaryMaxBackoffSeconds(),
                config.wecomMessageSummaryPollIntervalSeconds(), null);
    }

    WeComMessageSummaryWorker(WeComMessageSummaryRepository repository,
                              WeComChatDataMessageMapper messageMapper,
                              WeComCredentialProtector credentials,
                              WeComSummaryGateway gateway,
                              InstallationResolver installations,
                              String workerId,
                              int batchSize, int maxConcurrency, int maxAttempts,
                              int maxBackoffSeconds, int pollIntervalSeconds) {
        this(repository, messageMapper, credentials, gateway, installations, workerId,
                batchSize, maxConcurrency, maxAttempts, maxBackoffSeconds, pollIntervalSeconds, null);
    }

    WeComMessageSummaryWorker(WeComMessageSummaryRepository repository,
                              WeComChatDataMessageMapper messageMapper,
                              WeComCredentialProtector credentials,
                              WeComSummaryGateway gateway,
                              InstallationResolver installations,
                              String workerId,
                              int batchSize, int maxConcurrency, int maxAttempts,
                              int maxBackoffSeconds, int pollIntervalSeconds,
                              com.crmforlogistics.messagecenter.service.aitopic.WeComSummaryTopicActivityBridge topicActivityBridge) {
        this.repository = repository;
        this.messageMapper = messageMapper;
        this.credentials = credentials;
        this.gateway = gateway;
        this.installations = installations;
        this.workerId = workerId;
        this.batchSize = requireRange(batchSize, 1, 100, "batchSize");
        this.maxConcurrency = requireRange(maxConcurrency, 1, 8, "maxConcurrency");
        this.maxAttempts = requireRange(maxAttempts, 1, 100, "maxAttempts");
        this.maxBackoffSeconds = requireRange(maxBackoffSeconds, 1, 3600, "maxBackoffSeconds");
        this.pollIntervalSeconds = requireRange(pollIntervalSeconds, 1, 60, "pollIntervalSeconds");
        this.topicActivityBridge = topicActivityBridge;
    }

    public int runOnce(Instant now) {
        int processed = 0;
        int limit = Math.min(batchSize, maxConcurrency);
        while (processed < limit) {
            Optional<WeComMessageSummaryRepository.LeasedJob> leased =
                    repository.leaseNext(workerId, now, LEASE);
            if (leased.isEmpty()) break;
            WeComMessageSummaryRepository.LeasedJob job = leased.get();
            try {
                process(job, now);
            } catch (RuntimeException failure) {
                log.error("event=wecom.message_summary.job_isolated msgid={} taskId={} failureType={}",
                        job.msgid(), job.id(), failure.getClass().getSimpleName());
            }
            processed++;
        }
        return processed;
    }

    private void process(WeComMessageSummaryRepository.LeasedJob job, Instant now) {
        WeComChatDataMessageEntity entity = messageMapper.findSummaryReference(
                job.installationId(), job.msgid());
        if (entity == null) {
            repository.markFailed(job.id(), "MESSAGE_REFERENCE", "MESSAGE_REFERENCE", "",
                    "summary source message not found", "MESSAGE_REFERENCE", now);
            return;
        }
        if (!isTextMessageType(entity.getMsgtype())) {
            repository.markFailed(job.id(), "UNSUPPORTED_MEDIA", "UNSUPPORTED_MEDIA", "",
                    "official summary supports text messages only", "UNSUPPORTED_MEDIA", now);
            log.info("event=wecom.message_summary.skipped msgid={} taskId={} msgtype={} reason=UNSUPPORTED_MEDIA",
                    job.msgid(), job.id(), entity.getMsgtype());
            return;
        }
        if (entity.getSecretKey() == null || entity.getSecretKey().isBlank()) {
            repository.markFailed(job.id(), "MESSAGE_REFERENCE", "MESSAGE_REFERENCE", "",
                    "summary source secret key is missing", "MESSAGE_REFERENCE", now);
            return;
        }
        try {
            ResolvedInstallation installation = installations.resolve(job.authCorpId());
            String secretKey = credentials.revealSecretKey(entity.getSecretKey());
            if (job.wecomJobId() == null || job.wecomJobId().isBlank()) {
                WeComSummaryGateway.SubmitResult result = gateway.submit(installation,
                        java.util.List.of(new WeComSummaryGateway.MessageReference(job.msgid(), secretKey)),
                        PROGRAM_TIMEOUT);
                if (result.errcode() != 0 || result.jobId() == null || result.jobId().isBlank()) {
                    failOrRetry(job, "OFFICIAL_" + result.errcode(), "OFFICIAL_ERROR",
                            result.rawResponseJson(), "official submit errcode=" + result.errcode(), now);
                    return;
                }
                repository.markSubmitted(job.id(), result.jobId(),
                        now.plusSeconds(pollIntervalSeconds));
                log.info("event=wecom.message_summary.submitted msgid={} taskId={} wecomJobId={} attemptCount={}",
                        job.msgid(), job.id(), result.jobId(), job.attemptCount());
                return;
            }
            WeComSummaryGateway.PollResult result = gateway.poll(installation, job.wecomJobId(), PROGRAM_TIMEOUT);
            if (result.errcode() != 0) {
                failOrRetry(job, "OFFICIAL_" + result.errcode(), "OFFICIAL_ERROR",
                        result.rawResponseJson(), "official poll errcode=" + result.errcode(), now);
            } else if (result.status() == 0) {
                repository.markSubmitted(job.id(), job.wecomJobId(),
                        now.plusSeconds(pollIntervalSeconds));
            } else if (result.status() == 1 && result.summary() != null && !result.summary().isBlank()) {
                repository.markCompleted(job.id(), result.summary(), result.rawResponseJson(),
                        "OFFICIAL_RESULT", now);
                if (topicActivityBridge != null) {
                    try {
                        topicActivityBridge.recordSummaryActivity(job);
                    } catch (RuntimeException topicFailure) {
                        log.warn("event=wecom.message_summary.topic_activity_failed msgid={} taskId={} failureType={} failureMessage={}",
                                job.msgid(), job.id(), topicFailure.getClass().getSimpleName(),
                                safeMessage(topicFailure));
                    }
                }
                log.info("event=wecom.message_summary.completed msgid={} taskId={} wecomJobId={} attemptCount={}",
                        job.msgid(), job.id(), job.wecomJobId(), job.attemptCount());
            } else {
                failOrRetry(job, "AI_RESPONSE_INVALID", "RESPONSE_DATA",
                        result.rawResponseJson(), "official result is missing a non-empty summary", now);
            }
        } catch (WeComSummaryException exception) {
            failOrRetry(job, exception.code(), "PROVIDER_CALL", "", diagnostic(exception), now);
        } catch (RuntimeException exception) {
            log.warn("event=wecom.message_summary.worker_error msgid={} taskId={} failureType={} failureMessage={}",
                    job.msgid(), job.id(), exception.getClass().getSimpleName(), safeMessage(exception));
            failOrRetry(job, "MESSAGE_SUMMARY_RUNTIME_ERROR", "WORKER", "", diagnostic(exception), now);
        }
    }

    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "";
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private static String diagnostic(Throwable exception) {
        String value = exception.getClass().getSimpleName();
        String message = safeMessage(exception);
        if (message.isBlank()) return value;
        return (value + ": " + message)
                .replaceAll("(?i)(secret[_-]?key|access[_-]?token|private[_-]?key)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
    }

    private void failOrRetry(WeComMessageSummaryRepository.LeasedJob job, String code,
                             String stage, String rawResponseJson, String errorDiagnostic, Instant now) {
        if (job.attemptCount() + 1 >= maxAttempts) {
            repository.markFailed(job.id(), code, "RETRY_EXHAUSTED", rawResponseJson, errorDiagnostic, stage, now);
            log.warn("event=wecom.message_summary.failed msgid={} taskId={} attemptCount={} validationStage={} errorCode={}",
                    job.msgid(), job.id(), job.attemptCount(), stage, code);
            return;
        }
        long exponent = Math.min(job.attemptCount(), 10);
        long delay = Math.min((long) pollIntervalSeconds << exponent, maxBackoffSeconds);
        repository.markRetry(job.id(), code, rawResponseJson, errorDiagnostic, stage, now.plusSeconds(delay));
        log.info("event=wecom.message_summary.retry_wait msgid={} taskId={} attemptCount={} validationStage={} errorCode={}",
                job.msgid(), job.id(), job.attemptCount(), stage, code);
    }

    private static int requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " invalid");
        return value;
    }

    private static boolean isTextMessageType(String msgType) {
        return msgType == null || msgType.isBlank() || "1".equals(msgType)
                || "text".equalsIgnoreCase(msgType);
    }

    @FunctionalInterface
    interface InstallationResolver {
        ResolvedInstallation resolve(String authCorpId);
    }
}
