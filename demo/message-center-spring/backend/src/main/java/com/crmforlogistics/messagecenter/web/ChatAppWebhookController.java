package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.service.chatapp.ChatAppWebhookInboxService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
public class ChatAppWebhookController {
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private final ChatAppWebhookInboxService inboxService;

    public ChatAppWebhookController(ChatAppWebhookInboxService inboxService) {
        this.inboxService = inboxService;
    }

    @PostMapping("/api/v1/webhooks/chatapp")
    public ResponseEntity<ChatAppWebhookInboxService.WebhookReceipt> receive(
            @RequestHeader(value = "X-CAMS-Signature", required = false) String signature,
            @RequestHeader(value = "X-CAMS-Timestamp", required = false) String timestamp,
            HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("CHATAPP_WEBHOOK_BODY_SIZE_INVALID");
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("CHATAPP_WEBHOOK_BODY_SIZE_INVALID");
        }
        String rawBody = new String(bytes, StandardCharsets.UTF_8);
        return ResponseEntity.ok(inboxService.accept(signature, timestamp, rawBody));
    }
}
