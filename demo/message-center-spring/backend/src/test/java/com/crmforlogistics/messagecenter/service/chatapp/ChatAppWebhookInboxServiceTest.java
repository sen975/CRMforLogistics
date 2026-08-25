package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import com.crmforlogistics.messagecenter.entity.ChannelEventEntity;

class ChatAppWebhookInboxServiceTest {
    private final ChatAppWebhookVerifier verifier = mock(ChatAppWebhookVerifier.class);
    private final ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
    private final ChannelEventMapper eventMapper = mock(ChannelEventMapper.class);
    private final ChatAppWebhookProjector projector = mock(ChatAppWebhookProjector.class);
    private final ChatAppWebhookInboxService service = new ChatAppWebhookInboxService(
            verifier, accountMapper, eventMapper, projector, new ObjectMapper());

    @Test
    void rejectsBodyLargerThanOneMebibyteBeforeVerification() {
        String body = "x".repeat(1024 * 1024 + 1);

        assertThatThrownBy(() -> service.accept("signature", "timestamp", body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_WEBHOOK_BODY_SIZE_INVALID");
        verifyNoInteractions(verifier, accountMapper, eventMapper, projector);
    }

    @Test
    void duplicateProviderEventIsAcknowledgedWithoutAnotherProjection() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setAccountIdentifier("8613266259485");
        when(accountMapper.selectActiveChatAppAccounts()).thenReturn(List.of(account));
        when(eventMapper.insertIgnore(any())).thenReturn(0);
        String body = "{\"EventId\":\"event-1\",\"To\":\"8613266259485\","
                + "\"MessageId\":\"wamid-1\",\"Message\":\"hello\"}";

        var receipt = service.accept("signature", "timestamp", body);

        assertThat(receipt.duplicate()).isTrue();
        verify(projector, never()).project(any());
    }

    @Test
    void persistedPayloadDropsFieldsNotNeededByProjector() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setAccountIdentifier("8613266259485");
        when(accountMapper.selectActiveChatAppAccounts()).thenReturn(List.of(account));
        when(eventMapper.insertIgnore(any())).thenReturn(1);
        String body = "{\"EventId\":\"event-1\",\"To\":\"8613266259485\","
                + "\"From\":\"60123456789\",\"MessageId\":\"wamid-1\","
                + "\"Message\":\"hello\",\"unneededSecret\":\"must-not-persist\"}";

        service.accept("signature", "timestamp", body);

        ArgumentCaptor<ChannelEventEntity> event =
                ArgumentCaptor.forClass(ChannelEventEntity.class);
        verify(eventMapper).insertIgnore(event.capture());
        assertThat(event.getValue().getPayloadJsonb())
                .contains("wamid-1")
                .contains("hello")
                .doesNotContain("unneededSecret")
                .doesNotContain("must-not-persist");
    }

    @Test
    void statusCallbackMatchesBusinessNumberFromSender() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setAccountIdentifier("8613266259485");
        when(accountMapper.selectActiveChatAppAccounts()).thenReturn(List.of(account));
        when(eventMapper.insertIgnore(any())).thenReturn(1);
        String body = "{\"Status\":\"Read\",\"MessageId\":\"wamid-status-1\","
                + "\"From\":\"8613266259485\",\"To\":\"60123456789\"}";

        service.accept("signature", "timestamp", body);

        ArgumentCaptor<ChannelEventEntity> event =
                ArgumentCaptor.forClass(ChannelEventEntity.class);
        verify(eventMapper).insertIgnore(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("chatapp_status");
        assertThat(event.getValue().getPayloadJsonb())
                .contains("wamid-status-1")
                .contains("\"Status\":\"Read\"");
        verify(projector).project(event.getValue());
    }
}
