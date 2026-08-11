package com.crmforlogistics.messagecenter.service.chatapp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.infrastructure.ContactPointUtil;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.service.message.MessageSendApplicationService;
import com.crmforlogistics.messagecenter.service.conversation.ConversationAccessService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChatAppMessageApplicationService {
    private final ChannelAccountMapper channelAccountMapper;
    private final ContactIdentityMapper contactIdentityMapper;
    private final ConversationMapper conversationMapper;
    private final MessageSendApplicationService sendService;
    private final ConversationAccessService conversationAccessService;
    private final AppConfig config;

    public ChatAppMessageApplicationService(ChannelAccountMapper channelAccountMapper,
                                            ContactIdentityMapper contactIdentityMapper,
                                            ConversationMapper conversationMapper,
                                            MessageSendApplicationService sendService,
                                            ConversationAccessService conversationAccessService,
                                            AppConfig config) {
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
        this.contactIdentityMapper = Objects.requireNonNull(contactIdentityMapper);
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.sendService = Objects.requireNonNull(sendService);
        this.conversationAccessService = Objects.requireNonNull(conversationAccessService);
        this.config = Objects.requireNonNull(config);
    }

    public MessageSendApplicationService.MessageAccepted acceptRecipient(
            String recipient, String kind, String clientRequestId,
            Map<String, Object> content, UUID actorUserId) {
        RecipientContext context = resolveRecipient(recipient, actorUserId);
        return accept(context.account(), context.conversation(), context.recipient(),
                kind, clientRequestId, content, actorUserId);
    }

    public void authorizeRecipient(String recipient, UUID actorUserId) {
        resolveRecipient(recipient, actorUserId);
    }

    public MessageSendApplicationService.MessageAccepted acceptConversation(
            UUID conversationId, String kind, String clientRequestId,
            Map<String, Object> content, UUID actorUserId) {
        ConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("CHATAPP_CONVERSATION_NOT_FOUND");
        }
        ChannelAccountEntity account = channelAccountMapper.selectById(conversation.getChannelAccountId());
        validateAccount(account);
        ContactIdentityEntity identity = contactIdentityMapper.selectById(conversation.getContactIdentityId());
        if (identity == null || identity.getDeletedAt() != null) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_IDENTITY_NOT_FOUND");
        }
        conversationAccessService.requireAccessible(
                conversation.getId(), account.getId(), actorUserId);
        String recipient = ContactPointUtil.normalizePhone(identity.getIdentityValue());
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

    private ChannelAccountEntity fixedAccount() {
        List<ChannelAccountEntity> accounts = channelAccountMapper.selectList(
                new LambdaQueryWrapper<ChannelAccountEntity>()
                        .in(ChannelAccountEntity::getChannelType, List.of("chatapp", "whatsapp"))
                        .eq(ChannelAccountEntity::getAuthStatus, "active")
                        .isNull(ChannelAccountEntity::getDeletedAt)
                        .last("limit 2"));
        if (accounts.isEmpty()) {
            throw new IllegalStateException("CHATAPP_CHANNEL_ACCOUNT_NOT_CONFIGURED");
        }
        if (accounts.size() > 1) {
            throw new IllegalStateException("CHATAPP_FIXED_ACCOUNT_VIOLATION");
        }
        ChannelAccountEntity account = accounts.get(0);
        validateAccount(account);
        return account;
    }

    private RecipientContext resolveRecipient(String recipient, UUID actorUserId) {
        String normalizedRecipient = ContactPointUtil.normalizePhone(recipient);
        if (normalizedRecipient.isBlank()) {
            throw new IllegalArgumentException("CHATAPP_RECIPIENT_REQUIRED");
        }
        ChannelAccountEntity account = fixedAccount();
        ContactIdentityEntity identity = contactIdentityMapper
                .findByNormalizedValue("chatapp", normalizedRecipient)
                .orElseThrow(() -> new IllegalArgumentException("CHATAPP_CONTACT_IDENTITY_NOT_FOUND"));
        ConversationEntity conversation = conversationMapper.getOrCreateConversation(
                account.getId(), identity.getId());
        conversationAccessService.requireAccessible(
                conversation.getId(), account.getId(), actorUserId);
        return new RecipientContext(account, conversation, normalizedRecipient);
    }

    private void validateAccount(ChannelAccountEntity account) {
        if (account == null || account.getDeletedAt() != null
                || !isChatApp(account.getChannelType())
                || !"active".equalsIgnoreCase(account.getAuthStatus())) {
            throw new IllegalArgumentException("CHATAPP_CHANNEL_ACCOUNT_NOT_FOUND");
        }
        String configured = ContactPointUtil.normalizePhone(config.chatappFrom());
        String stored = ContactPointUtil.normalizePhone(account.getAccountIdentifier());
        if (configured.isBlank() || !configured.equals(stored)) {
            throw new IllegalStateException("CHATAPP_FIXED_ACCOUNT_CONFIG_MISMATCH");
        }
    }

    private static boolean isChatApp(String channelType) {
        return "chatapp".equalsIgnoreCase(channelType) || "whatsapp".equalsIgnoreCase(channelType);
    }

    private record RecipientContext(ChannelAccountEntity account,
                                    ConversationEntity conversation,
                                    String recipient) {}
}
