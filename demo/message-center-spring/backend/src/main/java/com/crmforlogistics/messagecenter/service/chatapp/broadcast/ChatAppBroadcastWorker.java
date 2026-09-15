package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastJobEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastRecipientEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastJobMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastRecipientMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastReconciliationEvidenceMapper;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastReconciliationEvidenceEntity;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppAccountResolver;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway.BroadcastQuery;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway.BroadcastSubmission;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway.ReconciliationItem;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway.SubmissionRecipient;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.AggregateResult;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastStatus;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.JobStatus;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.JobType;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ChatAppBroadcastWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ChatAppBroadcastWorker.class);
    private static final int MAX_BATCH = 10;
    private static final int RECONCILE_PAGE_SIZE = 100;
    private static final int MAX_RECONCILE_PAGES = 20;
    private static final Duration LEASE_DURATION = Duration.ofSeconds(60);

    private final ChatAppBroadcastJobMapper jobMapper;
    private final ChatAppBroadcastMapper broadcastMapper;
    private final ChatAppBroadcastRecipientMapper recipientMapper;
    private final ChatAppAccountResolver accountResolver;
    private final ChatAppBroadcastGateway gateway;
    private final EventHub eventHub;
    private final ChatAppBroadcastMessageProjector messageProjector;
    private final ChatAppBroadcastReconciliationEvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionOperations transactions;

    public ChatAppBroadcastWorker(
            ChatAppBroadcastJobMapper jobMapper,
            ChatAppBroadcastMapper broadcastMapper,
            ChatAppBroadcastRecipientMapper recipientMapper,
            ChatAppAccountResolver accountResolver,
            ChatAppBroadcastGateway gateway,
            EventHub eventHub,
            ChatAppBroadcastMessageProjector messageProjector,
            ChatAppBroadcastReconciliationEvidenceMapper evidenceMapper,
            ObjectMapper objectMapper,
            Clock clock,
            TransactionOperations transactions) {
        this.jobMapper = Objects.requireNonNull(jobMapper);
        this.broadcastMapper = Objects.requireNonNull(broadcastMapper);
        this.recipientMapper = Objects.requireNonNull(recipientMapper);
        this.accountResolver = Objects.requireNonNull(accountResolver);
        this.gateway = Objects.requireNonNull(gateway);
        this.eventHub = Objects.requireNonNull(eventHub);
        this.messageProjector = Objects.requireNonNull(messageProjector);
        this.evidenceMapper = Objects.requireNonNull(evidenceMapper);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.transactions = Objects.requireNonNull(transactions);
    }

    public int runAvailable(String workerId, int limit) {
        String owner = workerId == null || workerId.isBlank() ? "chatapp-broadcast" : workerId.trim();
        Instant now = clock.instant();
        List<ChatAppBroadcastJobEntity> jobs = jobMapper.claimDue(
                owner, now, now.plus(LEASE_DURATION), Math.max(1, Math.min(MAX_BATCH, limit)));
        for (ChatAppBroadcastJobEntity job : jobs) {
            try {
                process(job);
            } catch (BroadcastLeaseLostException ignored) {
                // A stale worker must not mutate state after another owner resolved the lease.
            } catch (RuntimeException error) {
                LOG.error("ChatApp broadcast job failed: jobType={} code={}",
                        job.getJobType(), code(error));
                try {
                    failUnexpected(job, error);
                } catch (BroadcastLeaseLostException ignored) {
                    // The job was recovered while this worker was handling an unexpected failure.
                }
            }
        }
        return jobs.size();
    }

    public int recoverExpiredSubmissions() {
        Instant now = clock.instant();
        RecoveryResult result = transactions.execute(status -> {
            List<java.util.UUID> recovered = jobMapper.recoverExpiredSubmissions(now);
            List<java.util.UUID> ids = new ArrayList<>();
            for (java.util.UUID broadcastId : recovered) {
                if (broadcastMapper.markSubmissionUnknownIfUnresolved(broadcastId, now) == 1) {
                    ids.add(broadcastId);
                }
            }
            return new RecoveryResult(recovered.size(), ids);
        });
        if (result == null) {
            return 0;
        }
        result.changedBroadcastIds().forEach(this::publish);
        return result.recoveredCount();
    }

    private void process(ChatAppBroadcastJobEntity job) {
        if (JobType.SUBMIT.name().equals(job.getJobType())) {
            submit(job);
        } else if (JobType.RECONCILE.name().equals(job.getJobType())) {
            reconcile(job);
        } else {
            failDead(job, "CHATAPP_BROADCAST_JOB_TYPE_INVALID", "CHATAPP_BROADCAST_JOB_TYPE_INVALID");
        }
    }

    private void submit(ChatAppBroadcastJobEntity job) {
        Instant now = clock.instant();
        ChatAppBroadcastEntity broadcast = requireBroadcast(job.getBroadcastId());
        if (!BroadcastStatus.QUEUED.name().equals(broadcast.getStatus())
                && !BroadcastStatus.SUBMITTING.name().equals(broadcast.getStatus())) {
            complete(job, now);
            return;
        }
        List<ChatAppBroadcastRecipientEntity> recipients =
                recipientMapper.findByBroadcastId(broadcast.getId());
        if (recipients.size() != broadcast.getRecipientCount()) {
            failSubmission(job, broadcast, "CHATAPP_BROADCAST_SNAPSHOT_INCOMPLETE", false);
            return;
        }
        transactions.executeWithoutResult(status -> {
            assertLease(job);
            broadcastMapper.updateStatus(broadcast.getId(), BroadcastStatus.SUBMITTING.name(), now);
        });
        try {
            ChatAppBroadcastGateway.SubmissionResult result = transactions.execute(status -> {
                ChannelAccountEntity account = requireSubmissionAccount(broadcast);
                BroadcastSubmission command = new BroadcastSubmission(
                        broadcast.getChannelAccountId(), account.getAccountIdentifier(),
                        broadcast.getTemplateCode(), broadcast.getTemplateName(), broadcast.getLanguageCode(),
                        broadcast.getId().toString(), recipients.stream()
                        .map(recipient -> new SubmissionRecipient(
                                recipient.getRecipientNumberSnapshot(), params(recipient.getTemplateParamsJsonb())))
                        .toList());
                return gateway.submit(command);
            });
            transactions.executeWithoutResult(status -> {
                if (jobMapper.completeIfLeased(job.getId(), job.getLeaseId(), now) != 1) {
                    throw leaseLost();
                }
                broadcastMapper.markSubmitted(
                        broadcast.getId(), bounded(result.groupMessageId(), 255),
                        bounded(result.providerRequestId(), 255),
                        bounded(result.providerCode(), 100), now);
                recipientMapper.updateAllStatuses(
                        broadcast.getId(), RecipientStatus.PROCESSING.name(), now);
                ChatAppBroadcastJobEntity reconcile = new ChatAppBroadcastJobEntity();
                reconcile.setId(java.util.UUID.randomUUID());
                reconcile.setBroadcastId(broadcast.getId());
                reconcile.setJobType(JobType.RECONCILE.name());
                reconcile.setStatus(JobStatus.PENDING.name());
                reconcile.setAttemptCount(0);
                reconcile.setMaxAttempts(10);
                reconcile.setNextAttemptAt(now.plusSeconds(5));
                reconcile.setCreatedAt(now);
                reconcile.setUpdatedAt(now);
                jobMapper.insert(reconcile);
            });
            for (ChatAppBroadcastRecipientEntity recipient
                    : recipientMapper.findWithoutMessage(broadcast.getId(), 1000)) {
                try {
                    messageProjector.ensureProcessing(broadcast.getId(), recipient.getId());
                } catch (RuntimeException projectionFailure) {
                    LOG.warn("ChatApp broadcast local projection deferred: broadcastId={} recipientId={} code={}",
                            broadcast.getId(), recipient.getId(), code(projectionFailure));
                }
            }
            publish(broadcast.getId());
        } catch (IllegalArgumentException error) {
            if ("WHATSAPP_ACCOUNT_REASSIGNED".equals(error.getMessage())) {
                failSubmission(job, broadcast, "WHATSAPP_ACCOUNT_REASSIGNED", false);
                return;
            }
            throw error;
        } catch (ChatAppBroadcastException error) {
            failSubmission(job, broadcast, error.getMessage(), error.resultUnknown());
        }
    }

    private ChannelAccountEntity requireSubmissionAccount(ChatAppBroadcastEntity broadcast) {
        if (broadcast.getCreatedByUserId() == null
                || broadcast.getChannelAccountVersion() == null) {
            // Legacy rows created before account-generation fencing.
            return accountResolver.requireCurrentAccount(broadcast.getChannelAccountId());
        }
        return accountResolver.requireOwnedAccountForSend(
                broadcast.getCreatedByUserId(),
                broadcast.getChannelAccountId(),
                broadcast.getChannelAccountVersion());
    }

    private ChannelAccountEntity requireReconciliationAccount(ChatAppBroadcastEntity broadcast) {
        if (broadcast.getCreatedByUserId() == null || broadcast.getChannelAccountVersion() == null) {
            // Legacy rows predate account-generation fencing; preserve their existing lookup path.
            return accountResolver.requireCurrentAccount(broadcast.getChannelAccountId());
        }
        return accountResolver.requireAccountForReconciliation(broadcast.getChannelAccountId());
    }

    private void failSubmission(
            ChatAppBroadcastJobEntity job, ChatAppBroadcastEntity broadcast,
            String errorCode, boolean resultUnknown) {
        Instant now = clock.instant();
        String status = resultUnknown
                ? BroadcastStatus.SUBMISSION_UNKNOWN.name() : BroadcastStatus.FAILED.name();
        transactions.executeWithoutResult(transaction -> {
            if (jobMapper.failIfLeased(
                    job.getId(), job.getLeaseId(), JobStatus.DEAD.name(), now,
                    errorCode, errorCode, now) != 1) {
                throw leaseLost();
            }
            broadcastMapper.markError(broadcast.getId(), status, errorCode, errorCode, now);
            if (!resultUnknown) {
                recipientMapper.updateAllStatuses(
                        broadcast.getId(), RecipientStatus.FAILED_RECIPIENT.name(), now);
                broadcastMapper.updateAggregate(
                        broadcast.getId(), BroadcastStatus.FAILED.name(), 0,
                        broadcast.getRecipientCount(), 0, now, errorCode, errorCode, now);
            }
        });
        publish(broadcast.getId());
    }

    private void reconcile(ChatAppBroadcastJobEntity job) {
        Instant now = clock.instant();
        ChatAppBroadcastEntity broadcast = requireBroadcast(job.getBroadcastId());
        if (terminal(broadcast.getStatus())) {
            complete(job, now);
            return;
        }
        if (broadcast.getProviderGroupMessageId() == null
                || broadcast.getProviderGroupMessageId().isBlank()) {
            markStatusUnknown(job, broadcast, "CHATAPP_BROADCAST_GROUP_ID_MISSING");
            return;
        }
        if (broadcast.getSubmittedAt() == null) {
            markStatusUnknown(job, broadcast, "CHATAPP_BROADCAST_SUBMITTED_AT_MISSING");
            return;
        }
        Instant queryStart = broadcast.getSubmittedAt().minus(Duration.ofMinutes(5));
        Instant queryEnd = now.isBefore(queryStart.plus(Duration.ofDays(90)))
                ? now : queryStart.plus(Duration.ofDays(90));
        if (!queryEnd.isAfter(queryStart)) {
            markStatusUnknown(job, broadcast, "CHATAPP_BROADCAST_RECONCILIATION_TIME_RANGE_INVALID");
            return;
        }
        ChannelAccountEntity account = requireReconciliationAccount(broadcast);
        if (!backfillProcessingMessages(broadcast)) {
            retryReconciliation(
                    job, broadcast, "CHATAPP_BROADCAST_MESSAGE_PROJECTION_FAILED");
            return;
        }
        transactions.executeWithoutResult(status -> {
            assertLease(job);
            broadcastMapper.updateStatus(broadcast.getId(), BroadcastStatus.RECONCILING.name(), now);
        });
        int currentPage = 1;
        try {
            boolean hasNext = true;
            boolean unmatched = false;
            while (hasNext && currentPage <= MAX_RECONCILE_PAGES) {
                var result = gateway.reconcile(new BroadcastQuery(
                        broadcast.getChannelAccountId(), account.getAccountIdentifier(),
                        broadcast.getProviderGroupMessageId(), queryStart, queryEnd,
                        currentPage, RECONCILE_PAGE_SIZE));
                unmatched |= persistReconciliationPage(job, broadcast, result);
                hasNext = result.hasNext();
                currentPage++;
            }
            finalizeReconciliation(
                    job, broadcast, currentPage > MAX_RECONCILE_PAGES && hasNext, unmatched);
            publish(broadcast.getId());
        } catch (ChatAppBroadcastException error) {
            persistProviderFailure(job, broadcast, currentPage, error);
            if (error.retryable()) {
                retryReconciliation(job, broadcast, error.getMessage());
            } else {
                markStatusUnknown(job, broadcast, error.getMessage());
            }
        }
    }

    private boolean persistReconciliationPage(
            ChatAppBroadcastJobEntity job,
            ChatAppBroadcastEntity broadcast,
            ChatAppBroadcastGateway.ReconciliationPage page) {
        Instant now = clock.instant();
        Boolean unmatched = transactions.execute(status -> {
            assertLease(job);
            boolean pageUnmatched = false;
            evidenceMapper.upsert(evidence(job, broadcast, page, 0, null, null, ""));
            ChatAppBroadcastGateway.ProviderDiagnostic diagnostic = page.diagnostic();
            broadcastMapper.updateReconciliationDiagnostic(
                    broadcast.getId(), diagnostic == null ? "" : bounded(diagnostic.providerRequestId(), 255),
                    diagnostic == null ? "" : bounded(diagnostic.providerCode(), 100), "", "", now);
            for (ReconciliationItem item : page.items()) {
                List<ChatAppBroadcastRecipientEntity> candidates;
                if (item.recipientNumber() != null && !item.recipientNumber().isBlank()) {
                    candidates = recipientMapper.findAllByNumber(
                            broadcast.getId(), item.recipientNumber());
                } else if (item.providerMessageId() != null && !item.providerMessageId().isBlank()) {
                    candidates = recipientMapper.findAllByProviderMessageId(
                            broadcast.getId(), item.providerMessageId(), 2);
                } else {
                    candidates = List.of();
                }
                String matchDiagnostic = candidates.size() > 1
                        ? "CHATAPP_BROADCAST_RECIPIENT_AMBIGUOUS" : item.diagnosticCode();
                ChatAppBroadcastRecipientEntity matched = candidates.size() == 1
                        ? candidates.get(0) : null;
                evidenceMapper.upsert(evidence(job, broadcast, page, item.rowNumber(),
                        matched, item, matchDiagnostic));
                if (matched == null) {
                    pageUnmatched = true;
                    continue;
                }
                ChatAppBroadcastMessageProjector.ReconciliationProjectionResult projection =
                        messageProjector.applyReconciliation(
                                broadcast.getId(), matched.getId(), item, now);
                if (projection != null && !projection.diagnosticCode().isBlank()) {
                    evidenceMapper.upsert(evidence(job, broadcast, page, item.rowNumber(),
                            matched, item, projection.diagnosticCode()));
                    pageUnmatched = true;
                }
            }
            return pageUnmatched;
        });
        return Boolean.TRUE.equals(unmatched);
    }

    private boolean backfillProcessingMessages(ChatAppBroadcastEntity broadcast) {
        for (ChatAppBroadcastRecipientEntity recipient
                : recipientMapper.findWithoutMessage(broadcast.getId(), 1000)) {
            try {
                messageProjector.ensureProcessing(broadcast.getId(), recipient.getId());
            } catch (RuntimeException projectionFailure) {
                LOG.warn("ChatApp broadcast reconciliation projection failed: broadcastId={} recipientId={} code={}",
                        broadcast.getId(), recipient.getId(), code(projectionFailure));
                return false;
            }
        }
        return true;
    }

    private void finalizeReconciliation(
            ChatAppBroadcastJobEntity job,
            ChatAppBroadcastEntity broadcast,
            boolean pageLimitReached,
            boolean unmatched) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            assertLease(job);
            if (pageLimitReached) {
                markStatusUnknownOwned(job, broadcast,
                        "CHATAPP_BROADCAST_RECONCILIATION_PAGE_LIMIT", now);
                return;
            }
            if (unmatched) {
                markStatusUnknownOwned(job, broadcast,
                        "CHATAPP_BROADCAST_RECONCILIATION_UNMATCHED_RECIPIENT", now);
                return;
            }
            List<RecipientStatus> statuses = recipientMapper.findByBroadcastId(broadcast.getId()).stream()
                    .map(recipient -> RecipientStatus.valueOf(recipient.getStatus())).toList();
            AggregateResult aggregate = ChatAppBroadcastStateMachine.aggregate(statuses);
            broadcastMapper.updateAggregate(
                    broadcast.getId(), aggregate.status().name(), aggregate.successCount(),
                    aggregate.failedCount(), aggregate.processingCount(), now, null, null, now);
            if (aggregate.status() == BroadcastStatus.RECONCILING) {
                retryReconciliationOwned(job, broadcast,
                        "CHATAPP_BROADCAST_RECONCILIATION_PENDING", now);
            } else if (jobMapper.completeIfLeased(job.getId(), job.getLeaseId(), now) != 1) {
                throw leaseLost();
            }
        });
    }

    private ChatAppBroadcastReconciliationEvidenceEntity evidence(
            ChatAppBroadcastJobEntity job,
            ChatAppBroadcastEntity broadcast,
            ChatAppBroadcastGateway.ReconciliationPage page,
            int rowNumber,
            ChatAppBroadcastRecipientEntity matched,
            ReconciliationItem item,
            String diagnosticOverride) {
        ChatAppBroadcastGateway.ProviderDiagnostic diagnostic = page.diagnostic();
        ChatAppBroadcastReconciliationEvidenceEntity evidence =
                new ChatAppBroadcastReconciliationEvidenceEntity();
        evidence.setId(java.util.UUID.randomUUID());
        evidence.setBroadcastId(broadcast.getId());
        evidence.setJobId(job.getId());
        evidence.setProviderRequestId(bounded(diagnostic == null ? "" : diagnostic.providerRequestId(), 255));
        evidence.setPageNumber(page.page());
        evidence.setRowNumber(rowNumber);
        evidence.setUserNumber(bounded(item == null ? "" : item.recipientNumber(), 50));
        evidence.setProviderMessageId(bounded(item == null ? "" : item.providerMessageId(), 255));
        evidence.setProviderUniqueMessageId(bounded(item == null ? "" : item.providerUniqueMessageId(), 255));
        evidence.setProviderStatus(bounded(item == null
                ? diagnostic == null ? "" : diagnostic.providerCode()
                : item.rawProviderStatus(), 100));
        evidence.setFailureReason(ChatAppBroadcastDiagnosticSanitizer.sanitize(item == null
                ? diagnostic == null ? "" : diagnostic.providerMessage()
                : item.failureReason()));
        evidence.setMatchedRecipientId(matched == null ? null : matched.getId());
        String diagnosticCode = diagnosticOverride == null || diagnosticOverride.isBlank()
                ? item == null ? (diagnostic == null ? "" : diagnostic.diagnosticCode()) : item.diagnosticCode()
                : diagnosticOverride;
        evidence.setDiagnosticCode(bounded(diagnosticCode, 100));
        evidence.setCreatedAt(clock.instant());
        return evidence;
    }

    private void persistProviderFailure(
            ChatAppBroadcastJobEntity job,
            ChatAppBroadcastEntity broadcast,
            int pageNumber,
            ChatAppBroadcastException error) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            assertLease(job);
            ChatAppBroadcastGateway.ReconciliationPage page = new ChatAppBroadcastGateway.ReconciliationPage(
                    List.of(), pageNumber, false,
                    new ChatAppBroadcastGateway.ProviderDiagnostic(
                            error.providerCode(), error.safeMessage(), error.providerRequestId(),
                            error.getMessage()));
            evidenceMapper.upsert(evidence(job, broadcast, page, 0, null, null, error.getMessage()));
            broadcastMapper.updateReconciliationDiagnostic(
                    broadcast.getId(), error.providerRequestId(), error.providerCode(),
                    error.getMessage(), error.safeMessage(), now);
        });
    }

    private void retryReconciliation(
            ChatAppBroadcastJobEntity job, ChatAppBroadcastEntity broadcast, String errorCode) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            assertLease(job);
            retryReconciliationOwned(job, broadcast, errorCode, now);
        });
        publish(broadcast.getId());
    }

    private void retryReconciliationOwned(
            ChatAppBroadcastJobEntity job,
            ChatAppBroadcastEntity broadcast,
            String errorCode,
            Instant now) {
        int nextAttempt = value(job.getAttemptCount()) + 1;
        if (nextAttempt >= value(job.getMaxAttempts())) {
            markStatusUnknownOwned(job, broadcast, errorCode, now);
            return;
        }
        long delaySeconds = Math.min(300, 5L << Math.min(6, nextAttempt));
        if (jobMapper.failIfLeased(
                job.getId(), job.getLeaseId(), JobStatus.FAILED.name(),
                now.plusSeconds(delaySeconds), errorCode, errorCode, now) != 1) {
            throw leaseLost();
        }
    }

    private void markStatusUnknown(
            ChatAppBroadcastJobEntity job, ChatAppBroadcastEntity broadcast, String errorCode) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            assertLease(job);
            markStatusUnknownOwned(job, broadcast, errorCode, now);
        });
        publish(broadcast.getId());
    }

    private void markStatusUnknownOwned(
            ChatAppBroadcastJobEntity job,
            ChatAppBroadcastEntity broadcast,
            String errorCode,
            Instant now) {
        if (jobMapper.failIfLeased(
                job.getId(), job.getLeaseId(), JobStatus.DEAD.name(), now,
                errorCode, errorCode, now) != 1) {
            throw leaseLost();
        }
        List<RecipientStatus> statuses = recipientMapper.findByBroadcastId(broadcast.getId()).stream()
                .map(recipient -> RecipientStatus.valueOf(recipient.getStatus())).toList();
        int successCount = value(broadcast.getSuccessCount());
        int failedCount = value(broadcast.getFailedCount());
        int processingCount = value(broadcast.getProcessingCount());
        if (!statuses.isEmpty()) {
            AggregateResult aggregate = ChatAppBroadcastStateMachine.aggregate(statuses);
            successCount = aggregate.successCount();
            failedCount = aggregate.failedCount();
            processingCount = aggregate.processingCount();
        }
        broadcastMapper.updateAggregate(
                broadcast.getId(), BroadcastStatus.STATUS_UNKNOWN.name(),
                successCount, failedCount, processingCount,
                now, errorCode, errorCode, now);
    }

    private void failUnexpected(ChatAppBroadcastJobEntity job, RuntimeException error) {
        if (JobType.SUBMIT.name().equals(job.getJobType())) {
            ChatAppBroadcastEntity broadcast = requireBroadcast(job.getBroadcastId());
            failSubmission(job, broadcast, code(error), true);
        } else {
            ChatAppBroadcastEntity broadcast = requireBroadcast(job.getBroadcastId());
            retryReconciliation(job, broadcast, code(error));
        }
    }

    private void failDead(ChatAppBroadcastJobEntity job, String code, String message) {
        Instant now = clock.instant();
        jobMapper.failIfLeased(
                job.getId(), job.getLeaseId(), JobStatus.DEAD.name(), now, code, message, now);
    }

    private void complete(ChatAppBroadcastJobEntity job, Instant now) {
        if (jobMapper.completeIfLeased(job.getId(), job.getLeaseId(), now) != 1) {
            throw leaseLost();
        }
    }

    private void assertLease(ChatAppBroadcastJobEntity job) {
        if (jobMapper.assertLeaseOwned(job.getId(), job.getLeaseId()) != 1) {
            throw leaseLost();
        }
    }

    private ChatAppBroadcastEntity requireBroadcast(java.util.UUID broadcastId) {
        return broadcastMapper.findByIdForUpdate(broadcastId)
                .orElseThrow(() -> new IllegalStateException("CHATAPP_BROADCAST_NOT_FOUND"));
    }

    private Map<String, String> params(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("CHATAPP_BROADCAST_PARAMS_INVALID", e);
        }
    }

    private static boolean terminal(String status) {
        return List.of(
                BroadcastStatus.SUCCEEDED.name(), BroadcastStatus.PARTIALLY_FAILED.name(),
                BroadcastStatus.FAILED.name(), BroadcastStatus.CANCELLED.name()).contains(status);
    }

    private void publish(java.util.UUID broadcastId) {
        eventHub.publish("broadcast-updated", Map.of("broadcastId", broadcastId.toString()));
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) return "";
        String safe = value.replace('\r', ' ').replace('\n', ' ').trim();
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }

    private static String code(Throwable error) {
        String message = error.getMessage();
        return message != null && message.matches("CHATAPP_[A-Z0-9_]+")
                ? message : "CHATAPP_BROADCAST_JOB_FAILED";
    }

    private static BroadcastLeaseLostException leaseLost() {
        return new BroadcastLeaseLostException();
    }

    private static final class BroadcastLeaseLostException extends IllegalStateException {
        private BroadcastLeaseLostException() {
            super("CHATAPP_BROADCAST_LEASE_LOST");
        }
    }

    private record RecoveryResult(int recoveredCount, List<java.util.UUID> changedBroadcastIds) {
    }
}
