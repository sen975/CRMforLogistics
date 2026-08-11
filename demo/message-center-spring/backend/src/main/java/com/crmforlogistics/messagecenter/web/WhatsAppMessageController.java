package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessageApplicationService;
import com.crmforlogistics.messagecenter.service.message.MessageSendApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
public class WhatsAppMessageController {
    private final ChatAppMessageApplicationService messageApplicationService;

    public WhatsAppMessageController(ChatAppMessageApplicationService messageApplicationService) {
        this.messageApplicationService = messageApplicationService;
    }

    @PostMapping("/api/v1/whatsapp/messages")
    public ResponseEntity<MessageSendApplicationService.MessageAccepted> send(
            @RequestBody SendWhatsAppMessageRequest request) {
        var accepted = messageApplicationService.acceptConversation(
                request.conversationId(), request.kind(), request.clientRequestId(),
                request.content(), SecurityUtil.currentUserId());
        return ResponseEntity.status(accepted.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(accepted);
    }

    public record SendWhatsAppMessageRequest(
            UUID conversationId,
            String kind,
            String clientRequestId,
            Map<String, Object> content) {}
}
