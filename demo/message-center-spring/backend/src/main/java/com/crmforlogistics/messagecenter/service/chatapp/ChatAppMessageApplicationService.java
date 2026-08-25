package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.infrastructure.ContactPointUtil;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.service.message.MessageSendApplicationService;
import com.crmforlogistics.messagecenter.service.conversation.ConversationAccessService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChatAppMessageApplicationService {
    private final ContactIdentityMapper contactIdentityMapper;
    private final ContactMapper contactMapper;
    private final ConversationMapper conversationMapper;
    private final MessageSendApplicationService sendService;
    private final ConversationAccessService conversationAccessService;
    private final ChatAppAccountResolver accountResolver;

    public ChatAppMessageApplicationService(ContactIdentityMapper contactIdentityMapper,
                                            ContactMapper contactMapper,
                                            ConversationMapper conversationMapper,
                                            MessageSendApplicationService sendService,
                                            ConversationAccessService conversationAccessService,
                                            ChatAppAccountResolver accountResolver) {
        this.contactIdentityMapper = Objects.requireNonNull(contactIdentityMapper);
        this.contactMapper = Objects.requireNonNull(contactMapper);
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.sendService = Objects.requireNonNull(sendService);
        this.conversationAccessService = Objects.requireNonNull(conversationAccessService);
        this.accountResolver = Objects.requireNonNull(accountResolver);
    }

    public MessageSendApplicationService.MessageAccepted acceptContactIdentity(
            UUID contactId, UUID recipientIdentityId, String kind, String clientRequestId,
            Map<String, Object> content, UUID actorUserId) {
        RecipientContext context = resolveContactIdentity(
                contactId, recipientIdentityId, actorUserId);
        return accept(context.account(), context.conversation(), context.recipient(),
                kind, clientRequestId, content, actorUserId);
    }

    public void authorizeContactIdentity(
            UUID contactId, UUID recipientIdentityId, UUID actorUserId) {
        authorizeRecipient(contactId, recipientIdentityId, actorUserId);
    }

    public MessageSendApplicationService.MessageAccepted acceptConversation(
            UUID conversationId, String kind, String clientRequestId,
            Map<String, Object> content, UUID actorUserId) {
        ConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("CHATAPP_CONVERSATION_NOT_FOUND");
        }
        ContactIdentityEntity identity = requireIdentity(conversation.getContactIdentityId());
        if (!"chatapp".equalsIgnoreCase(identity.getChannelType())) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_IDENTITY_CHANNEL_INVALID");
        }
        ChannelAccountEntity account = accountResolver.requireCurrentAccount(
                conversation.getChannelAccountId());
        String recipient = recipientForAccount(account, identity);
        conversationAccessService.requireAccessible(
                conversation.getId(), account.getId(), actorUserId);
        return accept(account, conversation, recipient, kind, clientRequestId, content, actorUserId);
    }

    private MessageSendApplicationService.MessageAccepted accept(
            ChannelAccountEntity account, ConversationEntity conversation, String recipient,
            String kind, String clientRequestId, Map<String, Object> content, UUID actorUserId) {
        Map<String, Object> providerContent = new LinkedHashMap<>();
        if (content != null) providerContent.putAll(content);
        providerContent.put("to", recipient);
        return sendService.accept(new MessageSendApplicationService.SendMessageCommand(
                account.getId(), conversation.getId(), kind, clientRequestId, providerContent), actorUserId);
    }

    private RecipientContext resolveContactIdentity(
            UUID contactId, UUID recipientIdentityId, UUID actorUserId) {
        AuthorizedRecipient authorized = authorizeRecipient(
                contactId, recipientIdentityId, actorUserId);
        ConversationEntity conversation = conversationMapper.getOrCreateConversationForSender(
                authorized.account().getId(), authorized.identity().getId(), actorUserId);
        conversationAccessService.requireAccessible(
                conversation.getId(), authorized.account().getId(), actorUserId);
        return new RecipientContext(
                authorized.account(), conversation, authorized.recipient());
    }

    private AuthorizedRecipient authorizeRecipient(
            UUID contactId, UUID recipientIdentityId, UUID actorUserId) {
        if (contactId == null) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_REQUIRED");
        }
        if (recipientIdentityId == null) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_IDENTITY_REQUIRED");
        }
        ContactIdentityEntity identity = requireIdentity(recipientIdentityId);
        if (!"chatapp".equalsIgnoreCase(identity.getChannelType())) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_IDENTITY_CHANNEL_INVALID");
        }
        if (!contactId.equals(identity.getContactId())) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_IDENTITY_MISMATCH");
        }
        UUID channelAccountId;
        try {
            channelAccountId = UUID.fromString(identity.getIdentityScope());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE", error);
        }
        ChannelAccountEntity account = accountResolver.requireCurrentAccount(channelAccountId);
        String recipient = recipientForAccount(account, identity);
        if (contactMapper.findAccessibleForChatAppSend(
                contactId, identity.getId(), account.getId(), actorUserId).isEmpty()) {
            throw new SecurityException("CHATAPP_CONVERSATION_FORBIDDEN");
        }
        return new AuthorizedRecipient(account, identity, recipient);
    }

    private ContactIdentityEntity requireIdentity(UUID identityId) {
        ContactIdentityEntity identity = contactIdentityMapper.selectById(identityId);
        if (identity == null || identity.getDeletedAt() != null) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_IDENTITY_NOT_FOUND");
        }
        return identity;
    }

    private String recipientForAccount(ChannelAccountEntity account,
                                       ContactIdentityEntity identity) {
        if (!"chatapp".equalsIgnoreCase(identity.getChannelType())) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_IDENTITY_CHANNEL_INVALID");
        }
        if (!account.getId().toString().equals(identity.getIdentityScope())) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
        }
        String recipient = ContactPointUtil.normalizePhone(identity.getIdentityValue());
        if (recipient.isBlank()) {
            throw new IllegalArgumentException("CHATAPP_RECIPIENT_REQUIRED");
        }
        return recipient;
    }

    private record RecipientContext(ChannelAccountEntity account,
                                    ConversationEntity conversation,
                                    String recipient) {}

    private record AuthorizedRecipient(ChannelAccountEntity account,
                                       ContactIdentityEntity identity,
                                       String recipient) {}
}
