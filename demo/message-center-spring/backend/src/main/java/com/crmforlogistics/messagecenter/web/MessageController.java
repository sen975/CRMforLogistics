package com.crmforlogistics.messagecenter.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crmforlogistics.messagecenter.dto.response.MessageResponse;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppSendService;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class MessageController {

    private final MessageMapper messageMapper;
    private final ChannelAccountMapper channelAccountMapper;
    private final ConversationMapper conversationMapper;
    private final ContactIdentityMapper contactIdentityMapper;
    private final ChatAppSendService chatAppSendService;

    public MessageController(MessageMapper messageMapper,
                             ChannelAccountMapper channelAccountMapper,
                             ConversationMapper conversationMapper,
                             ContactIdentityMapper contactIdentityMapper,
                             ChatAppSendService chatAppSendService) {
        this.messageMapper = messageMapper;
        this.channelAccountMapper = channelAccountMapper;
        this.conversationMapper = conversationMapper;
        this.contactIdentityMapper = contactIdentityMapper;
        this.chatAppSendService = chatAppSendService;
    }

    @GetMapping("/messages/{id}")
    public ResponseEntity<MessageResponse> getMessage(@PathVariable UUID id) {
        MessageEntity entity = messageMapper.selectById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toMessageResponse(entity));
    }

    @PostMapping("/send/email")
    public ResponseEntity<Void> sendEmail() {
        return ResponseEntity.status(501).build();
    }

    @PostMapping("/send/chatapp")
    public ResponseEntity<?> sendChatApp(@RequestBody Map<String, Object> body) {
        try {
            String mode = (String) body.getOrDefault("mode", "text");
            String to = (String) body.get("to");
            String clientRequestId = (String) body.getOrDefault("clientRequestId",
                    UUID.randomUUID().toString());

            ChatAppSendService.SendResult result;
            if ("template".equals(mode)) {
                String templateCode = (String) body.get("templateCode");
                String templateName = (String) body.get("templateName");
                String languageCode = (String) body.get("languageCode");
                result = chatAppSendService.sendTemplate(to, templateCode, templateName,
                        languageCode, Map.of(), clientRequestId);
            } else {
                String text = (String) body.getOrDefault("text", "");
                result = chatAppSendService.sendText(to, text, clientRequestId);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/channel-capabilities")
    public List<ChannelCapabilityItem> channelCapabilities() {
        SecurityUtil.currentUserId();
        List<ChannelAccountEntity> accounts = channelAccountMapper.selectList(
                new LambdaQueryWrapper<ChannelAccountEntity>()
                        .isNull(ChannelAccountEntity::getDeletedAt));

        return accounts.stream()
                .map(a -> new ChannelCapabilityItem(
                        a.getChannelType(),
                        channelDisplayName(a),
                        a.getAuthStatus()))
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
                entity.getDirection(),
                entity.getMessageKind(),
                entity.getSubject(),
                entity.getBodyText(),
                entity.getBodyHtml(),
                channelType,
                from,
                to,
                entity.getOccurredAt(),
                entity.getCurrentStatus(),
                entity.getIngestSequence() != null ? entity.getIngestSequence().intValue() : 0
        );
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

    private record ChannelCapabilityItem(String channelType, String displayName, String authStatus) {}
}
