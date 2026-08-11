package com.crmforlogistics.messagecenter.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crmforlogistics.messagecenter.dto.response.MessageResponse;
import com.crmforlogistics.messagecenter.dto.response.MessageAttachmentResponse;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.channel.email.EmailSendService;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMediaApplicationService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessageApplicationService;
import com.crmforlogistics.messagecenter.service.conversation.ConversationAccessService;
import com.crmforlogistics.messagecenter.service.message.TemplateMessageTextResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
    private final ChatAppMessageApplicationService chatAppMessageApplicationService;
    private final ChatAppMediaApplicationService chatAppMediaApplicationService;
    private final EmailSendService emailSendService;
    private final ConversationAccessService conversationAccessService;
    private final TemplateMessageTextResolver templateMessageTextResolver;
    private final AttachmentMapper attachmentMapper;

    public MessageController(MessageMapper messageMapper,
                             ChannelAccountMapper channelAccountMapper,
                             ConversationMapper conversationMapper,
                             ContactIdentityMapper contactIdentityMapper,
                             ChatAppMessageApplicationService chatAppMessageApplicationService,
                             ChatAppMediaApplicationService chatAppMediaApplicationService,
                             EmailSendService emailSendService,
                             ConversationAccessService conversationAccessService,
                             TemplateMessageTextResolver templateMessageTextResolver,
                             AttachmentMapper attachmentMapper) {
        this.messageMapper = messageMapper;
        this.channelAccountMapper = channelAccountMapper;
        this.conversationMapper = conversationMapper;
        this.contactIdentityMapper = contactIdentityMapper;
        this.chatAppMessageApplicationService = chatAppMessageApplicationService;
        this.chatAppMediaApplicationService = chatAppMediaApplicationService;
        this.emailSendService = emailSendService;
        this.conversationAccessService = conversationAccessService;
        this.templateMessageTextResolver = templateMessageTextResolver;
        this.attachmentMapper = attachmentMapper;
    }

    @GetMapping("/messages/{id}")
    public ResponseEntity<MessageResponse> getMessage(@PathVariable UUID id) {
        MessageEntity entity = messageMapper.selectById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        conversationAccessService.requireAccessible(
                entity.getConversationId(), entity.getChannelAccountId(), SecurityUtil.currentUserId());
        return ResponseEntity.ok(toMessageResponse(entity));
    }

    @PostMapping("/send/email")
    public ResponseEntity<?> sendEmail(@RequestBody Map<String, Object> body) {
        try {
            String to = (String) body.get("to");
            String subject = (String) body.getOrDefault("subject", "");
            String text = (String) body.getOrDefault("body", "");
            EmailSendService.SendResult result = emailSendService.send(to, subject, text);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/send/chatapp-media")
    public ResponseEntity<?> sendChatAppMedia(
            @RequestParam("to") String to,
            @RequestParam("mediaType") String mediaType,
            @RequestParam(value = "caption", required = false) String caption,
            @RequestParam(value = "clientRequestId", required = false) String clientRequestId,
            @RequestParam("file") MultipartFile file) {
        try {
            var result = chatAppMediaApplicationService.accept(
                    to, mediaType, file.getBytes(), file.getOriginalFilename(),
                    file.getContentType(), caption == null ? "" : caption,
                    clientRequestId == null || clientRequestId.isBlank()
                            ? UUID.randomUUID().toString() : clientRequestId,
                    SecurityUtil.currentUserId());
            return ResponseEntity.ok(result);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/send/chatapp")
    public ResponseEntity<?> sendChatApp(@RequestBody Map<String, Object> body) {
        try {
            String mode = (String) body.getOrDefault("mode", "text");
            String to = (String) body.get("to");
            String clientRequestId = (String) body.getOrDefault("clientRequestId",
                    UUID.randomUUID().toString());

            var content = new java.util.LinkedHashMap<String, Object>();
            if ("template".equals(mode)) {
                String templateCode = (String) body.get("templateCode");
                String templateName = (String) body.get("templateName");
                String languageCode = (String) body.get("languageCode");
                @SuppressWarnings("unchecked")
                Map<String, String> templateParams = body.containsKey("templateParams")
                        ? (Map<String, String>) body.get("templateParams")
                        : Map.of();
                content.put("templateCode", templateCode);
                content.put("templateName", templateName);
                content.put("languageCode", languageCode);
                content.put("templateParams", templateParams);
            } else {
                String text = (String) body.getOrDefault("text", "");
                content.put("text", text);
            }
            var result = chatAppMessageApplicationService.acceptRecipient(
                    to, "template".equals(mode) ? "template" : "text",
                    clientRequestId, content, SecurityUtil.currentUserId());
            return ResponseEntity.ok(result);
        } catch (SecurityException e) {
            throw e;
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
                        .toList()
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
