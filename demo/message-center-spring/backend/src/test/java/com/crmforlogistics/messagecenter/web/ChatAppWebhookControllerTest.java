package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppWebhookAuthenticationException;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppWebhookInboxService;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppController;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppSendService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessageApplicationService;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppMessageSyncService;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppTemplateSyncService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppTemplateService;
import com.crmforlogistics.messagecenter.service.event.EventHub;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ChatAppWebhookController.class, ChatAppController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ChatAppWebhookControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ChatAppWebhookInboxService inboxService;
    @MockitoBean AuthSessionService authSessionService;
    @MockitoBean ChatAppSendService sendService;
    @MockitoBean ChatAppMessageApplicationService messageApplicationService;
    @MockitoBean ChatAppMessageSyncService messageSyncService;
    @MockitoBean ChatAppTemplateSyncService templateSyncService;
    @MockitoBean ChatAppTemplateService templateService;
    @MockitoBean EventHub eventHub;

    @Test
    void legacyWebhookRouteReturnsExplicitGoneInsteadOfAuthenticationFailure() throws Exception {
        mvc.perform(post("/api/chatapp/webhook").content("{}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CHATAPP_WEBHOOK_DEPRECATED"));
        verifyNoInteractions(inboxService);
    }

    @Test
    void webhookIsPublicAndAcceptsRequestWithoutUserToken() throws Exception {
        String body = "{\"MessageId\":\"wamid-1\"}";
        UUID eventId = UUID.randomUUID();
        when(inboxService.accept("valid", "1800000000", body))
                .thenReturn(new ChatAppWebhookInboxService.WebhookReceipt(
                        eventId, false, "processed"));

        mvc.perform(post("/api/v1/webhooks/chatapp")
                        .header("X-CAMS-Signature", "valid")
                        .header("X-CAMS-Timestamp", "1800000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()));
    }

    @Test
    void invalidWebhookSignatureReturnsUnauthorized() throws Exception {
        String body = "{\"MessageId\":\"wamid-1\"}";
        when(inboxService.accept("invalid", "1800000000", body))
                .thenThrow(new ChatAppWebhookAuthenticationException(
                        "CHATAPP_WEBHOOK_SIGNATURE_INVALID"));

        mvc.perform(post("/api/v1/webhooks/chatapp")
                        .header("X-CAMS-Signature", "invalid")
                        .header("X-CAMS-Timestamp", "1800000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CHATAPP_WEBHOOK_UNAUTHORIZED"));
    }

    @Test
    void bodyLargerThanOneMebibyteIsRejectedBeforeInboxService() throws Exception {
        mvc.perform(post("/api/v1/webhooks/chatapp")
                        .header("X-CAMS-Signature", "signature")
                        .header("X-CAMS-Timestamp", "1800000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("x".repeat(1024 * 1024 + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("CHATAPP_WEBHOOK_BODY_SIZE_INVALID"));
        verifyNoInteractions(inboxService);
    }
}
