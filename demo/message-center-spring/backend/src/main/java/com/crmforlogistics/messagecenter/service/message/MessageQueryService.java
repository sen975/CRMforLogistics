package com.crmforlogistics.messagecenter.service.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crmforlogistics.messagecenter.dto.response.ChannelCapabilityResponse;
import com.crmforlogistics.messagecenter.dto.response.MessageAttachmentResponse;
import com.crmforlogistics.messagecenter.dto.response.MessageResponse;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.service.conversation.ConversationAccessService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class MessageQueryService {

    private final MessageMapper messageMapper;
    private final ChannelAccountMapper channelAccountMapper;
    private final ConversationMapper conversationMapper;
    private final ContactIdentityMapper contactIdentityMapper;
    private final AttachmentMapper attachmentMapper;
    private final TemplateMessageTextResolver templateMessageTextResolver;
    private final ConversationAccessService conversationAccessService;

    public MessageQueryService(MessageMapper messageMapper,
                               ChannelAccountMapper channelAccountMapper,
                               ConversationMapper conversationMapper,
                               ContactIdentityMapper contactIdentityMapper,
                               AttachmentMapper attachmentMapper,
                               TemplateMessageTextResolver templateMessageTextResolver,
                               ConversationAccessService conversationAccessService) {
        this.messageMapper = messageMapper;
        this.channelAccountMapper = channelAccountMapper;
        this.conversationMapper = conversationMapper;
        this.contactIdentityMapper = contactIdentityMapper;
        this.attachmentMapper = attachmentMapper;
        this.templateMessageTextResolver = templateMessageTextResolver;
        this.conversationAccessService = conversationAccessService;
    }

    public MessageResponse getMessage(UUID id, UUID userId) {
        MessageEntity ownedEntity = messageMapper.findByIdAndOwner(id, userId);
        if (ownedEntity != null) return toMessageResponse(ownedEntity);

        // The primary lookup already applies conversation authorization. This fallback only
        // supports legacy WeCom records and must not expose private channels by global id.
        MessageEntity legacyEntity = messageMapper.findWeComById(id);
        if (legacyEntity == null) return null;
        conversationAccessService.requireAccessible(
                legacyEntity.getConversationId(), legacyEntity.getChannelAccountId(), userId);
        return toMessageResponse(legacyEntity);
    }

    public List<ChannelCapabilityResponse> channelCapabilities() {
        List<ChannelAccountEntity> accounts = channelAccountMapper.selectList(
                new LambdaQueryWrapper<ChannelAccountEntity>()
                        .isNull(ChannelAccountEntity::getDeletedAt));
        return accounts.stream()
                .map(a -> new ChannelCapabilityResponse(
                        a.getChannelType(), a.getId(), channelDisplayName(a), a.getAuthStatus()))
                .toList();
    }

    private MessageResponse toMessageResponse(MessageEntity entity) {
        ChannelAccountEntity channelAccount = entity.getChannelAccountId() != null
                ? channelAccountMapper.selectById(entity.getChannelAccountId()) : null;

        String channelType = channelAccount != null ? channelAccount.getChannelType() : null;

        String from = null;
        String to = null;

        if (entity.getConversationId() != null) {
            ConversationEntity conversation = conversationMapper.selectById(entity.getConversationId());
            if (conversation != null && conversation.getContactIdentityId() != null) {
                ContactIdentityEntity identity = contactIdentityMapper.selectById(
                        conversation.getContactIdentityId());
                if (identity != null) {
                    boolean isInbound = "inbound".equals(entity.getDirection());
                    String channelName = channelAccountName(channelAccount);
                    String identityName = identityValue(identity);
                    from = isInbound ? identityName : channelName;
                    to = isInbound ? channelName : identityName;
                }
            }
        }

        return new MessageResponse(
                entity.getId(),
                entity.getProviderMessageId(),
                entity.getDirection(),
                entity.getMessageKind(),
                entity.getSubject(),
                templateMessageTextResolver.resolve(entity),
                entity.getBodyHtml(),
                channelType,
                from,
                to,
                entity.getOccurredAt(),
                entity.getCurrentStatus(),
                entity.getIngestSequence() != null ? entity.getIngestSequence().intValue() : 0,
                attachmentMapper.listReadyByMessageId(entity.getId()).stream()
                        .map(MessageAttachmentResponse::from)
                        .toList());
    }

    private static String identityValue(ContactIdentityEntity identity) {
        String display = identity.getDisplayName();
        return display != null && !display.isBlank() ? display : identity.getIdentityValue();
    }

    private static String channelAccountName(ChannelAccountEntity channelAccount) {
        if (channelAccount == null) {
            return null;
        }
        String name = channelAccount.getName();
        return name != null && !name.isBlank() ? name : channelAccount.getAccountIdentifier();
    }

    private static String channelDisplayName(ChannelAccountEntity channelAccount) {
        String name = channelAccount.getName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        String type = channelAccount.getChannelType();
        if (type == null) {
            return channelAccount.getAccountIdentifier();
        }
        return switch (type.toLowerCase()) {
            case "email" -> "Email";
            case "chatapp" -> "ChatApp";
            case "wecom" -> "WeCom";
            case "whatsapp" -> "WhatsApp";
            default -> type.substring(0, 1).toUpperCase() + type.substring(1);
        };
    }
}
