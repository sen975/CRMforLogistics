package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.service.chatapp.PeerReconciliationModels.PeerReconciliationCommand;
import com.crmforlogistics.messagecenter.service.chatapp.PeerReconciliationModels.PeerReconciliationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatAppMessagePeerReconciliationServiceTest {
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private final ContactIdentityMapper identityMapper = mock(ContactIdentityMapper.class);
    private final ContactMapper contactMapper = mock(ContactMapper.class);

    private ChatAppMessagePeerReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new ChatAppMessagePeerReconciliationService(
                messageMapper, conversationMapper, identityMapper, contactMapper);
    }

    @Test
    void movesExistingMessageToConversationOwnedByProviderUserNumber() {
        UUID accountId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID sourceConversationId = UUID.randomUUID();
        UUID targetConversationId = UUID.randomUUID();
        UUID sourceIdentityId = UUID.randomUUID();
        UUID targetIdentityId = UUID.randomUUID();

        MessageEntity message = message(messageId, accountId, sourceConversationId, "provider-134");
        ConversationEntity source = conversation(sourceConversationId, sourceIdentityId);
        ConversationEntity target = conversation(targetConversationId, targetIdentityId);
        ContactIdentityEntity sourceIdentity = identity(sourceIdentityId, accountId, "8613266259485");
        ContactIdentityEntity targetIdentity = identity(targetIdentityId, accountId, "8613428277520");

        when(messageMapper.findByProviderMessageId(accountId, "provider-134"))
                .thenReturn(Optional.of(message));
        when(conversationMapper.selectById(sourceConversationId)).thenReturn(source);
        when(identityMapper.selectById(sourceIdentityId)).thenReturn(sourceIdentity);
        when(identityMapper.findByNormalizedValueInScope(
                "chatapp", accountId.toString(), "8613428277520"))
                .thenReturn(Optional.of(targetIdentity));
        when(conversationMapper.getOrCreateConversation(accountId, targetIdentityId)).thenReturn(target);
        when(conversationMapper.lockForMessage(sourceConversationId, accountId)).thenReturn(source);
        when(conversationMapper.lockForMessage(targetConversationId, accountId)).thenReturn(target);
        when(conversationMapper.allocateNextIngestSequence(targetConversationId)).thenReturn(20L);
        when(messageMapper.updateConversationAndSequence(
                messageId, sourceConversationId, targetConversationId, 20L)).thenReturn(1);

        PeerReconciliationResult result = service.reconcile(new PeerReconciliationCommand(
                accountId, messageId, "provider-134", "8613428277520"));

        assertThat(result.kind()).isEqualTo(PeerReconciliationResult.Kind.MOVED);
        assertThat(result.identityId()).isEqualTo(targetIdentityId);
        assertThat(result.conversationId()).isEqualTo(targetConversationId);
        verify(messageMapper).updateConversationAndSequence(
                messageId, sourceConversationId, targetConversationId, 20L);
        verify(conversationMapper).recomputeProjection(sourceConversationId);
        verify(conversationMapper).recomputeProjection(targetConversationId);
    }

    @Test
    void keepsSelfSendInBusinessNumberIdentityWhenProviderUserNumberMatchesIt() {
        UUID accountId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();

        MessageEntity message = message(messageId, accountId, conversationId, "provider-self");
        ConversationEntity conversation = conversation(conversationId, identityId);
        ContactIdentityEntity identity = identity(identityId, accountId, "8613266259485");
        when(messageMapper.findByProviderMessageId(accountId, "provider-self"))
                .thenReturn(Optional.of(message));
        when(conversationMapper.selectById(conversationId)).thenReturn(conversation);
        when(identityMapper.selectById(identityId)).thenReturn(identity);

        PeerReconciliationResult result = service.reconcile(new PeerReconciliationCommand(
                accountId, messageId, "provider-self", "8613266259485"));

        assertThat(result.kind()).isEqualTo(PeerReconciliationResult.Kind.UNCHANGED);
        assertThat(result.identityId()).isEqualTo(identityId);
        assertThat(result.conversationId()).isEqualTo(conversationId);
        verify(messageMapper, never()).updateConversationAndSequence(
                any(), any(), any(), anyLong());
        verifyNoInteractions(contactMapper);
    }

    @Test
    void createsScopedIdentityWhenProviderPeerDoesNotExist() {
        UUID accountId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID sourceConversationId = UUID.randomUUID();
        UUID sourceIdentityId = UUID.randomUUID();
        UUID targetConversationId = UUID.randomUUID();
        AtomicReference<UUID> targetIdentityId = new AtomicReference<>();

        MessageEntity message = message(messageId, accountId, sourceConversationId, "provider-new-peer");
        ConversationEntity source = conversation(sourceConversationId, sourceIdentityId);
        ContactIdentityEntity sourceIdentity = identity(sourceIdentityId, accountId, "8613266259485");
        when(messageMapper.findByProviderMessageId(accountId, "provider-new-peer"))
                .thenReturn(Optional.of(message));
        when(conversationMapper.selectById(sourceConversationId)).thenReturn(source);
        when(identityMapper.selectById(sourceIdentityId)).thenReturn(sourceIdentity);
        when(identityMapper.findByNormalizedValueInScope(
                "chatapp", accountId.toString(), "8613428277520"))
                .thenReturn(Optional.empty());
        when(conversationMapper.getOrCreateConversation(eq(accountId), any(UUID.class)))
                .thenAnswer(invocation -> {
                    targetIdentityId.set(invocation.getArgument(1));
                    return conversation(targetConversationId, targetIdentityId.get());
                });
        when(conversationMapper.lockForMessage(sourceConversationId, accountId)).thenReturn(source);
        when(conversationMapper.lockForMessage(targetConversationId, accountId))
                .thenAnswer(invocation -> conversation(targetConversationId, targetIdentityId.get()));
        when(conversationMapper.allocateNextIngestSequence(targetConversationId)).thenReturn(1L);
        when(messageMapper.updateConversationAndSequence(
                eq(messageId), eq(sourceConversationId), eq(targetConversationId), eq(1L)))
                .thenReturn(1);

        PeerReconciliationResult result = service.reconcile(new PeerReconciliationCommand(
                accountId, messageId, "provider-new-peer", "8613428277520"));

        assertThat(result.kind()).isEqualTo(PeerReconciliationResult.Kind.MOVED);
        ArgumentCaptor<ContactIdentityEntity> insertedIdentity =
                ArgumentCaptor.forClass(ContactIdentityEntity.class);
        verify(identityMapper).insert(insertedIdentity.capture());
        assertThat(insertedIdentity.getValue().getIdentityScope()).isEqualTo(accountId.toString());
        assertThat(insertedIdentity.getValue().getIdentityValue()).isEqualTo("8613428277520");
        assertThat(insertedIdentity.getValue().getSource()).isEqualTo("synced");
        verify(contactMapper).insert(any(ContactEntity.class));
    }

    @Test
    void missingProviderMessageIsUnresolvedAndDoesNotCreateContact() {
        UUID accountId = UUID.randomUUID();
        when(messageMapper.findByProviderMessageId(accountId, "provider-missing"))
                .thenReturn(Optional.empty());

        PeerReconciliationResult result = service.reconcile(new PeerReconciliationCommand(
                accountId, UUID.randomUUID(), "provider-missing", "8613428277520"));

        assertThat(result.kind()).isEqualTo(PeerReconciliationResult.Kind.UNRESOLVED);
        assertThat(result.reason()).isEqualTo("CHATAPP_PEER_MESSAGE_NOT_FOUND");
        verifyNoInteractions(conversationMapper, identityMapper, contactMapper);
    }

    @Test
    void invalidProviderUserNumberIsUnresolvedBeforeDatabaseAccess() {
        PeerReconciliationResult result = service.reconcile(new PeerReconciliationCommand(
                UUID.randomUUID(), UUID.randomUUID(), "provider-invalid", "not-a-phone"));

        assertThat(result.kind()).isEqualTo(PeerReconciliationResult.Kind.UNRESOLVED);
        assertThat(result.reason()).isEqualTo("CHATAPP_PEER_NUMBER_INVALID");
        verifyNoInteractions(messageMapper, conversationMapper, identityMapper, contactMapper);
    }

    @Test
    void dryRunReportsMoveAndMissingIdentityWithoutWriting() {
        UUID accountId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID sourceConversationId = UUID.randomUUID();
        UUID sourceIdentityId = UUID.randomUUID();
        when(messageMapper.findByProviderMessageId(accountId, "provider-dry-run"))
                .thenReturn(Optional.of(message(
                        messageId, accountId, sourceConversationId, "provider-dry-run")));
        when(conversationMapper.selectById(sourceConversationId))
                .thenReturn(conversation(sourceConversationId, sourceIdentityId));
        when(identityMapper.selectById(sourceIdentityId))
                .thenReturn(identity(sourceIdentityId, accountId, "8613266259485"));
        when(identityMapper.findByNormalizedValueInScope(
                "chatapp", accountId.toString(), "8613428277520"))
                .thenReturn(Optional.empty());

        PeerReconciliationResult result = service.inspect(new PeerReconciliationCommand(
                accountId, messageId, "provider-dry-run", "8613428277520"));

        assertThat(result.kind()).isEqualTo(PeerReconciliationResult.Kind.MOVED);
        assertThat(result.identityCreated()).isTrue();
        verify(contactMapper, never()).insert(any(ContactEntity.class));
        verify(identityMapper, never()).insert(any(ContactIdentityEntity.class));
        verify(messageMapper, never()).updateConversationAndSequence(any(), any(), any(), anyLong());
    }

    private static MessageEntity message(UUID id, UUID accountId, UUID conversationId,
                                         String providerMessageId) {
        MessageEntity message = new MessageEntity();
        message.setId(id);
        message.setChannelAccountId(accountId);
        message.setConversationId(conversationId);
        message.setProviderMessageId(providerMessageId);
        return message;
    }

    private static ConversationEntity conversation(UUID id, UUID identityId) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(id);
        conversation.setContactIdentityId(identityId);
        return conversation;
    }

    private static ContactIdentityEntity identity(UUID id, UUID accountId, String normalizedValue) {
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(id);
        identity.setChannelType("chatapp");
        identity.setIdentityScope(accountId.toString());
        identity.setNormalizedValue(normalizedValue);
        identity.setIdentityValue(normalizedValue);
        return identity;
    }
}
