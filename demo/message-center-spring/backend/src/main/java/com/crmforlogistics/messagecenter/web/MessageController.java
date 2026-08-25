package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.email.EmailSendService;
import com.crmforlogistics.messagecenter.dto.response.ChannelCapabilityResponse;
import com.crmforlogistics.messagecenter.dto.response.MessageResponse;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMediaApplicationService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessageApplicationService;
import com.crmforlogistics.messagecenter.service.message.MessageQueryService;
import org.springframework.http.ResponseEntity;import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class MessageController {

    private final MessageQueryService messageQueryService;
    private final ChatAppMessageApplicationService chatAppMessageApplicationService;
    private final ChatAppMediaApplicationService chatAppMediaApplicationService;
    private final EmailSendService emailSendService;

    public MessageController(MessageQueryService messageQueryService,
                             ChatAppMessageApplicationService chatAppMessageApplicationService,
                             ChatAppMediaApplicationService chatAppMediaApplicationService,
                             EmailSendService emailSendService) {
        this.messageQueryService = messageQueryService;
        this.chatAppMessageApplicationService = chatAppMessageApplicationService;
        this.chatAppMediaApplicationService = chatAppMediaApplicationService;
        this.emailSendService = emailSendService;
    }

    @GetMapping("/messages/{id}")
    public ResponseEntity<MessageResponse> getMessage(@PathVariable UUID id) {
        MessageResponse response = messageQueryService.getMessage(id, SecurityUtil.currentUserId());
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
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

    @PostMapping(value = "/send/email", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> sendEmailMultipart(
            @RequestParam String to,
            @RequestParam(defaultValue = "") String subject,
            @RequestParam(defaultValue = "") String body,
            @RequestPart(value = "file", required = false) List<MultipartFile> files) throws Exception {
        List<com.crmforlogistics.messagecenter.channel.email.EmailAttachmentInput> inputs =
                (files == null ? List.<MultipartFile>of() : files).stream()
                        .map(file -> new com.crmforlogistics.messagecenter.channel.email.EmailAttachmentInput(
                                file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename(),
                                file.getContentType(), file.getSize(), file::getInputStream))
                        .toList();
        return ResponseEntity.ok(emailSendService.send(to, subject, body, inputs));
    }

    @PostMapping("/send/chatapp-media")
    public ResponseEntity<?> sendChatAppMedia(
            @RequestParam("contactId") UUID contactId,
            @RequestParam("recipientIdentityId") UUID recipientIdentityId,
            @RequestParam("mediaType") String mediaType,
            @RequestParam(value = "caption", required = false) String caption,
            @RequestParam(value = "clientRequestId", required = false) String clientRequestId,
            @RequestParam("file") MultipartFile file) throws Exception {
        var result = chatAppMediaApplicationService.accept(
                contactId, recipientIdentityId, mediaType,
                file.getBytes(), file.getOriginalFilename(),
                file.getContentType(), caption == null ? "" : caption,
                clientRequestId == null || clientRequestId.isBlank()
                        ? UUID.randomUUID().toString() : clientRequestId,
                SecurityUtil.currentUserId());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/send/chatapp")
    public ResponseEntity<?> sendChatApp(@RequestBody Map<String, Object> body) {
        String mode = (String) body.getOrDefault("mode", "text");
        UUID contactId = UUID.fromString((String) body.get("contactId"));
        UUID recipientIdentityId = UUID.fromString(
                (String) body.get("recipientIdentityId"));
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
        var result = chatAppMessageApplicationService.acceptContactIdentity(
                contactId, recipientIdentityId,
                "template".equals(mode) ? "template" : "text",
                clientRequestId, content, SecurityUtil.currentUserId());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/channel-capabilities")
    public List<ChannelCapabilityResponse> channelCapabilities() {
        SecurityUtil.currentUserId();
        return messageQueryService.channelCapabilities();
    }
}
