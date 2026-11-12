package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastApplicationService;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastException;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastPage;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastStatus;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastView;
import com.crmforlogistics.messagecenter.dto.response.TemplateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatAppBroadcastController.class)
@Import(SecurityConfig.class)
class ChatAppBroadcastControllerTest {
    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACCOUNT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BROADCAST_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired MockMvc mvc;
    @MockitoBean ChatAppBroadcastApplicationService service;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    void anonymousUserCannotReadBroadcasts() throws Exception {
        mvc.perform(get("/api/v1/chatapp/broadcasts")
                        .param("channelAccountId", ACCOUNT_ID.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "AGENT")
    void ordinaryAgentCannotCreateBroadcast() throws Exception {
        mvc.perform(post("/api/v1/chatapp/broadcasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "BROADCAST_SENDER")
    void broadcastSenderCanCreateBoundedTemplateBroadcast() throws Exception {
        when(service.create(any(), eq(ACTOR_ID))).thenReturn(view());

        mvc.perform(post("/api/v1/chatapp/broadcasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(BROADCAST_ID.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.recipientCount").value(1));

        verify(service).create(any(), eq(ACTOR_ID));
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "ADMIN")
    void adminCanListBroadcasts() throws Exception {
        when(service.list(ACCOUNT_ID, 1, 20, ACTOR_ID))
                .thenReturn(new BroadcastPage(List.of(view()), 1, 1, 20));

        mvc.perform(get("/api/v1/chatapp/broadcasts")
                        .param("channelAccountId", ACCOUNT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].id").value(BROADCAST_ID.toString()));

        verify(service).list(ACCOUNT_ID, 1, 20, ACTOR_ID);
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "BROADCAST_SENDER")
    void broadcastSenderCanListSendableTemplatesForSelectedAccount() throws Exception {
        when(service.sendableTemplates(ACCOUNT_ID, ACTOR_ID)).thenReturn(List.of(new TemplateResponse(
                "shipping_notice", "Shipping Notice", "发货提醒（Shipping Notice）",
                "zh_CN", "订单 $(order) 已发货", List.of("order"), "UTILITY",
                new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode(),
                java.util.Map.of("order", List.of("SO-1")))));

        mvc.perform(get("/api/v1/chatapp/broadcasts/sendable-templates")
                        .param("channelAccountId", ACCOUNT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].templateCode").value("shipping_notice"))
                .andExpect(jsonPath("$[0].placeholders[0]").value("order"));

        verify(service).sendableTemplates(ACCOUNT_ID, ACTOR_ID);
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "BROADCAST_SENDER")
    void idempotencyConflictUsesServiceHttpStatus() throws Exception {
        when(service.create(any(), eq(ACTOR_ID))).thenThrow(new ChatAppBroadcastException(
                "CHATAPP_BROADCAST_IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT));

        mvc.perform(post("/api/v1/chatapp/broadcasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHATAPP_BROADCAST_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "BROADCAST_SENDER")
    void nullRecipientUsesStableRequestValidationError() throws Exception {
        when(service.create(any(), eq(ACTOR_ID))).thenThrow(new ChatAppBroadcastException(
                "CHATAPP_BROADCAST_REQUEST_INVALID", HttpStatus.BAD_REQUEST));

        mvc.perform(post("/api/v1/chatapp/broadcasts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBodyWithNullRecipient()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHATAPP_BROADCAST_REQUEST_INVALID"));

        verify(service).create(any(), eq(ACTOR_ID));
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "BROADCAST_SENDER")
    void failurePageCannotExceedOneHundred() throws Exception {
        when(service.failures(BROADCAST_ID, 1, 101, ACTOR_ID)).thenThrow(new ChatAppBroadcastException(
                "CHATAPP_BROADCAST_PAGE_INVALID", HttpStatus.BAD_REQUEST));

        mvc.perform(get("/api/v1/chatapp/broadcasts/{id}/failures", BROADCAST_ID)
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHATAPP_BROADCAST_PAGE_INVALID"));
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "BROADCAST_SENDER")
    void broadcastSenderCanRequestReadOnlyReconciliation() throws Exception {
        when(service.requestReconciliation(BROADCAST_ID, ACTOR_ID)).thenReturn(view());

        mvc.perform(post("/api/v1/chatapp/broadcasts/{id}/reconcile", BROADCAST_ID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(BROADCAST_ID.toString()));

        verify(service).requestReconciliation(BROADCAST_ID, ACTOR_ID);
    }

    private static String createBody() {
        return """
                {
                  "channelAccountId":"22222222-2222-2222-2222-222222222222",
                  "name":"八月通知",
                  "templateCode":"shipping_notice",
                  "languageCode":"zh_CN",
                  "clientRequestId":"broadcast-1",
                  "recipients":[{
                    "contactIdentityId":"44444444-4444-4444-4444-444444444444",
                    "templateParams":{"order":"SO-1"}
                  }],
                  "sharedTemplateParams":{"company":"CRM"}
                }
                """;
    }

    private static String createBodyWithNullRecipient() {
        return """
                {
                  "channelAccountId":"22222222-2222-2222-2222-222222222222",
                  "name":"八月通知",
                  "templateCode":"shipping_notice",
                  "languageCode":"zh_CN",
                  "clientRequestId":"broadcast-null-recipient",
                  "recipients":[null],
                  "sharedTemplateParams":{"company":"CRM"}
                }
                """;
    }

    private static BroadcastView view() {
        Instant now = Instant.parse("2026-08-14T07:00:00Z");
        return new BroadcastView(
                BROADCAST_ID, ACCOUNT_ID, "八月通知", "shipping_notice", "Shipping Notice",
                "zh_CN", 1, 0, 0, 1, BroadcastStatus.QUEUED, null,
                null, null, null, null, null, null,
                null, ACTOR_ID, null, null, now, now);
    }
}
