package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessageApplicationService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppTemplateService;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chatapp")
public class ChatAppController {

    private final ChatAppSendService sendService;
    private final ChatAppMessageApplicationService messageApplicationService;
    private final ChatAppMessageSyncService messageSyncService;
    private final ChatAppTemplateSyncService templateSyncService;
    private final ChatAppTemplateService templateService;
    private final EventHub eventHub;

    public ChatAppController(ChatAppSendService sendService,
                              ChatAppMessageApplicationService messageApplicationService,
                              ChatAppMessageSyncService messageSyncService,
                              ChatAppTemplateSyncService templateSyncService,
                              ChatAppTemplateService templateService,
                              EventHub eventHub) {
        this.sendService = sendService;
        this.messageApplicationService = messageApplicationService;
        this.messageSyncService = messageSyncService;
        this.templateSyncService = templateSyncService;
        this.templateService = templateService;
        this.eventHub = eventHub;
    }

    @PostMapping("/send/text")
    public ResponseEntity<?> sendText(@RequestBody Map<String, String> body) {
        String clientRequestId = body.getOrDefault("clientRequestId", UUID.randomUUID().toString());
        return ResponseEntity.ok(messageApplicationService.acceptRecipient(
                body.get("to"), "text", clientRequestId,
                Map.of("text", body.getOrDefault("text", "")),
                SecurityUtil.currentUserId()));
    }

    @PostMapping("/send/template")
    public ResponseEntity<?> sendTemplate(@RequestBody Map<String, String> body) {
        try {
            String clientRequestId = body.getOrDefault("clientRequestId", UUID.randomUUID().toString());
            @SuppressWarnings("unchecked")
            Map<String, String> params = body.containsKey("templateParams")
                    ? new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                            body.get("templateParams"), Map.class)
                    : Map.of();
            Map<String, Object> content = new java.util.LinkedHashMap<>();
            content.put("templateCode", body.get("templateCode"));
            content.put("templateName", body.get("templateName"));
            content.put("languageCode", body.get("languageCode"));
            content.put("templateParams", params);
            return ResponseEntity.ok(messageApplicationService.acceptRecipient(
                    body.get("to"), "template", clientRequestId, content,
                    SecurityUtil.currentUserId()));
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("CHATAPP_TEMPLATE_PARAMS_INVALID", e);
        }
    }

    @PostMapping("/sync/messages")
    public ResponseEntity<?> syncMessages() {
        ChatAppMessageSyncService.SyncResultRecord result = messageSyncService.runOnce();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/sync/templates")
    public ResponseEntity<?> syncTemplates() {
        ChatAppTemplateSyncService.SyncResultRecord result = templateSyncService.runOnce();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(@RequestBody String rawBody) {
        ChatAppSendService.SendResult result = sendService.processWebhook(rawBody);
        eventHub.publish("message-new", "{}");
        return ResponseEntity.ok(result);
    }
}
