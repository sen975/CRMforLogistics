package com.crmforlogistics.messagecenter.service.message;

import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppOutboundGateway;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppAccountCredentialsException;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.entity.MessageStatusEventEntity;
import com.crmforlogistics.messagecenter.entity.OutboxJobEntity;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.MessageStatusEventMapper;
import com.crmforlogistics.messagecenter.mapper.OutboxJobMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppAccountResolver;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class MessageOutboxWorker {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OutboxJobMapper outboxJobMapper;
    private final MessageMapper messageMapper;
    private final MessageStatusEventMapper statusEventMapper;
    private final ChatAppOutboundGateway gateway;
    private final EventHub eventHub;
    private final TransactionOperations transactions;
    private final ChatAppAccountResolver accountResolver;

    public MessageOutboxWorker(OutboxJobMapper outboxJobMapper,
                               MessageMapper messageMapper,
                               MessageStatusEventMapper statusEventMapper,
                               ChatAppOutboundGateway gateway,
                               EventHub eventHub,
                               TransactionOperations transactions) {
        this(outboxJobMapper, messageMapper, statusEventMapper, gateway, eventHub,
                transactions, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MessageOutboxWorker(OutboxJobMapper outboxJobMapper,
                               MessageMapper messageMapper,
                               MessageStatusEventMapper statusEventMapper,
                               ChatAppOutboundGateway gateway,
                               EventHub eventHub,
                               TransactionOperations transactions,
                               ChatAppAccountResolver accountResolver) {
        this.outboxJobMapper = Objects.requireNonNull(outboxJobMapper);
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.statusEventMapper = Objects.requireNonNull(statusEventMapper);
        this.gateway = Objects.requireNonNull(gateway);
        this.eventHub = Objects.requireNonNull(eventHub);
        this.transactions = Objects.requireNonNull(transactions);
        this.accountResolver = accountResolver;
    }

    public int claimAndProcess(int requestedBatchSize) {
        int batchSize = Math.max(1, Math.min(50, requestedBatchSize));
        String workerId = "chatapp-outbox-" + UUID.randomUUID();
        var jobs = outboxJobMapper.claimDue(
                workerId, Instant.now().plus(30, ChronoUnit.SECONDS), batchSize);
        for (OutboxJobEntity job : jobs) {
            try {
                process(job);
            } catch (OutboxLeaseLostException ignored) {
                // A stale worker must not mutate state after another owner resolved the lease.
            } catch (Exception e) {
                MessageEntity message = messageMapper.selectById(job.getMessageId());
                transactions.executeWithoutResult(status -> {
                    if (markJobDeadIfOwned(
                            job, "UNCLASSIFIED_PROVIDER_FAILURE", safeMessage(e))
                            && message != null) {
                        updateMessageStatus(message, "submission_unknown", Instant.now(),
                                "UNCLASSIFIED_PROVIDER_FAILURE", safeMessage(e));
                    }
                });
                publishMessageChanged();
            }
        }
        return jobs.size();
    }

    public int recoverExpiredProcessing() {
        Integer recovered = transactions.execute(status -> {
            Instant now = Instant.now();
            List<UUID> messageIds = outboxJobMapper.recoverExpiredProcessing(now);
            for (UUID messageId : messageIds) {
                if (messageMapper.markSubmissionUnknownIfUnresolved(messageId, now) == 1) {
                    insertStatusEvent(
                            messageId, "submission_unknown", now,
                            "outbox-lease-expired:" + messageId,
                            "SUBMISSION_UNKNOWN",
                            "Lease expired after provider submission; reconcile by task id");
                }
            }
            return messageIds.size();
        });
        int count = recovered == null ? 0 : recovered;
        if (count > 0) publishMessageChanged();
        return count;
    }

    public void process(OutboxJobEntity job) throws Exception {
        MessageEntity message = messageMapper.selectById(job.getMessageId());
        if (message == null) {
            transactions.executeWithoutResult(status -> {
                if (!markJobDeadIfOwned(
                        job, "MESSAGE_NOT_FOUND", "Outbox message no longer exists")) {
                    throw leaseLost();
                }
            });
            publishMessageChanged();
            return;
        }

        int attempt = value(job.getAttemptCount()) + 1;
        job.setAttemptCount(attempt);
        try {
            ChatAppOutboundGateway.Command command = new ChatAppOutboundGateway.Command(
                    message.getChannelAccountId(), message.getId(),
                    message.getClientRequestId(), message.getMessageKind(), content(message));
            ChatAppOutboundGateway.Submission submission;
            if (requiresOwnershipFence(job, message)) {
                submission = submitWithOwnershipFence(message, command);
            } else {
                submission = gateway.submit(command);
            }
            transactions.executeWithoutResult(status -> {
                Instant now = Instant.now();
                int completed = outboxJobMapper.completeIfOwned(
                        job.getId(), job.getLeaseOwner(), now, now);
                if (completed != 1) {
                    throw leaseLost();
                }
                message.setProviderMessageId(submission.providerMessageId());
                updateMessageStatus(message, "submitted", now, null, null);
                job.setStatus("completed");
                job.setCompletedAt(now);
                job.setUpdatedAt(now);
                job.setLeaseOwner(null);
                job.setLeaseUntil(null);
            });
            publishMessageChanged();
        } catch (ChatAppOutboundGateway.SubmissionUnknownException e) {
            transactions.executeWithoutResult(status -> {
                Instant now = Instant.now();
                if (!markJobDeadIfOwned(job, "SUBMISSION_UNKNOWN", safeMessage(e))) {
                    throw leaseLost();
                }
                updateMessageStatus(message, "submission_unknown", now,
                        "SUBMISSION_UNKNOWN", safeMessage(e));
            });
            publishMessageChanged();
        } catch (ChatAppOutboundGateway.RetryableException e) {
            transactions.executeWithoutResult(status ->
                    retryOrFail(job, message, attempt, "PROVIDER_RETRYABLE", safeMessage(e)));
            publishMessageChanged();
        } catch (ChatAppAccountCredentialsException e) {
            transactions.executeWithoutResult(status -> {
                if (!markJobDeadIfOwned(job, e.code(), safeMessage(e))) {
                    throw leaseLost();
                }
                updateMessageStatus(message, "failed", Instant.now(), e.code(), safeMessage(e));
            });
            publishMessageChanged();
        } catch (IllegalArgumentException e) {
            if ("WHATSAPP_ACCOUNT_REASSIGNED".equals(e.getMessage())) {
                transactions.executeWithoutResult(status -> {
                    if (!markJobDeadIfOwned(job, "WHATSAPP_ACCOUNT_REASSIGNED",
                            "WHATSAPP_ACCOUNT_REASSIGNED")) {
                        throw leaseLost();
                    }
                    updateMessageStatus(message, "failed", Instant.now(),
                            "WHATSAPP_ACCOUNT_REASSIGNED", "WHATSAPP_ACCOUNT_REASSIGNED");
                });
                publishMessageChanged();
                return;
            }
            transactions.executeWithoutResult(status -> {
                if (!markJobDeadIfOwned(job, "INVALID_OUTBOX_MESSAGE", safeMessage(e))) {
                    throw leaseLost();
                }
                updateMessageStatus(message, "failed", Instant.now(),
                        "INVALID_OUTBOX_MESSAGE", safeMessage(e));
            });
            publishMessageChanged();
        }
    }

    private boolean requiresOwnershipFence(OutboxJobEntity job, MessageEntity message) {
        return accountResolver != null
                && "chatapp_send".equals(job.getJobType())
                && message.getCreatedByUserId() != null
                && message.getChannelAccountVersion() != null;
    }

    private ChatAppOutboundGateway.Submission submitWithOwnershipFence(
            MessageEntity message, ChatAppOutboundGateway.Command command) throws Exception {
        try {
            return transactions.execute(status -> {
                accountResolver.requireOwnedAccountForSend(
                        message.getCreatedByUserId(),
                        message.getChannelAccountId(),
                        message.getChannelAccountVersion());
                try {
                    return gateway.submit(command);
                } catch (Exception error) {
                    throw new GatewaySubmissionException(error);
                }
            });
        } catch (GatewaySubmissionException error) {
            Exception cause = error.cause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw cause;
        }
    }

    private void publishMessageChanged() {
        eventHub.publish("message-new", "{}");
    }

    private void retryOrFail(OutboxJobEntity job, MessageEntity message, int attempt,
                             String code, String errorMessage) {
        if (attempt >= value(job.getMaxAttempts())) {
            if (!markJobDeadIfOwned(job, code, errorMessage)) throw leaseLost();
            updateMessageStatus(message, "failed", Instant.now(), code, errorMessage);
            return;
        }
        Instant now = Instant.now();
        long delaySeconds = Math.min(300L, 5L * (1L << Math.min(attempt - 1, 6)));
        Instant nextAttemptAt = now.plus(delaySeconds, ChronoUnit.SECONDS);
        int updated = outboxJobMapper.retryIfOwned(
                job.getId(), job.getLeaseOwner(), nextAttemptAt,
                code, errorMessage, now);
        if (updated != 1) throw leaseLost();
        job.setStatus("retry_wait");
        job.setNextAttemptAt(nextAttemptAt);
        job.setLastErrorCode(code);
        job.setLastErrorMessage(errorMessage);
        job.setUpdatedAt(now);
        job.setLeaseOwner(null);
        job.setLeaseUntil(null);
    }

    private boolean markJobDeadIfOwned(OutboxJobEntity job, String code, String errorMessage) {
        Instant now = Instant.now();
        int updated = outboxJobMapper.markDeadIfOwned(
                job.getId(), job.getLeaseOwner(), code, errorMessage, now);
        if (updated != 1) return false;
        job.setStatus("dead");
        job.setLastErrorCode(code);
        job.setLastErrorMessage(errorMessage);
        job.setUpdatedAt(now);
        job.setLeaseOwner(null);
        job.setLeaseUntil(null);
        return true;
    }

    private void updateMessageStatus(MessageEntity message, String status, Instant occurredAt,
                                     String reasonCode, String reasonMessage) {
        message.setCurrentStatus(status);
        message.setCurrentStatusAt(occurredAt);
        messageMapper.updateDeliveryStatus(
                message.getId(), message.getProviderMessageId(), status, occurredAt);

        insertStatusEvent(message.getId(), status, occurredAt, null, reasonCode, reasonMessage);
    }

    private void insertStatusEvent(UUID messageId, String status, Instant occurredAt,
                                   String providerEventId, String reasonCode,
                                   String reasonMessage) {
        MessageStatusEventEntity event = new MessageStatusEventEntity();
        event.setId(UUID.randomUUID());
        event.setMessageId(messageId);
        event.setStatus(status);
        event.setOccurredAt(occurredAt);
        event.setProviderEventId(providerEventId);
        event.setReasonCode(reasonCode);
        event.setReasonMessage(reasonMessage);
        event.setMetadataJsonb("{}");
        statusEventMapper.insertIgnore(event);
    }

    private static OutboxLeaseLostException leaseLost() {
        return new OutboxLeaseLostException();
    }

    private static Map<String, Object> content(MessageEntity message) {
        try {
            return JSON.readValue(message.getMetadataJsonb(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("CHATAPP_OUTBOX_CONTENT_INVALID", e);
        }
    }

    private static int value(Integer value) { return value == null ? 0 : value; }

    private static String safeMessage(Exception e) {
        String value = e.getMessage();
        return value == null || value.isBlank() ? e.getClass().getSimpleName() : value;
    }

    private static final class OutboxLeaseLostException extends IllegalStateException {
        private OutboxLeaseLostException() {
            super("CHATAPP_OUTBOX_LEASE_LOST_AFTER_SUBMISSION");
        }
    }

    private static final class GatewaySubmissionException extends RuntimeException {
        private GatewaySubmissionException(Exception cause) {
            super(cause);
        }

        private Exception cause() {
            return (Exception) getCause();
        }
    }
}
