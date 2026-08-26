package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataMessageEntity;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataCursorMapper;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataIngestFailureMapper;
import com.crmforlogistics.messagecenter.mapper.WeComPartyMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceConversationMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceParticipantMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComChatDataStoreTest {
    private final WeComChatDataMessageMapper messages = mock(WeComChatDataMessageMapper.class);
    private final WeComChatDataCursorMapper cursors = mock(WeComChatDataCursorMapper.class);
    private final WeComCredentialProtector protector = mock(WeComCredentialProtector.class);
    private final WeComMessageProjector projector = mock(WeComMessageProjector.class);
    private final EventHub events = mock(EventHub.class);
    private final WeComChatDataRetention retention = mock(WeComChatDataRetention.class);
    private final WeComChatDataStore store = new WeComChatDataStore(
            messages, cursors, protector, projector, events, retention);

    @Test
    void publishesReferenceProjectionAndCursorInOneTransaction() {
        when(protector.protectSecretKey("secret")).thenReturn("encrypted");
        when(messages.insertIgnore(any())).thenReturn(1);
        when(projector.project(any())).thenReturn(new WeComMessageProjector.ProjectionResult(true));
        when(retention.enforce()).thenReturn(new WeComChatDataRetention.RetentionResult(0, true));
        TransactionSynchronizationManager.initSynchronization();
        try {
            WeComChatDataStore.PublishResult result = store.publishPage(
                    key(), "next", List.of(decrypted("m1", 1, 2)));

            assertThat(result.stored()).isEqualTo(1);
            InOrder order = inOrder(messages, projector, cursors);
            order.verify(messages).insertIgnore(any());
            order.verify(projector).project(any());
            order.verify(cursors).upsert(anyString(), eq("next"));
            ArgumentCaptor<WeComChatDataMessageEntity> storedReference =
                    ArgumentCaptor.forClass(WeComChatDataMessageEntity.class);
            verify(messages).insertIgnore(storedReference.capture());
            assertThat(storedReference.getValue().getId()).isNotNull();
            verify(events, never()).publish(anyString(), any());

            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(events).publish("message-new", "{}");
            verify(retention).enforce();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void duplicateOnlyPageStillRunsRetentionWithoutPublishingMessageEvent() {
        when(protector.protectSecretKey("secret")).thenReturn("encrypted");
        when(projector.project(any())).thenReturn(new WeComMessageProjector.ProjectionResult(false));
        when(retention.enforce()).thenReturn(new WeComChatDataRetention.RetentionResult(1, true));

        store.publishPage(key(), "next", List.of(decrypted("m1", 1, 2)));

        verify(retention).enforce();
        verify(events, never()).publish(anyString(), any());
    }

    @Test
    void projectionFailurePreventsCursorAdvance() {
        when(protector.protectSecretKey("secret")).thenReturn("encrypted");
        when(messages.insertIgnore(any())).thenReturn(1);
        doThrow(new IllegalStateException("projection failed")).when(projector).project(any());

        assertThatThrownBy(() -> store.publishPage(key(), "next", List.of(decrypted("m1", 1, 2))))
                .isInstanceOf(com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataException.class);
        verify(cursors, never()).upsert(anyString(), anyString());
    }

    @Test
    void recordsMalformedMessageBeforeAdvancingCursor() {
        WeComChatDataIngestFailureMapper failures = mock(WeComChatDataIngestFailureMapper.class);
        when(failures.insert(any(com.crmforlogistics.messagecenter.entity.WeComChatDataIngestFailureEntity.class))).thenReturn(1);
        when(retention.enforce()).thenReturn(new WeComChatDataRetention.RetentionResult(0, true));
        WeComChatDataStore failureAware = new WeComChatDataStore(
                messages, cursors, protector, projector, events, retention, failures);

        WeComChatDataStore.PublishResult result = failureAware.publishPage(
                new WeComChatDataStore.SyncKey("6f5a3e35-8d31-4f0a-9ed8-9f0cc8cc6e8e", 1,
                        "program", "ability"), "next", List.of(
                        new WeComChatDataStore.DecryptedMessage(null, "secret")));

        assertThat(result.failed()).isEqualTo(1);
        verify(failures).insert(any(com.crmforlogistics.messagecenter.entity.WeComChatDataIngestFailureEntity.class));
        verify(cursors).upsert(anyString(), eq("next"));
    }

    @Test
    void doesNotAdvanceCursorWhenFailureFactCannotBeSaved() {
        WeComChatDataIngestFailureMapper failures = mock(WeComChatDataIngestFailureMapper.class);
        when(failures.insert(any(com.crmforlogistics.messagecenter.entity.WeComChatDataIngestFailureEntity.class))).thenReturn(0);
        WeComChatDataStore failureAware = new WeComChatDataStore(
                messages, cursors, protector, projector, events, retention, failures);

        assertThatThrownBy(() -> failureAware.publishPage(
                new WeComChatDataStore.SyncKey("6f5a3e35-8d31-4f0a-9ed8-9f0cc8cc6e8e", 1,
                        "program", "ability"), "next", List.of(
                        new WeComChatDataStore.DecryptedMessage(null, "secret"))))
                .isInstanceOf(com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataException.class)
                .extracting("code").isEqualTo("WECOM_CHATDATA_FAILURE_RECORD_FAILED");
        verify(cursors, never()).upsert(anyString(), anyString());
    }

    @Test
    void routesEmployeeToEmployeeDirectMessageThroughDirectProjector() {
        when(protector.protectSecretKey("secret")).thenReturn("encrypted");
        when(messages.insertIgnore(any())).thenReturn(1);
        when(projector.projectDirect(any())).thenReturn(new WeComMessageProjector.ProjectionResult(true));
        when(retention.enforce()).thenReturn(new WeComChatDataRetention.RetentionResult(0, true));
        WeComPartyMapper parties = mock(WeComPartyMapper.class);
        WeComSourceConversationMapper sourceConversations = mock(WeComSourceConversationMapper.class);
        WeComSourceParticipantMapper participants = mock(WeComSourceParticipantMapper.class);
        java.util.UUID installationId = java.util.UUID.randomUUID();
        java.util.UUID sourceId = java.util.UUID.randomUUID();
        when(sourceConversations.upsertObserved(eq(installationId), anyString(), eq("DIRECT")))
                .thenReturn(sourceId);
        when(parties.upsertObserved(eq(installationId), anyString(), anyString(), anyString()))
                .thenReturn(java.util.UUID.randomUUID());

        WeComChatDataStore directAware = new WeComChatDataStore(
                messages, cursors, protector, projector, events, retention,
                null, new WeComChatDataNormalizer(), parties, sourceConversations, participants);

        directAware.publishPage(new WeComChatDataStore.SyncKey(installationId.toString(), 1,
                "program", "ability", "corp"), "next", List.of(decrypted("employee-direct", 1, 1)));

        verify(projector).projectDirect(any(WeComMessageProjector.WeComProjectedDirectMessage.class));
        verify(projector, never()).project(any(WeComMessageProjector.WeComProjectedMessage.class));
    }


    private static WeComChatDataStore.SyncKey key() {
        return new WeComChatDataStore.SyncKey(java.util.UUID.randomUUID().toString(), 1,
                "program", "ability");
    }

    private static WeComChatDataStore.DecryptedMessage decrypted(
            String messageId, int senderType, int receiverType) {
        return new WeComChatDataStore.DecryptedMessage(new WeComChatDataGateway.EncryptedMessage(
                messageId,
                new WeComChatDataGateway.Party(senderType, senderType == 1 ? "employee" : "external"),
                List.of(new WeComChatDataGateway.Party(
                        receiverType, receiverType == 1 ? "employee" : "external")),
                "", 100L, 1, "encrypted-key", 1), "secret");
    }
}
