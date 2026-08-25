package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastJobEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastRecipientEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastReconciliationEvidenceEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastJobMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastRecipientMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastReconciliationEvidenceMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppAccountResolver;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway.BroadcastQuery;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway.ReconciliationItem;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway.ReconciliationPage;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastGateway.SubmissionResult;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus.DELIVERED;
import static com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus.FAILED_RECIPIENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAppBroadcastWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-14T07:00:00Z");

    @Mock ChatAppBroadcastJobMapper jobMapper;
    @Mock ChatAppBroadcastMapper broadcastMapper;
    @Mock ChatAppBroadcastRecipientMapper recipientMapper;
    @Mock ChatAppAccountResolver accountResolver;
    @Mock ChatAppBroadcastGateway gateway;
    @Mock EventHub eventHub;
    @Mock ChatAppBroadcastMessageProjector messageProjector;
    @Mock ChatAppBroadcastReconciliationEvidenceMapper evidenceMapper;

    private ChatAppBroadcastWorker worker;

    @BeforeEach
    void setUp() {
        lenient().when(jobMapper.assertLeaseOwned(any(), any())).thenReturn(1);
        worker = new ChatAppBroadcastWorker(
                jobMapper, broadcastMapper, recipientMapper, accountResolver,
                gateway, eventHub, messageProjector, evidenceMapper,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                TransactionOperations.withoutTransaction());
    }

    @Test
    void submissionPersistsGroupBeforeProjectingAndNeverResubmitsAfterProjectionFailure() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "SUBMIT");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "QUEUED");
        broadcast.setRecipientCount(1);
        ChatAppBroadcastRecipientEntity recipient = recipient(broadcastId, "60111111111", "QUEUED");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(recipientMapper.findByBroadcastId(broadcastId)).thenReturn(List.of(recipient));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(gateway.submit(any())).thenReturn(new SubmissionResult("group-1", "request-1", "OK"));
        when(jobMapper.completeIfLeased(job.getId(), job.getLeaseId(), NOW)).thenReturn(1);
        when(recipientMapper.findWithoutMessage(broadcastId, 1000)).thenReturn(List.of(recipient));
        doThrow(new IllegalStateException("local projection failed"))
                .when(messageProjector).ensureProcessing(broadcastId, recipient.getId());

        worker.runAvailable("worker-1", 10);

        verify(broadcastMapper).markSubmitted(broadcastId, "group-1", "request-1", "OK", NOW);
        verify(gateway, times(1)).submit(any());
        verify(jobMapper).insert(argThat((ChatAppBroadcastJobEntity value) ->
                "RECONCILE".equals(value.getJobType())));
        verify(messageProjector).ensureProcessing(broadcastId, recipient.getId());
    }

    @Test
    void reconciliationProjectsEveryProviderRecipient() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "RECONCILE");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "SUBMITTED");
        broadcast.setProviderGroupMessageId("group-1");
        broadcast.setRecipientCount(3);
        ChatAppBroadcastRecipientEntity first = recipient(broadcastId, "60111111111", "PROCESSING");
        ChatAppBroadcastRecipientEntity second = recipient(broadcastId, "60122222222", "PROCESSING");
        ChatAppBroadcastRecipientEntity third = recipient(broadcastId, "60199999999", "PROCESSING");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(recipientMapper.findWithoutMessage(broadcastId, 1000)).thenReturn(List.of(first, second, third));
        when(gateway.reconcile(any())).thenReturn(new ReconciliationPage(List.of(
                new ReconciliationItem(1, first.getRecipientNumberSnapshot(), "wamid-1", "unique-1",
                        DELIVERED, "DELIVERED", "", NOW, ""),
                new ReconciliationItem(2, second.getRecipientNumberSnapshot(), "wamid-2", "unique-2",
                        DELIVERED, "DELIVERED", "", NOW, ""),
                new ReconciliationItem(3, third.getRecipientNumberSnapshot(), "wamid-3", "unique-3",
                        FAILED_RECIPIENT, "FAILED", "cannot send to self", NOW, "")),
                1, false, "request-2"));
        when(recipientMapper.findAllByNumber(broadcastId, first.getRecipientNumberSnapshot())).thenReturn(List.of(first));
        when(recipientMapper.findAllByNumber(broadcastId, second.getRecipientNumberSnapshot())).thenReturn(List.of(second));
        when(recipientMapper.findAllByNumber(broadcastId, third.getRecipientNumberSnapshot())).thenReturn(List.of(third));
        when(recipientMapper.findByBroadcastId(broadcastId)).thenReturn(List.of(
                recipient(broadcastId, first.getRecipientNumberSnapshot(), "DELIVERED"),
                recipient(broadcastId, second.getRecipientNumberSnapshot(), "DELIVERED"),
                recipient(broadcastId, third.getRecipientNumberSnapshot(), "FAILED_RECIPIENT")));
        when(jobMapper.assertLeaseOwned(job.getId(), job.getLeaseId())).thenReturn(1);
        when(jobMapper.completeIfLeased(job.getId(), job.getLeaseId(), NOW)).thenReturn(1);

        worker.runAvailable("worker-1", 10);

        verify(messageProjector, times(3)).applyReconciliation(eq(broadcastId), any(), any(), eq(NOW));
        verify(evidenceMapper, times(4)).upsert(any(ChatAppBroadcastReconciliationEvidenceEntity.class));
        verify(broadcastMapper).updateAggregate(
                broadcastId, "PARTIALLY_FAILED", 2, 1, 0, NOW, null, null, NOW);
    }

    @Test
    void unmatchedProviderRowKeepsMatchedEvidenceAndMarksStatusUnknown() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "RECONCILE");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "SUBMITTED");
        broadcast.setProviderGroupMessageId("group-1");
        ChatAppBroadcastRecipientEntity matched = recipient(broadcastId, "60111111111", "PROCESSING");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(gateway.reconcile(any())).thenReturn(new ReconciliationPage(List.of(
                new ReconciliationItem(1, "60111111111", "wamid-1", "unique-1",
                        DELIVERED, "DELIVERED", "", NOW, ""),
                new ReconciliationItem(2, "", "wamid-2", "unique-2",
                        FAILED_RECIPIENT, "FAILED", "recipient missing", NOW,
                        "CHATAPP_BROADCAST_RECIPIENT_NUMBER_MISSING")),
                1, false, "request-2"));
        when(recipientMapper.findAllByNumber(broadcastId, "60111111111"))
                .thenReturn(List.of(matched));
        when(recipientMapper.findByBroadcastId(broadcastId)).thenReturn(List.of(
                recipient(broadcastId, "60111111111", "DELIVERED"),
                recipient(broadcastId, "60122222222", "PROCESSING")));
        when(jobMapper.assertLeaseOwned(job.getId(), job.getLeaseId())).thenReturn(1);
        when(jobMapper.failIfLeased(
                job.getId(), job.getLeaseId(), "DEAD", NOW,
                "CHATAPP_BROADCAST_RECONCILIATION_UNMATCHED_RECIPIENT",
                "CHATAPP_BROADCAST_RECONCILIATION_UNMATCHED_RECIPIENT", NOW)).thenReturn(1);

        worker.runAvailable("worker-1", 10);

        verify(messageProjector).applyReconciliation(broadcastId, matched.getId(),
                new ReconciliationItem(1, "60111111111", "wamid-1", "unique-1",
                        DELIVERED, "DELIVERED", "", NOW, ""), NOW);
        verify(evidenceMapper, times(3)).upsert(any(ChatAppBroadcastReconciliationEvidenceEntity.class));
        verify(broadcastMapper).updateAggregate(
                broadcastId, "STATUS_UNKNOWN", 1, 0, 1, NOW,
                "CHATAPP_BROADCAST_RECONCILIATION_UNMATCHED_RECIPIENT",
                "CHATAPP_BROADCAST_RECONCILIATION_UNMATCHED_RECIPIENT", NOW);
    }

    @Test
    void missingNumberUsesUniqueProviderMessageBindingAndKeepsStatusFact() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "RECONCILE");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "SUBMITTED");
        broadcast.setProviderGroupMessageId("group-1");
        ChatAppBroadcastRecipientEntity matched = recipient(
                broadcastId, "60111111111", "PROCESSING");
        matched.setProviderMessageId("wamid-1");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        ReconciliationItem item = new ReconciliationItem(
                1, "", "wamid-1", "unique-1", DELIVERED, "DELIVERED", "", NOW,
                "CHATAPP_BROADCAST_RECIPIENT_NUMBER_MISSING");
        when(gateway.reconcile(any())).thenReturn(new ReconciliationPage(
                List.of(item), 1, false, "request-2"));
        when(recipientMapper.findAllByProviderMessageId(broadcastId, "wamid-1", 2))
                .thenReturn(List.of(matched));
        when(recipientMapper.findByBroadcastId(broadcastId)).thenReturn(List.of(
                recipient(broadcastId, "60111111111", "DELIVERED")));
        when(jobMapper.completeIfLeased(job.getId(), job.getLeaseId(), NOW)).thenReturn(1);

        worker.runAvailable("worker-1", 10);

        verify(messageProjector).applyReconciliation(broadcastId, matched.getId(), item, NOW);
        verify(broadcastMapper).updateAggregate(
                broadcastId, "SUCCEEDED", 1, 0, 0, NOW, null, null, NOW);
    }

    @Test
    void successfulPageRefreshesLatestProviderDiagnostic() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "RECONCILE");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "SUBMITTED");
        broadcast.setProviderGroupMessageId("group-1");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        ChatAppBroadcastRecipientEntity matched = recipient(
                broadcastId, "60111111111", "PROCESSING");
        ReconciliationItem item = new ReconciliationItem(
                1, "60111111111", "wamid-1", "unique-1", DELIVERED,
                "DELIVERED", "", NOW, "");
        when(gateway.reconcile(any())).thenReturn(new ReconciliationPage(
                List.of(item), 1, false,
                new ChatAppBroadcastGateway.ProviderDiagnostic("OK", "", "request-fresh", "")));
        when(recipientMapper.findAllByNumber(broadcastId, "60111111111"))
                .thenReturn(List.of(matched));
        when(recipientMapper.findByBroadcastId(broadcastId)).thenReturn(List.of(
                recipient(broadcastId, "60111111111", "DELIVERED")));
        when(jobMapper.completeIfLeased(job.getId(), job.getLeaseId(), NOW)).thenReturn(1);

        worker.runAvailable("worker-1", 10);

        verify(broadcastMapper).updateReconciliationDiagnostic(
                broadcastId, "request-fresh", "OK", "", "", NOW);
    }

    @Test
    void providerFailurePersistsDiagnosticWithoutMutatingRecipientsOrMessages() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "RECONCILE");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "SUBMITTED");
        broadcast.setProviderGroupMessageId("group-1");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(jobMapper.assertLeaseOwned(job.getId(), job.getLeaseId())).thenReturn(1);
        when(gateway.reconcile(any())).thenThrow(new ChatAppBroadcastException(
                "CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE", HttpStatus.BAD_GATEWAY,
                false, true, "Throttling", "request-throttle", "rate limited", null));
        when(jobMapper.failIfLeased(
                job.getId(), job.getLeaseId(), "FAILED", NOW.plusSeconds(10),
                "CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE",
                "CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE", NOW)).thenReturn(1);

        worker.runAvailable("worker-1", 10);

        ArgumentCaptor<ChatAppBroadcastReconciliationEvidenceEntity> evidence =
                ArgumentCaptor.forClass(ChatAppBroadcastReconciliationEvidenceEntity.class);
        verify(evidenceMapper).upsert(evidence.capture());
        assertThat(evidence.getValue().getRowNumber()).isZero();
        assertThat(evidence.getValue().getProviderRequestId()).isEqualTo("request-throttle");
        assertThat(evidence.getValue().getProviderStatus()).isEqualTo("Throttling");
        assertThat(evidence.getValue().getFailureReason()).isEqualTo("rate limited");
        verify(broadcastMapper).updateReconciliationDiagnostic(
                broadcastId, "request-throttle", "Throttling",
                "CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE", "rate limited", NOW);
        verify(eventHub).publish(eq("broadcast-updated"), any());
        verify(messageProjector, never()).applyReconciliation(any(), any(), any(), any());
        verify(recipientMapper, never()).updateProviderStatus(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void nonRetryableProviderFailureImmediatelyMarksReconciliationUnknownWithKnownCounts() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "RECONCILE");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "SUBMITTED");
        broadcast.setProviderGroupMessageId("group-1");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(recipientMapper.findByBroadcastId(broadcastId)).thenReturn(List.of(
                recipient(broadcastId, "60111111111", "DELIVERED"),
                recipient(broadcastId, "60122222222", "FAILED_RECIPIENT")));
        when(jobMapper.assertLeaseOwned(job.getId(), job.getLeaseId())).thenReturn(1);
        when(gateway.reconcile(any())).thenThrow(new ChatAppBroadcastException(
                "CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE", HttpStatus.BAD_GATEWAY,
                false, false, "QueryParam.startTime", "request-400",
                "Query start time not allowed to be empty", null));
        when(jobMapper.failIfLeased(
                job.getId(), job.getLeaseId(), "DEAD", NOW,
                "CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE",
                "CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE", NOW)).thenReturn(1);

        worker.runAvailable("worker-1", 10);

        verify(jobMapper).failIfLeased(
                job.getId(), job.getLeaseId(), "DEAD", NOW,
                "CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE",
                "CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE", NOW);
        verify(broadcastMapper).updateAggregate(
                broadcastId, "STATUS_UNKNOWN", 1, 1, 0, NOW,
                "CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE",
                "CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE", NOW);
        verify(jobMapper, never()).failIfLeased(
                job.getId(), job.getLeaseId(), "FAILED", NOW.plusSeconds(10),
                "CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE",
                "CHATAPP_BROADCAST_RECONCILIATION_UNAVAILABLE", NOW);
    }

    @Test
    void reconciliationBackfillsProcessingMessageBeforeProviderRead() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "RECONCILE");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "SUBMITTED");
        broadcast.setProviderGroupMessageId("group-1");
        ChatAppBroadcastRecipientEntity recipient = recipient(broadcastId, "60111111111", "PROCESSING");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(recipientMapper.findWithoutMessage(broadcastId, 1000)).thenReturn(List.of(recipient));
        when(jobMapper.assertLeaseOwned(job.getId(), job.getLeaseId())).thenReturn(1);
        when(gateway.reconcile(any())).thenReturn(new ReconciliationPage(List.of(), 1, false, "request-2"));
        when(recipientMapper.findByBroadcastId(broadcastId)).thenReturn(List.of(recipient));
        when(jobMapper.failIfLeased(
                job.getId(), job.getLeaseId(), "FAILED", NOW.plusSeconds(10),
                "CHATAPP_BROADCAST_RECONCILIATION_PENDING",
                "CHATAPP_BROADCAST_RECONCILIATION_PENDING", NOW)).thenReturn(1);

        worker.runAvailable("worker-1", 10);

        var ordered = inOrder(messageProjector, gateway);
        ordered.verify(messageProjector).ensureProcessing(broadcastId, recipient.getId());
        ArgumentCaptor<BroadcastQuery> query = ArgumentCaptor.forClass(BroadcastQuery.class);
        ordered.verify(gateway).reconcile(query.capture());
        assertThat(query.getValue().startTime()).isEqualTo(NOW.minusSeconds(360));
        assertThat(query.getValue().endTime()).isEqualTo(NOW);
    }

    @Test
    void projectionFailureBeforeReconciliationReadSchedulesRetryWithoutCallingProvider() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "RECONCILE");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "SUBMITTED");
        broadcast.setProviderGroupMessageId("group-1");
        ChatAppBroadcastRecipientEntity recipient = recipient(broadcastId, "60111111111", "PROCESSING");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(recipientMapper.findWithoutMessage(broadcastId, 1000)).thenReturn(List.of(recipient));
        doThrow(new IllegalStateException("local projection failed"))
                .when(messageProjector).ensureProcessing(broadcastId, recipient.getId());
        when(jobMapper.assertLeaseOwned(job.getId(), job.getLeaseId())).thenReturn(1);
        when(jobMapper.failIfLeased(
                job.getId(), job.getLeaseId(), "FAILED", NOW.plusSeconds(10),
                "CHATAPP_BROADCAST_MESSAGE_PROJECTION_FAILED",
                "CHATAPP_BROADCAST_MESSAGE_PROJECTION_FAILED", NOW)).thenReturn(1);

        worker.runAvailable("worker-1", 10);

        verify(gateway, never()).reconcile(any());
        verify(jobMapper).failIfLeased(
                job.getId(), job.getLeaseId(), "FAILED", NOW.plusSeconds(10),
                "CHATAPP_BROADCAST_MESSAGE_PROJECTION_FAILED",
                "CHATAPP_BROADCAST_MESSAGE_PROJECTION_FAILED", NOW);
    }

    @Test
    void ambiguousProviderNumberIsEvidenceConflictAndDoesNotProjectEitherRecipient() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "RECONCILE");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "SUBMITTED");
        broadcast.setProviderGroupMessageId("group-1");
        ChatAppBroadcastRecipientEntity first = recipient(broadcastId, "60111111111", "PROCESSING");
        ChatAppBroadcastRecipientEntity second = recipient(broadcastId, "60111111111", "PROCESSING");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(gateway.reconcile(any())).thenReturn(new ReconciliationPage(List.of(
                new ReconciliationItem(1, "60111111111", "wamid-1", "unique-1",
                        DELIVERED, "DELIVERED", "", NOW, "")), 1, false, "request-2"));
        when(recipientMapper.findAllByNumber(broadcastId, "60111111111"))
                .thenReturn(List.of(first, second));
        when(jobMapper.assertLeaseOwned(job.getId(), job.getLeaseId())).thenReturn(1);
        when(jobMapper.failIfLeased(
                job.getId(), job.getLeaseId(), "DEAD", NOW,
                "CHATAPP_BROADCAST_RECONCILIATION_UNMATCHED_RECIPIENT",
                "CHATAPP_BROADCAST_RECONCILIATION_UNMATCHED_RECIPIENT", NOW)).thenReturn(1);

        worker.runAvailable("worker-1", 10);

        verify(messageProjector, never()).applyReconciliation(any(), any(), any(), any());
        ArgumentCaptor<ChatAppBroadcastReconciliationEvidenceEntity> evidence =
                ArgumentCaptor.forClass(ChatAppBroadcastReconciliationEvidenceEntity.class);
        verify(evidenceMapper, times(2)).upsert(evidence.capture());
        assertThat(evidence.getAllValues().get(1).getDiagnosticCode())
                .isEqualTo("CHATAPP_BROADCAST_RECIPIENT_AMBIGUOUS");
    }

    @Test
    void submissionLeaseLossStopsProviderWrite() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "SUBMIT");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "QUEUED");
        broadcast.setRecipientCount(1);
        ChatAppBroadcastRecipientEntity recipient = recipient(broadcastId, "60111111111", "QUEUED");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(recipientMapper.findByBroadcastId(broadcastId)).thenReturn(List.of(recipient));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(jobMapper.assertLeaseOwned(job.getId(), job.getLeaseId())).thenReturn(0);

        worker.runAvailable("worker-1", 10);

        verify(gateway, never()).submit(any());
        verify(broadcastMapper, never()).updateStatus(any(), any(), any());
    }

    @Test
    void successfulSubmissionPersistsGroupIdAndCreatesReconciliationJob() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "SUBMIT");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "QUEUED");
        broadcast.setRecipientCount(1);
        broadcast.setProcessingCount(1);
        ChatAppBroadcastRecipientEntity recipient = recipient(broadcastId, "60111111111", "QUEUED");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(recipientMapper.findByBroadcastId(broadcastId)).thenReturn(List.of(recipient));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(gateway.submit(any())).thenReturn(new SubmissionResult("group-1", "request-1", "OK"));
        when(jobMapper.completeIfLeased(job.getId(), job.getLeaseId(), NOW)).thenReturn(1);

        assertThat(worker.runAvailable("worker-1", 10)).isEqualTo(1);

        verify(broadcastMapper).markSubmitted(
                broadcastId, "group-1", "request-1", "OK", NOW);
        verify(recipientMapper).updateAllStatuses(broadcastId, "PROCESSING", NOW);
        ArgumentCaptor<ChatAppBroadcastJobEntity> reconcileJob =
                ArgumentCaptor.forClass(ChatAppBroadcastJobEntity.class);
        verify(jobMapper).insert(reconcileJob.capture());
        assertThat(reconcileJob.getValue().getJobType()).isEqualTo("RECONCILE");
        assertThat(reconcileJob.getValue().getMaxAttempts()).isEqualTo(10);
    }

    @Test
    void unknownSubmissionIsNeverAutomaticallyRetried() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "SUBMIT");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "QUEUED");
        broadcast.setRecipientCount(1);
        broadcast.setProcessingCount(1);
        ChatAppBroadcastRecipientEntity recipient = recipient(broadcastId, "60111111111", "QUEUED");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(recipientMapper.findByBroadcastId(broadcastId)).thenReturn(List.of(recipient));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(gateway.submit(any())).thenThrow(new ChatAppBroadcastException(
                "CHATAPP_BROADCAST_SUBMISSION_UNKNOWN", HttpStatus.GATEWAY_TIMEOUT,
                true, false, null));
        when(jobMapper.failIfLeased(
                job.getId(), job.getLeaseId(), "DEAD", NOW,
                "CHATAPP_BROADCAST_SUBMISSION_UNKNOWN",
                "CHATAPP_BROADCAST_SUBMISSION_UNKNOWN", NOW)).thenReturn(1);

        worker.runAvailable("worker-1", 10);

        verify(broadcastMapper).markError(
                broadcastId, "SUBMISSION_UNKNOWN", "CHATAPP_BROADCAST_SUBMISSION_UNKNOWN",
                "CHATAPP_BROADCAST_SUBMISSION_UNKNOWN", NOW);
        verify(jobMapper).failIfLeased(
                job.getId(), job.getLeaseId(), "DEAD", NOW,
                "CHATAPP_BROADCAST_SUBMISSION_UNKNOWN",
                "CHATAPP_BROADCAST_SUBMISSION_UNKNOWN", NOW);
        verify(jobMapper, never()).insert(any(ChatAppBroadcastJobEntity.class));
    }

    @Test
    void explicitProviderRejectionFailsBroadcastWithoutRetry() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "SUBMIT");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "QUEUED");
        broadcast.setRecipientCount(1);
        broadcast.setProcessingCount(1);
        ChatAppBroadcastRecipientEntity recipient = recipient(broadcastId, "60111111111", "QUEUED");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(recipientMapper.findByBroadcastId(broadcastId)).thenReturn(List.of(recipient));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(gateway.submit(any())).thenThrow(new ChatAppBroadcastException(
                "CHATAPP_BROADCAST_PROVIDER_REJECTED", HttpStatus.BAD_GATEWAY,
                false, false, null));
        when(jobMapper.failIfLeased(
                job.getId(), job.getLeaseId(), "DEAD", NOW,
                "CHATAPP_BROADCAST_PROVIDER_REJECTED",
                "CHATAPP_BROADCAST_PROVIDER_REJECTED", NOW)).thenReturn(1);

        worker.runAvailable("worker-1", 10);

        verify(broadcastMapper).updateAggregate(
                broadcastId, "FAILED", 0, 1, 0, NOW,
                "CHATAPP_BROADCAST_PROVIDER_REJECTED",
                "CHATAPP_BROADCAST_PROVIDER_REJECTED", NOW);
        verify(recipientMapper).updateAllStatuses(broadcastId, "FAILED_RECIPIENT", NOW);
        verify(jobMapper, never()).insert(any(ChatAppBroadcastJobEntity.class));
    }

    @Test
    void staleSubmissionLeaseCannotPersistProviderResult() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "SUBMIT");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "QUEUED");
        broadcast.setRecipientCount(1);
        broadcast.setProcessingCount(1);
        ChatAppBroadcastRecipientEntity recipient = recipient(broadcastId, "60111111111", "QUEUED");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(recipientMapper.findByBroadcastId(broadcastId)).thenReturn(List.of(recipient));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(gateway.submit(any())).thenReturn(new SubmissionResult("group-1", "request-1", "OK"));
        when(jobMapper.completeIfLeased(job.getId(), job.getLeaseId(), NOW)).thenReturn(0);

        worker.runAvailable("worker-1", 10);

        verify(broadcastMapper, never()).markSubmitted(any(), any(), any(), any(), any());
        verify(recipientMapper, never()).updateAllStatuses(any(), any(), any());
        verify(jobMapper, never()).insert(any(ChatAppBroadcastJobEntity.class));
        verify(eventHub, never()).publish(eq("broadcast-updated"), any());
    }

    @Test
    void reconciliationComputesPartialFailureFromProviderEvidence() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "RECONCILE");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "SUBMITTED");
        broadcast.setProviderGroupMessageId("group-1");
        ChatAppBroadcastRecipientEntity first = recipient(broadcastId, "60111111111", "PROCESSING");
        ChatAppBroadcastRecipientEntity second = recipient(broadcastId, "60122222222", "PROCESSING");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(recipientMapper.findByBroadcastId(broadcastId)).thenReturn(List.of(
                recipient(broadcastId, "60111111111", "DELIVERED"),
                recipient(broadcastId, "60122222222", "FAILED_RECIPIENT")));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(gateway.reconcile(any())).thenReturn(new ReconciliationPage(List.of(
                new ReconciliationItem("60111111111", "wamid-1", "unique-1", DELIVERED, "", NOW),
                new ReconciliationItem("60122222222", "wamid-2", "unique-2", FAILED_RECIPIENT,
                        "blocked", NOW)), 1, false, "request-2"));
        when(recipientMapper.findAllByNumber(broadcastId, "60111111111")).thenReturn(List.of(first));
        when(recipientMapper.findAllByNumber(broadcastId, "60122222222")).thenReturn(List.of(second));
        when(jobMapper.assertLeaseOwned(job.getId(), job.getLeaseId())).thenReturn(1);
        when(jobMapper.completeIfLeased(job.getId(), job.getLeaseId(), NOW)).thenReturn(1);

        worker.runAvailable("worker-1", 10);

        verify(broadcastMapper).updateAggregate(
                broadcastId, "PARTIALLY_FAILED", 1, 1, 0, NOW, null, null, NOW);
        verify(jobMapper).completeIfLeased(job.getId(), job.getLeaseId(), NOW);
        verify(eventHub).publish(eq("broadcast-updated"), any());
    }

    @Test
    void staleReconciliationLeaseCannotMutateRecipientOrAggregateState() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "RECONCILE");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "SUBMITTED");
        broadcast.setProviderGroupMessageId("group-1");
        ChatAppBroadcastRecipientEntity recipient =
                recipient(broadcastId, "60111111111", "PROCESSING");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(gateway.reconcile(any())).thenReturn(new ReconciliationPage(List.of(
                new ReconciliationItem("60111111111", "wamid-1", "unique-1", DELIVERED, "", NOW)),
                1, false, "request-2"));
        when(jobMapper.assertLeaseOwned(job.getId(), job.getLeaseId())).thenReturn(1, 0);

        worker.runAvailable("worker-1", 10);

        verify(recipientMapper, never()).updateProviderStatus(any(), any(), any(), any(), any(), any(), any());
        verify(broadcastMapper, never()).updateAggregate(any(), any(), anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any());
        verify(jobMapper, never()).completeIfLeased(any(), any(), any());
        verify(eventHub, never()).publish(eq("broadcast-updated"), any());
    }

    @Test
    void reconciliationPageLimitMarksStatusUnknown() {
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastJobEntity job = job(broadcastId, "RECONCILE");
        ChatAppBroadcastEntity broadcast = broadcast(broadcastId, "SUBMITTED");
        broadcast.setProviderGroupMessageId("group-1");
        when(jobMapper.claimDue(eq("worker-1"), eq(NOW), any(), eq(10))).thenReturn(List.of(job));
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(accountResolver.requireCurrentAccount(broadcast.getChannelAccountId()))
                .thenReturn(account(broadcast.getChannelAccountId()));
        when(jobMapper.assertLeaseOwned(job.getId(), job.getLeaseId())).thenReturn(1);
        when(gateway.reconcile(any())).thenReturn(
                new ReconciliationPage(List.of(), 1, true, "request-2"));
        when(jobMapper.failIfLeased(
                job.getId(), job.getLeaseId(), "DEAD", NOW,
                "CHATAPP_BROADCAST_RECONCILIATION_PAGE_LIMIT",
                "CHATAPP_BROADCAST_RECONCILIATION_PAGE_LIMIT", NOW)).thenReturn(1);

        worker.runAvailable("worker-1", 10);

        verify(gateway, times(20)).reconcile(any());
        verify(broadcastMapper).updateAggregate(
                broadcastId, "STATUS_UNKNOWN", 0, 0, 2, NOW,
                "CHATAPP_BROADCAST_RECONCILIATION_PAGE_LIMIT",
                "CHATAPP_BROADCAST_RECONCILIATION_PAGE_LIMIT", NOW);
    }

    @Test
    void expiredSubmissionLeaseBecomesUnknownWithoutBlindRetry() {
        UUID unresolvedId = UUID.randomUUID();
        UUID alreadyResolvedId = UUID.randomUUID();
        when(jobMapper.recoverExpiredSubmissions(NOW))
                .thenReturn(List.of(unresolvedId, alreadyResolvedId));
        when(broadcastMapper.markSubmissionUnknownIfUnresolved(unresolvedId, NOW)).thenReturn(1);
        when(broadcastMapper.markSubmissionUnknownIfUnresolved(alreadyResolvedId, NOW)).thenReturn(0);

        assertThat(worker.recoverExpiredSubmissions()).isEqualTo(2);

        verify(eventHub).publish(eq("broadcast-updated"),
                eq(java.util.Map.of("broadcastId", unresolvedId.toString())));
        verify(eventHub, never()).publish(eq("broadcast-updated"),
                eq(java.util.Map.of("broadcastId", alreadyResolvedId.toString())));
    }

    private static ChatAppBroadcastJobEntity job(UUID broadcastId, String type) {
        ChatAppBroadcastJobEntity job = new ChatAppBroadcastJobEntity();
        job.setId(UUID.randomUUID());
        job.setBroadcastId(broadcastId);
        job.setJobType(type);
        job.setStatus("PROCESSING");
        job.setAttemptCount(0);
        job.setMaxAttempts("SUBMIT".equals(type) ? 1 : 10);
        job.setLeaseId("lease-1");
        job.setLeaseWorkerId("worker-1");
        job.setLeaseExpiresAt(NOW.plusSeconds(60));
        return job;
    }

    private static ChatAppBroadcastEntity broadcast(UUID id, String status) {
        ChatAppBroadcastEntity broadcast = new ChatAppBroadcastEntity();
        broadcast.setId(id);
        broadcast.setChannelAccountId(UUID.randomUUID());
        broadcast.setName("August notice");
        broadcast.setTemplateCode("shipping_notice");
        broadcast.setTemplateName("Shipping Notice");
        broadcast.setLanguageCode("zh_CN");
        broadcast.setRecipientCount(2);
        broadcast.setSuccessCount(0);
        broadcast.setFailedCount(0);
        broadcast.setProcessingCount(2);
        broadcast.setStatus(status);
        broadcast.setSubmittedAt(NOW.minusSeconds(60));
        return broadcast;
    }

    private static ChatAppBroadcastRecipientEntity recipient(
            UUID broadcastId, String number, String status) {
        ChatAppBroadcastRecipientEntity recipient = new ChatAppBroadcastRecipientEntity();
        recipient.setId(UUID.randomUUID());
        recipient.setBroadcastId(broadcastId);
        recipient.setContactId(UUID.randomUUID());
        recipient.setContactIdentityId(UUID.randomUUID());
        recipient.setRecipientNameSnapshot("Recipient");
        recipient.setRecipientNumberSnapshot(number);
        recipient.setTemplateParamsJsonb("{\"order\":\"SO-1\"}");
        recipient.setStatus(status);
        return recipient;
    }

    private static ChannelAccountEntity account(UUID id) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(id);
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        account.setAccountIdentifier("60199999999");
        return account;
    }
}
