package com.crmforlogistics.messagecenter.service.chatapp.outbox;

import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.MessageStatusEventMapper;
import com.crmforlogistics.messagecenter.mapper.OutboxJobMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppAccountResolver;
import com.crmforlogistics.messagecenter.service.conversation.ConversationAccessService;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicActivityRecorder;
import com.crmforlogistics.messagecenter.service.scheduling.AdaptivePollingScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.crmforlogistics.messagecenter.service.message.TemplateMessageTextResolver;

@ExtendWith(MockitoExtension.class)
class MessageSendApplicationServiceTest {

    @Mock MessageMapper messageMapper;
    @Mock ConversationMapper conversationMapper;
    @Mock OutboxJobMapper outboxJobMapper;
    @Mock MessageStatusEventMapper statusEventMapper;
    @Mock TemplateMapper templateMapper;
    @Mock ChannelAccountMapper accountMapper;
    @Mock EventHub eventHub;
    @Mock ConversationAccessService conversationAccessService;
    @Mock AiTopicActivityRecorder topicActivityRecorder;
    @Mock ChatAppAccountResolver accountResolver;
    @Mock AdaptivePollingScheduler pollingScheduler;

    @Test
    void persistedOutboundMessageRecordsTopicActivity() {
        UUID accountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setChannelAccountId(accountId);
        when(conversationAccessService.lockForMessage(conversationId, accountId, actorId))
                .thenReturn(conversation);
        when(messageMapper.findByClientRequestId(accountId, "request-topic"))
                .thenReturn(Optional.empty()).thenReturn(Optional.empty());
        MessageSendApplicationService service = new MessageSendApplicationService(
                messageMapper, outboxJobMapper, statusEventMapper, new ObjectMapper(), eventHub,
                conversationAccessService,
                new TemplateMessageTextResolver(templateMapper, accountMapper, new ObjectMapper()), topicActivityRecorder);

        service.accept(new MessageSendApplicationService.SendMessageCommand(
                accountId, conversationId, "text", "request-topic", Map.of("text", "hello")), actorId);

        verify(topicActivityRecorder).recordConversation(org.mockito.ArgumentMatchers.eq(conversation), any());
    }

    @Test
    void duplicateClientRequestCreatesOnePendingMessageAndOutbox() {
        UUID accountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setChannelAccountId(accountId);
        when(conversationAccessService.lockForMessage(
                conversationId, accountId, actorId)).thenReturn(conversation);

        MessageEntity existing = new MessageEntity();
        existing.setId(UUID.randomUUID());
        existing.setChannelAccountId(accountId);
        existing.setConversationId(conversationId);
        existing.setClientRequestId("request-1");
        existing.setCurrentStatus("pending");
        // 两次 accept：第一次没命中（新建），第二次命中同一条（幂等返回既有消息）。
        // stub 只喂两个答案，多余的第三个只在被调用时才生效 —— 多写一个就会把第二次也变成「没命中」。
        when(messageMapper.findByClientRequestId(accountId, "request-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(messageMapper.insertWithSequence(any())).thenAnswer(invocation -> {
            MessageEntity inserted = invocation.getArgument(0);
            inserted.setId(existing.getId());
            return 1;
        });

        MessageSendApplicationService service = new MessageSendApplicationService(
                messageMapper, outboxJobMapper, statusEventMapper, new ObjectMapper(), eventHub,
                conversationAccessService,
                new TemplateMessageTextResolver(templateMapper, accountMapper, new ObjectMapper()));
        MessageSendApplicationService.SendMessageCommand command =
                new MessageSendApplicationService.SendMessageCommand(
                        accountId,
                        conversationId,
                        "text",
                        "request-1",
                        Map.of("text", "hello"));

        MessageSendApplicationService.MessageAccepted first = service.accept(command, actorId);
        MessageSendApplicationService.MessageAccepted duplicate = service.accept(command, actorId);

        assertThat(first.messageId()).isEqualTo(existing.getId());
        assertThat(first.status()).isEqualTo("pending");
        assertThat(first.duplicate()).isFalse();
        assertThat(duplicate.messageId()).isEqualTo(existing.getId());
        assertThat(duplicate.duplicate()).isTrue();

        ArgumentCaptor<MessageEntity> messageCaptor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageMapper, times(1)).insertWithSequence(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getDirection()).isEqualTo("outbound");
        assertThat(messageCaptor.getValue().getCurrentStatus()).isEqualTo("pending");
        assertThat(messageCaptor.getValue().getMetadataJsonb()).contains("hello");
        verify(outboxJobMapper, times(1)).insertIgnore(any());
        verify(statusEventMapper, times(1)).insertIgnore(any());
    }

    @Test
    void trimsClientRequestIdBeforeIdempotencyLookup() {
        UUID accountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        MessageEntity existing = new MessageEntity();
        existing.setId(UUID.randomUUID());
        existing.setCurrentStatus("submitted");
        when(messageMapper.findByClientRequestId(accountId, "request-1"))
                .thenReturn(Optional.of(existing));
        MessageSendApplicationService service = new MessageSendApplicationService(
                messageMapper, outboxJobMapper, statusEventMapper, new ObjectMapper(), eventHub,
                conversationAccessService,
                new TemplateMessageTextResolver(templateMapper, accountMapper, new ObjectMapper()));
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        when(conversationAccessService.lockForMessage(
                conversationId, accountId, actorId)).thenReturn(conversation);

        var accepted = service.accept(new MessageSendApplicationService.SendMessageCommand(
                accountId, conversationId, "text", "  request-1  ",
                Map.of("text", "hello")), actorId);

        assertThat(accepted.duplicate()).isTrue();
        assertThat(accepted.messageId()).isEqualTo(existing.getId());
        verifyNoInteractions(conversationMapper, outboxJobMapper, statusEventMapper);
    }

    @Test
    void authorizationRunsBeforeIdempotencyLookup() {
        UUID accountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(conversationAccessService.lockForMessage(conversationId, accountId, actorId))
                .thenThrow(new SecurityException("CHATAPP_CONVERSATION_FORBIDDEN"));
        MessageSendApplicationService service = new MessageSendApplicationService(
                messageMapper, outboxJobMapper, statusEventMapper, new ObjectMapper(), eventHub,
                conversationAccessService,
                new TemplateMessageTextResolver(templateMapper, accountMapper, new ObjectMapper()));

        assertThatThrownBy(() -> service.accept(
                new MessageSendApplicationService.SendMessageCommand(
                        accountId, conversationId, "text", "request-1",
                        Map.of("text", "hello")), actorId))
                .isInstanceOf(SecurityException.class)
                .hasMessage("CHATAPP_CONVERSATION_FORBIDDEN");
        verifyNoInteractions(messageMapper, conversationMapper, outboxJobMapper, statusEventMapper);
    }

    @Test
    void outboundMessagePersistsLockedDatabaseAccountVersionInsteadOfClientValue() {
        UUID accountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setChannelAccountId(accountId);
        when(conversationAccessService.lockForMessage(conversationId, accountId, actorId))
                .thenReturn(conversation);
        when(messageMapper.findByClientRequestId(accountId, "request-version"))
                .thenReturn(Optional.empty());
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        account.setOwnerUserId(actorId);
        account.setVersion(12L);
        when(accountResolver.requireOwnedAccountForSend(actorId, accountId, null))
                .thenReturn(account);

        MessageSendApplicationService service = new MessageSendApplicationService(
                messageMapper, outboxJobMapper, statusEventMapper, new ObjectMapper(), eventHub,
                conversationAccessService,
                new TemplateMessageTextResolver(templateMapper, accountMapper, new ObjectMapper()),
                topicActivityRecorder, accountResolver, pollingScheduler);

        service.accept(new MessageSendApplicationService.SendMessageCommand(
                accountId, conversationId, "text", "request-version",
                Map.of("text", "hello"), 999L), actorId);

        ArgumentCaptor<MessageEntity> messageCaptor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageMapper).insertWithSequence(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getChannelAccountVersion()).isEqualTo(12L);
    }

    @Test
    void reassignedAccountIsRejectedBeforeMessageAndOutboxCreation() {
        UUID accountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setChannelAccountId(accountId);
        when(conversationAccessService.lockForMessage(conversationId, accountId, actorId))
                .thenReturn(conversation);
        when(accountResolver.requireOwnedAccountForSend(actorId, accountId, null))
                .thenThrow(new IllegalArgumentException("WHATSAPP_ACCOUNT_REASSIGNED"));

        MessageSendApplicationService service = new MessageSendApplicationService(
                messageMapper, outboxJobMapper, statusEventMapper, new ObjectMapper(), eventHub,
                conversationAccessService,
                new TemplateMessageTextResolver(templateMapper, accountMapper, new ObjectMapper()),
                topicActivityRecorder, accountResolver, pollingScheduler);

        assertThatThrownBy(() -> service.accept(new MessageSendApplicationService.SendMessageCommand(
                accountId, conversationId, "text", "request-reassigned",
                Map.of("text", "hello"), 1L), actorId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("WHATSAPP_ACCOUNT_REASSIGNED");
        verifyNoInteractions(messageMapper, outboxJobMapper, statusEventMapper);
    }

    @Test
    void templateMessageStoresRenderedBodySnapshot() {
        UUID accountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setChannelAccountId(accountId);
        when(conversationAccessService.lockForMessage(conversationId, accountId, actorId))
                .thenReturn(conversation);
        when(messageMapper.findByClientRequestId(accountId, "request-template"))
                .thenReturn(java.util.Optional.empty())
                .thenReturn(java.util.Optional.empty());

        UUID scopeId = UUID.randomUUID();
        TemplateEntity template = new TemplateEntity();
        template.setChannelAccountId(accountId);
        template.setBody("Hello $(customer), your order $(orderNo) is ready.");
        template.setStatus("APPROVED");
        template.setAllowSend(true);
        when(accountMapper.selectById(accountId)).thenReturn(account(accountId, scopeId));
        when(templateMapper.findSharedForSend(scopeId, "order_ready", "en_US"))
                .thenReturn(java.util.Optional.of(template));

        MessageSendApplicationService service = new MessageSendApplicationService(
                messageMapper, outboxJobMapper, statusEventMapper, new ObjectMapper(), eventHub,
                conversationAccessService,
                new TemplateMessageTextResolver(templateMapper, accountMapper, new ObjectMapper()));

        service.accept(new MessageSendApplicationService.SendMessageCommand(
                accountId, conversationId, "template", "request-template",
                Map.of("templateCode", "order_ready", "languageCode", "en_US",
                        "templateParams", Map.of("customer", "Alice", "orderNo", "A-17"))), actorId);

        ArgumentCaptor<MessageEntity> messageCaptor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageMapper).insertWithSequence(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getBodyText())
                .isEqualTo("Hello Alice, your order A-17 is ready.");
    }

    @Test
    void templateMessageIsRejectedWhenApprovedBodyIsUnavailable() {
        UUID accountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setChannelAccountId(accountId);
        when(conversationAccessService.lockForMessage(conversationId, accountId, actorId))
                .thenReturn(conversation);
        when(messageMapper.findByClientRequestId(accountId, "request-missing-template"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        UUID scopeId = UUID.randomUUID();
        when(accountMapper.selectById(accountId)).thenReturn(account(accountId, scopeId));
        when(templateMapper.findSharedForSend(scopeId, "missing_template", "en_US"))
                .thenReturn(Optional.empty());

        MessageSendApplicationService service = new MessageSendApplicationService(
                messageMapper, outboxJobMapper, statusEventMapper, new ObjectMapper(), eventHub,
                conversationAccessService,
                new TemplateMessageTextResolver(templateMapper, accountMapper, new ObjectMapper()));

        assertThatThrownBy(() -> service.accept(
                new MessageSendApplicationService.SendMessageCommand(
                        accountId, conversationId, "template", "request-missing-template",
                        Map.of("templateCode", "missing_template", "languageCode", "en_US",
                                "templateParams", Map.of("customer", "Alice"))),
                actorId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_TEMPLATE_NOT_SYNCED");
        verify(messageMapper, times(0)).insertWithSequence(any());
        verify(outboxJobMapper, times(0)).insertIgnore(any());
    }

    @Test
    void templateMessageIsRejectedBeforeOutboxWhenReturnedTemplateIsSuspended() {
        UUID accountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setChannelAccountId(accountId);
        when(conversationAccessService.lockForMessage(conversationId, accountId, actorId))
                .thenReturn(conversation);
        when(messageMapper.findByClientRequestId(accountId, "request-suspended-template"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        UUID scopeId = UUID.randomUUID();
        TemplateEntity suspended = new TemplateEntity();
        suspended.setChannelAccountId(accountId);
        suspended.setBody("Hello $(customer)");
        suspended.setStatus("SUSPENDED");
        suspended.setAllowSend(true);
        when(accountMapper.selectById(accountId)).thenReturn(account(accountId, scopeId));
        when(templateMapper.findSharedForSend(scopeId, "order_ready", "en_US"))
                .thenReturn(Optional.of(suspended));

        MessageSendApplicationService service = new MessageSendApplicationService(
                messageMapper, outboxJobMapper, statusEventMapper, new ObjectMapper(), eventHub,
                conversationAccessService,
                new TemplateMessageTextResolver(templateMapper, accountMapper, new ObjectMapper()));

        assertThatThrownBy(() -> service.accept(
                new MessageSendApplicationService.SendMessageCommand(
                        accountId, conversationId, "template", "request-suspended-template",
                        Map.of("templateCode", "order_ready", "languageCode", "en_US",
                                "templateParams", Map.of("customer", "Alice"))),
                actorId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_TEMPLATE_NOT_SYNCED");
        verify(messageMapper, times(0)).insertWithSequence(any());
        verify(outboxJobMapper, times(0)).insertIgnore(any());
    }

    private static ChannelAccountEntity account(UUID accountId, UUID scopeId) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setProviderScopeId(scopeId);
        return account;
    }
}
