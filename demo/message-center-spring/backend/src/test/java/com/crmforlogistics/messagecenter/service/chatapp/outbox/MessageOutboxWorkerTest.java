package com.crmforlogistics.messagecenter.service.chatapp.outbox;

import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppOutboundGateway;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsException;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.OutboxJobEntity;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.MessageStatusEventMapper;
import com.crmforlogistics.messagecenter.mapper.OutboxJobMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppAccountResolver;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageOutboxWorkerTest {
    @Mock OutboxJobMapper outboxJobMapper;
    @Mock MessageMapper messageMapper;
    @Mock MessageStatusEventMapper statusEventMapper;
    @Mock ChatAppOutboundGateway gateway;
    @Mock EventHub eventHub;
    @Mock ChatAppAccountResolver accountResolver;

    @Test
    void successfulSubmissionCompletesJobAndRecordsProviderId() throws Exception {
        Fixture fixture = fixture(0, 3);
        when(messageMapper.selectById(fixture.message.getId())).thenReturn(fixture.message);
        when(gateway.submit(any())).thenReturn(new ChatAppOutboundGateway.Submission("wamid-1"));
        when(outboxJobMapper.completeIfOwned(any(), any(), any(), any())).thenReturn(1);
        MessageOutboxWorker worker = worker();

        worker.process(fixture.job);

        assertThat(fixture.job.getStatus()).isEqualTo("completed");
        assertThat(fixture.message.getProviderMessageId()).isEqualTo("wamid-1");
        assertThat(fixture.message.getCurrentStatus()).isEqualTo("submitted");
        verify(outboxJobMapper).completeIfOwned(
                eq(fixture.job.getId()), eq("worker-1"), any(), any());
        verify(messageMapper, never()).updateById(any(MessageEntity.class));
        verify(messageMapper).updateDeliveryStatus(
                eq(fixture.message.getId()), eq("wamid-1"), eq("submitted"), any(Instant.class));
        verify(statusEventMapper).insertIgnore(any());
        verify(eventHub).publish("message-new", "{}");
    }

    @Test
    void staleLeaseCannotCompleteJobAfterAnotherWorkerTookIt() throws Exception {
        Fixture fixture = fixture(0, 3);
        when(messageMapper.selectById(fixture.message.getId())).thenReturn(fixture.message);
        when(gateway.submit(any())).thenReturn(new ChatAppOutboundGateway.Submission("wamid-1"));
        MessageOutboxWorker worker = worker();
        when(outboxJobMapper.completeIfOwned(any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> worker.process(fixture.job))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHATAPP_OUTBOX_LEASE_LOST_AFTER_SUBMISSION");

        assertThat(fixture.job.getStatus()).isEqualTo("processing");
        verify(outboxJobMapper).completeIfOwned(
                eq(fixture.job.getId()), eq(fixture.job.getLeaseOwner()), any(), any());
        verify(messageMapper, never()).updateDeliveryStatus(
                eq(fixture.message.getId()), any(), eq("submitted"), any());
    }

    @Test
    void unknownSubmissionDoesNotRetryBlindly() throws Exception {
        Fixture fixture = fixture(0, 3);
        when(messageMapper.selectById(fixture.message.getId())).thenReturn(fixture.message);
        when(gateway.submit(any())).thenThrow(new ChatAppOutboundGateway.SubmissionUnknownException("timeout"));
        when(outboxJobMapper.markDeadIfOwned(any(), any(), any(), any(), any())).thenReturn(1);
        MessageOutboxWorker worker = worker();

        worker.process(fixture.job);

        assertThat(fixture.job.getStatus()).isEqualTo("dead");
        assertThat(fixture.job.getAttemptCount()).isEqualTo(1);
        assertThat(fixture.message.getCurrentStatus()).isEqualTo("submission_unknown");
        verify(outboxJobMapper).markDeadIfOwned(
                eq(fixture.job.getId()), eq("worker-1"), eq("SUBMISSION_UNKNOWN"),
                eq("timeout"), any());
        verify(statusEventMapper).insertIgnore(any());
    }

    @Test
    void missingAccountCredentialsFailsTheMessageWithItsOriginalCode() throws Exception {
        Fixture fixture = fixture(0, 3);
        when(messageMapper.selectById(fixture.message.getId())).thenReturn(fixture.message);
        when(gateway.submit(any())).thenThrow(
                new ChatAppAccountCredentialsException("CHATAPP_ACCOUNT_CREDENTIALS_MISSING"));
        when(outboxJobMapper.markDeadIfOwned(any(), any(), any(), any(), any())).thenReturn(1);

        worker().process(fixture.job);

        assertThat(fixture.job.getStatus()).isEqualTo("dead");
        assertThat(fixture.message.getCurrentStatus()).isEqualTo("failed");
        verify(outboxJobMapper).markDeadIfOwned(
                eq(fixture.job.getId()), eq("worker-1"),
                eq("CHATAPP_ACCOUNT_CREDENTIALS_MISSING"),
                eq("CHATAPP_ACCOUNT_CREDENTIALS_MISSING"), any());
    }

    @Test
    void staleLeaseCannotMarkUnknownSubmissionOrMutateMessage() throws Exception {
        Fixture fixture = fixture(0, 3);
        when(messageMapper.selectById(fixture.message.getId())).thenReturn(fixture.message);
        when(gateway.submit(any())).thenThrow(
                new ChatAppOutboundGateway.SubmissionUnknownException("timeout"));
        when(outboxJobMapper.markDeadIfOwned(any(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> worker().process(fixture.job))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHATAPP_OUTBOX_LEASE_LOST_AFTER_SUBMISSION");

        assertThat(fixture.message.getCurrentStatus()).isEqualTo("pending");
        verify(messageMapper, never()).updateDeliveryStatus(any(), any(), any(), any());
        verify(statusEventMapper, never()).insertIgnore(any());
    }

    @Test
    void transientFailureRetriesWithinConfiguredLimit() throws Exception {
        Fixture fixture = fixture(0, 3);
        when(messageMapper.selectById(fixture.message.getId())).thenReturn(fixture.message);
        when(gateway.submit(any())).thenThrow(new ChatAppOutboundGateway.RetryableException("rate limited"));
        when(outboxJobMapper.retryIfOwned(any(), any(), any(), any(), any(), any())).thenReturn(1);
        MessageOutboxWorker worker = worker();

        worker.process(fixture.job);

        assertThat(fixture.job.getStatus()).isEqualTo("retry_wait");
        assertThat(fixture.job.getAttemptCount()).isEqualTo(1);
        assertThat(fixture.job.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(fixture.message.getCurrentStatus()).isEqualTo("pending");
        verify(outboxJobMapper).retryIfOwned(
                eq(fixture.job.getId()), eq("worker-1"), any(), eq("PROVIDER_RETRYABLE"),
                eq("rate limited"), any());
        verify(outboxJobMapper, never()).updateById(any(OutboxJobEntity.class));
    }

    @Test
    void expiredProcessingLeaseMarksUnresolvedMessageSubmissionUnknown() {
        UUID messageId = UUID.randomUUID();
        when(outboxJobMapper.recoverExpiredProcessing(any())).thenReturn(List.of(messageId));
        when(messageMapper.markSubmissionUnknownIfUnresolved(eq(messageId), any())).thenReturn(1);

        int recovered = worker().recoverExpiredProcessing();

        assertThat(recovered).isEqualTo(1);
        verify(messageMapper).markSubmissionUnknownIfUnresolved(eq(messageId), any());
        verify(statusEventMapper).insertIgnore(any());
        verify(eventHub).publish("message-new", "{}");
    }

    @Test
    void expiredProcessingLeaseDoesNotRegressReconciledMessageStatus() {
        UUID messageId = UUID.randomUUID();
        when(outboxJobMapper.recoverExpiredProcessing(any())).thenReturn(List.of(messageId));
        when(messageMapper.markSubmissionUnknownIfUnresolved(eq(messageId), any())).thenReturn(0);

        int recovered = worker().recoverExpiredProcessing();

        assertThat(recovered).isEqualTo(1);
        verify(statusEventMapper, never()).insertIgnore(any());
        verify(eventHub).publish("message-new", "{}");
    }

    @Test
    void submitsOutsideTransactionAndPersistsOutcomeInsideTransaction() throws Exception {
        Fixture fixture = fixture(0, 3);
        TrackingTransactionOperations transactions = new TrackingTransactionOperations();
        when(messageMapper.selectById(fixture.message.getId())).thenReturn(fixture.message);
        when(gateway.submit(any())).thenAnswer(invocation -> {
            assertThat(transactions.isActive()).isFalse();
            return new ChatAppOutboundGateway.Submission("wamid-1");
        });
        when(messageMapper.updateDeliveryStatus(any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(transactions.isActive()).isTrue();
            return 1;
        });
        when(statusEventMapper.insertIgnore(any())).thenAnswer(invocation -> {
            assertThat(transactions.isActive()).isTrue();
            return 1;
        });
        when(outboxJobMapper.completeIfOwned(any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(transactions.isActive()).isTrue();
            return 1;
        });
        MessageOutboxWorker worker = new MessageOutboxWorker(
                outboxJobMapper, messageMapper, statusEventMapper, gateway, eventHub, transactions);

        worker.process(fixture.job);

        assertThat(transactions.isActive()).isFalse();
        assertThat(transactions.executionCount()).isEqualTo(1);
        verify(eventHub).publish("message-new", "{}");
    }

    @Test
    void ownershipFenceSnapshotIsCapturedBeforeProviderSubmission() throws Exception {
        Fixture fixture = fixture(0, 3);
        fixture.message.setCreatedByUserId(UUID.randomUUID());
        fixture.message.setChannelAccountVersion(4L);
        TrackingTransactionOperations transactions = new TrackingTransactionOperations();
        when(messageMapper.selectById(fixture.message.getId())).thenReturn(fixture.message);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(fixture.message.getChannelAccountId());
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        account.setAccountIdentifier("60122222222");
        when(accountResolver.requireOwnedAccountForSend(
                fixture.message.getCreatedByUserId(), fixture.message.getChannelAccountId(), 4L))
                .thenReturn(account);
        when(gateway.submit(any())).thenAnswer(invocation -> {
            assertThat(transactions.isActive()).isFalse();
            return new ChatAppOutboundGateway.Submission("wamid-1");
        });
        when(outboxJobMapper.completeIfOwned(any(), any(), any(), any())).thenReturn(1);
        MessageOutboxWorker worker = new MessageOutboxWorker(
                outboxJobMapper, messageMapper, statusEventMapper, gateway, eventHub,
                transactions, accountResolver);

        worker.process(fixture.job);

        verify(accountResolver).requireOwnedAccountForSend(
                fixture.message.getCreatedByUserId(), fixture.message.getChannelAccountId(), 4L);
        assertThat(transactions.isActive()).isFalse();
    }

    @Test
    void reassignedPendingMessageIsDeadWithoutCallingProvider() throws Exception {
        Fixture fixture = fixture(0, 3);
        UUID ownerId = UUID.randomUUID();
        fixture.message.setCreatedByUserId(ownerId);
        fixture.message.setChannelAccountVersion(4L);
        when(messageMapper.selectById(fixture.message.getId())).thenReturn(fixture.message);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("WHATSAPP_ACCOUNT_REASSIGNED"))
                .when(accountResolver)
                .requireOwnedAccountForSend(ownerId, fixture.message.getChannelAccountId(), 4L);
        when(outboxJobMapper.markDeadIfOwned(any(), any(), any(), any(), any())).thenReturn(1);

        workerWithResolver().process(fixture.job);

        verify(gateway, never()).submit(any());
        assertThat(fixture.job.getStatus()).isEqualTo("dead");
        assertThat(fixture.message.getCurrentStatus()).isEqualTo("failed");
        verify(outboxJobMapper).markDeadIfOwned(
                eq(fixture.job.getId()), eq("worker-1"),
                eq("WHATSAPP_ACCOUNT_REASSIGNED"),
                eq("WHATSAPP_ACCOUNT_REASSIGNED"), any());
    }

    private MessageOutboxWorker worker() {
        return new MessageOutboxWorker(outboxJobMapper, messageMapper, statusEventMapper, gateway,
                eventHub, TransactionOperations.withoutTransaction());
    }

    private MessageOutboxWorker workerWithResolver() {
        return new MessageOutboxWorker(outboxJobMapper, messageMapper, statusEventMapper, gateway,
                eventHub, TransactionOperations.withoutTransaction(), accountResolver);
    }

    private static Fixture fixture(int attempts, int maxAttempts) {
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setChannelAccountId(UUID.randomUUID());
        message.setConversationId(UUID.randomUUID());
        message.setClientRequestId("request-1");
        message.setMessageKind("text");
        message.setBodyText("hello");
        message.setMetadataJsonb("{\"to\":\"60123456789\",\"text\":\"hello\"}");
        message.setCurrentStatus("pending");

        OutboxJobEntity job = new OutboxJobEntity();
        job.setId(UUID.randomUUID());
        job.setMessageId(message.getId());
        job.setJobType("chatapp_send");
        job.setStatus("processing");
        job.setAttemptCount(attempts);
        job.setMaxAttempts(maxAttempts);
        job.setLeaseOwner("worker-1");
        return new Fixture(job, message);
    }

    private record Fixture(OutboxJobEntity job, MessageEntity message) {}

    private static final class TrackingTransactionOperations implements TransactionOperations {
        private boolean active;
        private int executionCount;

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            assertThat(active).isFalse();
            active = true;
            executionCount++;
            try {
                return action.doInTransaction(new SimpleTransactionStatus());
            } finally {
                active = false;
            }
        }

        boolean isActive() {
            return active;
        }

        int executionCount() {
            return executionCount;
        }
    }
}
