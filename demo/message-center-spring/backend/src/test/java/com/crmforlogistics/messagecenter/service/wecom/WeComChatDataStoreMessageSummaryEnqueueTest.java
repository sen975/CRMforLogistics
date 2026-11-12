package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataGateway;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataCursorMapper;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WeComChatDataStoreMessageSummaryEnqueueTest {
    @Test
    void newlyStoredLegacyMessageEnqueuesPendingSummaryInSameStoreCall() throws Exception {
        WeComChatDataMessageMapper messages = mock(WeComChatDataMessageMapper.class);
        WeComChatDataCursorMapper cursors = mock(WeComChatDataCursorMapper.class);
        WeComCredentialProtector protector = mock(WeComCredentialProtector.class);
        WeComMessageProjector projector = mock(WeComMessageProjector.class);
        EventHub events = mock(EventHub.class);
        WeComMessageSummaryRepository summaries = mock(WeComMessageSummaryRepository.class);
        when(protector.protectSecretKey("secret")).thenReturn("encrypted");
        when(messages.insertIgnore(any())).thenReturn(1);
        when(projector.project(any())).thenReturn(new WeComMessageProjector.ProjectionResult(true));

        UUID installationId = UUID.randomUUID();
        WeComChatDataStore store = new WeComChatDataStore(messages, cursors, protector, projector, events,
                null, null, null, null, null, null, null, null, summaries);
        store.publishPage(new WeComChatDataStore.SyncKey(installationId.toString(), 1,
                        "program", "ability", "corp"), "next",
                List.of(new WeComChatDataStore.DecryptedMessage(new WeComChatDataGateway.EncryptedMessage(
                        "m-1", new WeComChatDataGateway.Party(1, "employee"),
                        List.of(new WeComChatDataGateway.Party(2, "external")), null,
                        100L, 1, "encrypted-secret", 1), "secret")));

        verify(summaries).enqueueIfAbsent(argThat(command -> command.installationId().equals(installationId)
                && command.msgid().equals("m-1")
                && command.rawRequestJson().contains("\"operation\":\"submit\"")));
    }

    @Test
    void duplicateMessageDoesNotEnqueueSummary() throws Exception {
        WeComChatDataMessageMapper messages = mock(WeComChatDataMessageMapper.class);
        WeComChatDataCursorMapper cursors = mock(WeComChatDataCursorMapper.class);
        WeComCredentialProtector protector = mock(WeComCredentialProtector.class);
        WeComMessageProjector projector = mock(WeComMessageProjector.class);
        EventHub events = mock(EventHub.class);
        WeComMessageSummaryRepository summaries = mock(WeComMessageSummaryRepository.class);
        when(protector.protectSecretKey("secret")).thenReturn("encrypted");
        when(messages.insertIgnore(any())).thenReturn(0);
        when(projector.project(any())).thenReturn(new WeComMessageProjector.ProjectionResult(false));
        WeComChatDataStore store = new WeComChatDataStore(messages, cursors, protector, projector, events,
                null, null, null, null, null, null, null, null, summaries);
        store.publishPage(new WeComChatDataStore.SyncKey(UUID.randomUUID().toString(), 1,
                        "program", "ability", "corp"), "next",
                List.of(new WeComChatDataStore.DecryptedMessage(new WeComChatDataGateway.EncryptedMessage(
                        "m-1", new WeComChatDataGateway.Party(1, "employee"),
                        List.of(new WeComChatDataGateway.Party(2, "external")), null,
                        100L, 1, "encrypted-secret", 1), "secret")));
        verifyNoInteractions(summaries);
    }

    @Test
    void mediaMessageDoesNotEnqueueSummaryJob() throws Exception {
        WeComChatDataMessageMapper messages = mock(WeComChatDataMessageMapper.class);
        WeComChatDataCursorMapper cursors = mock(WeComChatDataCursorMapper.class);
        WeComCredentialProtector protector = mock(WeComCredentialProtector.class);
        WeComMessageProjector projector = mock(WeComMessageProjector.class);
        EventHub events = mock(EventHub.class);
        WeComMessageSummaryRepository summaries = mock(WeComMessageSummaryRepository.class);
        when(protector.protectSecretKey("secret")).thenReturn("encrypted");
        when(messages.insertIgnore(any())).thenReturn(1);
        when(projector.project(any())).thenReturn(new WeComMessageProjector.ProjectionResult(true));
        WeComChatDataStore store = new WeComChatDataStore(messages, cursors, protector, projector, events,
                null, null, null, null, null, null, null, null, summaries);

        store.publishPage(new WeComChatDataStore.SyncKey(UUID.randomUUID().toString(), 1,
                        "program", "ability", "corp"), "next",
                List.of(new WeComChatDataStore.DecryptedMessage(new WeComChatDataGateway.EncryptedMessage(
                        "m-image", new WeComChatDataGateway.Party(1, "employee"),
                        List.of(new WeComChatDataGateway.Party(2, "external")), null,
                        100L, 2, "encrypted-secret", 1), "secret")));

        verifyNoInteractions(summaries);
    }

    @Test
    void mediaDescriptorIsStoredWithMessageReference() throws Exception {
        WeComChatDataMessageMapper messages = mock(WeComChatDataMessageMapper.class);
        WeComChatDataCursorMapper cursors = mock(WeComChatDataCursorMapper.class);
        WeComCredentialProtector protector = mock(WeComCredentialProtector.class);
        WeComMessageProjector projector = mock(WeComMessageProjector.class);
        EventHub events = mock(EventHub.class);
        when(protector.protectSecretKey("secret")).thenReturn("encrypted");
        when(messages.insertIgnore(any())).thenReturn(1);
        when(projector.project(any())).thenReturn(new WeComMessageProjector.ProjectionResult(true));
        WeComChatDataStore store = new WeComChatDataStore(messages, cursors, protector, projector, events,
                null, null, null, null, null, null, null, null, null);

        store.publishPage(new WeComChatDataStore.SyncKey(UUID.randomUUID().toString(), 1,
                        "program", "ability"), "next",
                List.of(new WeComChatDataStore.DecryptedMessage(new WeComChatDataGateway.EncryptedMessage(
                        "m-image", new WeComChatDataGateway.Party(1, "employee"),
                        List.of(new WeComChatDataGateway.Party(2, "external")), null,
                        100L, 2, "encrypted-secret", 1,
                        "{\"kind\":\"image\",\"sdkfileid\":\"sdk-1\"}"), "secret")));

        verify(messages).insertIgnore(argThat(message ->
                "{\"kind\":\"image\",\"sdkfileid\":\"sdk-1\"}".equals(message.getMediaJson())));
    }
}
