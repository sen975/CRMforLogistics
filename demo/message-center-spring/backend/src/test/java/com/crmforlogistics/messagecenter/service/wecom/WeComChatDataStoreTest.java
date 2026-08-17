package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataGateway;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataCursorMapper;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
    private final WeComChatDataStore store = new WeComChatDataStore(
            messages, cursors, protector, projector, events);

    @Test
    void publishesReferenceProjectionAndCursorInOneTransaction() {
        when(protector.protectSecretKey("secret")).thenReturn("encrypted");
        when(messages.insertIgnore(any())).thenReturn(1);
        when(projector.project(any())).thenReturn(new WeComMessageProjector.ProjectionResult(true));
        TransactionSynchronizationManager.initSynchronization();
        try {
            WeComChatDataStore.PublishResult result = store.publishPage(
                    key(), "next", List.of(decrypted("m1", 1, 2)));

            assertThat(result.stored()).isEqualTo(1);
            InOrder order = inOrder(messages, projector, cursors);
            order.verify(messages).insertIgnore(any());
            order.verify(projector).project(any());
            order.verify(cursors).upsert(anyString(), eq("next"));
            verify(events, never()).publish(anyString(), any());

            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(events).publish("message-new", "{}");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
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

    private static WeComChatDataStore.SyncKey key() {
        return new WeComChatDataStore.SyncKey("installation", 1, "program", "ability");
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
