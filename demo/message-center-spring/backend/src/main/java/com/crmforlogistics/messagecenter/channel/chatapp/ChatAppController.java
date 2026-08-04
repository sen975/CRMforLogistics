package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.service.chatapp.ChatAppTemplateService;
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
    private final ChatAppMessageSyncService messageSyncService;
    private final ChatAppTemplateSyncService templateSyncService;
    private final ChatAppTemplateService templateService;

    public ChatAppController(ChatAppSendService sendService,
                              ChatAppMessageSyncService messageSyncService,
                              ChatAppTemplateSyncService templateSyncService,
                              ChatAppTemplateService templateService) {
        this.sendService = sendService;
        this.messageSyncService = messageSyncService;
        this.templateSyncService = templateSyncService;
        this.templateService = templateService;
    }

    @PostMapping("/send/text")
    public ResponseEntity<?> sendText(@RequestBody Map<String, String> body) {
        try {
            String clientRequestId = body.getOrDefault("clientRequestId", UUID.randomUUID().toString());
            ChatAppSendService.SendResult result = sendService.sendText(
                    body.get("to"), body.get("text"), clientRequestId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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
            ChatAppSendService.SendResult result = sendService.sendTemplate(
                    body.get("to"), body.get("templateCode"), body.get("templateName"),
                    body.get("languageCode"), params, clientRequestId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
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
        return ResponseEntity.ok(result);
    }
}
