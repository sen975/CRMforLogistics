package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.infrastructure.ContactPointUtil;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.service.chatapp.PeerReconciliationModels.PeerReconciliationCommand;
import com.crmforlogistics.messagecenter.service.chatapp.PeerReconciliationModels.PeerReconciliationResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChatAppMessagePeerReconciliationService {
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final ContactIdentityMapper identityMapper;
    private final ContactMapper contactMapper;

    public ChatAppMessagePeerReconciliationService(MessageMapper messageMapper,
                                                   ConversationMapper conversationMapper,
                                                   ContactIdentityMapper identityMapper,
                                                   ContactMapper contactMapper) {
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.contactMapper = Objects.requireNonNull(contactMapper);
    }

    @Transactional
    public PeerReconciliationResult reconcile(PeerReconciliationCommand command) {
        InspectionContext context = inspectContext(command);
        if (context.result() != null) {
            return context.result();
        }
        if (matches(context.sourceIdentity(), command.channelAccountId(), context.normalized())) {
            return PeerReconciliationResult.unchanged(
                    context.sourceIdentity().getId(), context.sourceConversation().getId());
        }

        IdentityResolution identityResolution = findOrCreateIdentity(
                command.channelAccountId(), context.normalized());
        ContactIdentityEntity targetIdentity = identityResolution.identity();
        ConversationEntity targetConversation = conversationMapper.getOrCreateConversation(
                command.channelAccountId(), targetIdentity.getId());
        if (targetConversation == null) {
            return PeerReconciliationResult.unresolved("CHATAPP_PEER_TARGET_CONVERSATION_NOT_FOUND");
        }
        if (context.sourceConversation().getId().equals(targetConversation.getId())) {
            return PeerReconciliationResult.unchanged(
                    targetIdentity.getId(), targetConversation.getId());
        }

        lockInStableOrder(context.sourceConversation().getId(), targetConversation.getId(),
                command.channelAccountId());
        long targetSequence = conversationMapper.allocateNextIngestSequence(targetConversation.getId());
        int moved = messageMapper.updateConversationAndSequence(
                context.message().getId(), context.sourceConversation().getId(),
                targetConversation.getId(), targetSequence);
        if (moved == 0) {
            Optional<MessageEntity> reloaded = messageMapper.findByProviderMessageId(
                    command.channelAccountId(), command.providerMessageId());
            if (reloaded.isPresent()
                    && targetConversation.getId().equals(reloaded.orElseThrow().getConversationId())) {
                return PeerReconciliationResult.unchanged(
                        targetIdentity.getId(), targetConversation.getId());
            }
            return PeerReconciliationResult.unresolved("CHATAPP_PEER_CONCURRENT_MOVE");
        }

        conversationMapper.recomputeProjection(context.sourceConversation().getId());
        conversationMapper.recomputeProjection(targetConversation.getId());
        return PeerReconciliationResult.moved(
                targetIdentity.getId(), targetConversation.getId(), identityResolution.created());
    }

    @Transactional(readOnly = true)
    public PeerReconciliationResult inspect(PeerReconciliationCommand command) {
        InspectionContext context = inspectContext(command);
        if (context.result() != null) {
            return context.result();
        }
        if (matches(context.sourceIdentity(), command.channelAccountId(), context.normalized())) {
            return PeerReconciliationResult.unchanged(
                    context.sourceIdentity().getId(), context.sourceConversation().getId());
        }
        Optional<ContactIdentityEntity> target = identityMapper.findByNormalizedValueInScope(
                "chatapp", command.channelAccountId().toString(), context.normalized());
        return PeerReconciliationResult.moved(
                target.map(ContactIdentityEntity::getId).orElse(null), null, target.isEmpty());
    }

    private InspectionContext inspectContext(PeerReconciliationCommand command) {
        if (command == null || command.channelAccountId() == null
                || command.providerMessageId() == null || command.providerMessageId().isBlank()) {
            return InspectionContext.resolved(
                    PeerReconciliationResult.unresolved("CHATAPP_PEER_MESSAGE_NOT_FOUND"));
        }
        String normalized = ContactPointUtil.normalizePhone(command.userNumber());
        if (normalized.isBlank()) {
            return InspectionContext.resolved(
                    PeerReconciliationResult.unresolved("CHATAPP_PEER_NUMBER_INVALID"));
        }
        Optional<MessageEntity> found = messageMapper.findByProviderMessageId(
                command.channelAccountId(), command.providerMessageId());
        if (found.isEmpty()) {
            return InspectionContext.resolved(
                    PeerReconciliationResult.unresolved("CHATAPP_PEER_MESSAGE_NOT_FOUND"));
        }
        MessageEntity message = found.orElseThrow();
        if (command.messageId() != null && !command.messageId().equals(message.getId())) {
            return InspectionContext.resolved(
                    PeerReconciliationResult.unresolved("CHATAPP_PEER_MESSAGE_MISMATCH"));
        }
        ConversationEntity sourceConversation = conversationMapper.selectById(message.getConversationId());
        if (sourceConversation == null) {
            return InspectionContext.resolved(PeerReconciliationResult.unresolved(
                    "CHATAPP_PEER_SOURCE_CONVERSATION_NOT_FOUND"));
        }
        ContactIdentityEntity sourceIdentity = identityMapper.selectById(
                sourceConversation.getContactIdentityId());
        return new InspectionContext(
                normalized, message, sourceConversation, sourceIdentity, null);
    }

    private IdentityResolution findOrCreateIdentity(UUID accountId, String normalized) {
        Optional<ContactIdentityEntity> existing = identityMapper.findByNormalizedValueInScope(
                "chatapp", accountId.toString(), normalized);
        if (existing.isPresent()) {
            return new IdentityResolution(existing.orElseThrow(), false);
        }

        ContactEntity contact = new ContactEntity();
        contact.setId(UUID.randomUUID());
        contact.setDisplayName(normalized);
        contact.setStatus("active");
        contactMapper.insert(contact);

        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contact.getId());
        identity.setChannelType("chatapp");
        identity.setIdentityScope(accountId.toString());
        identity.setIdentityValue(normalized);
        identity.setNormalizedValue(normalized);
        identity.setDisplayName(normalized);
        identity.setIsPrimary(true);
        identity.setVerifyStatus("unverified");
        identity.setSource("synced");
        identityMapper.insert(identity);
        return new IdentityResolution(identity, true);
    }

    private void lockInStableOrder(UUID firstConversationId, UUID secondConversationId,
                                   UUID accountId) {
        UUID first = firstConversationId.compareTo(secondConversationId) <= 0
                ? firstConversationId : secondConversationId;
        UUID second = first.equals(firstConversationId) ? secondConversationId : firstConversationId;
        if (conversationMapper.lockForMessage(first, accountId) == null
                || conversationMapper.lockForMessage(second, accountId) == null) {
            throw new IllegalStateException("CHATAPP_PEER_SCOPE_MISMATCH");
        }
    }

    private static boolean matches(ContactIdentityEntity identity, UUID accountId,
                                   String normalized) {
        return identity != null
                && "chatapp".equalsIgnoreCase(identity.getChannelType())
                && accountId.toString().equals(identity.getIdentityScope())
                && normalized.equals(identity.getNormalizedValue());
    }

    private record IdentityResolution(ContactIdentityEntity identity, boolean created) {
    }

    private record InspectionContext(
            String normalized,
            MessageEntity message,
            ConversationEntity sourceConversation,
            ContactIdentityEntity sourceIdentity,
            PeerReconciliationResult result) {
        private static InspectionContext resolved(PeerReconciliationResult result) {
            return new InspectionContext(null, null, null, null, result);
        }
    }
}
