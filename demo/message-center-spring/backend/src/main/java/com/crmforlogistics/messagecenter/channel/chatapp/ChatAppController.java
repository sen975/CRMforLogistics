package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessageApplicationService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppTemplateService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppHistoryReconciliationResult;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
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
    public ResponseEntity<?> sendText(@RequestBody TextSendRequest request) {
        return ResponseEntity.ok(messageApplicationService.acceptContactIdentity(
                request.contactId(), request.recipientIdentityId(), "text",
                clientRequestId(request.clientRequestId()),
                Map.of("text", value(request.text())),
                SecurityUtil.currentUserId()));
    }

    @PostMapping("/send/template")
    public ResponseEntity<?> sendTemplate(@RequestBody TemplateSendRequest request) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> params = request.templateParams() != null
                    ? new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                            request.templateParams(), Map.class)
                    : Map.of();
            Map<String, Object> content = new java.util.LinkedHashMap<>();
            content.put("templateCode", request.templateCode());
            content.put("templateName", request.templateName());
            content.put("languageCode", request.languageCode());
            content.put("templateParams", params);
            return ResponseEntity.ok(messageApplicationService.acceptContactIdentity(
                    request.contactId(), request.recipientIdentityId(), "template",
                    clientRequestId(request.clientRequestId()), content,
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
        ChatAppMessageSyncService.SyncResultRecord result =
                messageSyncService.runOwnedAccount(SecurityUtil.currentUserId());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/sync/messages/reconcile")
    public ResponseEntity<ChatAppHistoryReconciliationResult> reconcileMessages(
            @RequestBody HistoryReconciliationRequest request) {
        return ResponseEntity.ok(messageSyncService.runOwnedAccount(
                SecurityUtil.currentUserId(), request.accountId(), request.startTime(), request.endTime(),
                request.maxPages(), request.dryRun()));
    }

    @PostMapping("/sync/templates")
    public ResponseEntity<?> syncTemplates() {
        ChatAppTemplateSyncService.SyncResultRecord result =
                templateSyncService.runOwnedAccount(SecurityUtil.currentUserId());
        if (result.syncFailed() || !result.complete()) {
            return ResponseEntity.status(result.retryable()
                    ? org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE
                    : org.springframework.http.HttpStatus.BAD_GATEWAY).body(result);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(@RequestBody String rawBody) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.GONE)
                .body(Map.of("code", "CHATAPP_WEBHOOK_DEPRECATED",
                        "message", "Use /api/v1/webhooks/chatapp"));
    }

    private static String clientRequestId(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    public record TextSendRequest(
            UUID contactId,
            UUID recipientIdentityId,
            String text,
            String clientRequestId) {}

    public record TemplateSendRequest(
            UUID contactId,
            UUID recipientIdentityId,
            String templateCode,
            String templateName,
            String languageCode,
            String templateParams,
            String clientRequestId) {}

    public record HistoryReconciliationRequest(
            UUID accountId,
            Instant startTime,
            Instant endTime,
            int maxPages,
            boolean dryRun) {}
}
