package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.email.EmailSendService;
import com.crmforlogistics.messagecenter.channel.email.EmailException;
import com.crmforlogistics.messagecenter.dto.response.ChannelCapabilityResponse;
import com.crmforlogistics.messagecenter.dto.response.MessageResponse;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMediaApplicationService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessageApplicationService;
import com.crmforlogistics.messagecenter.service.message.MessageQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MessageController.class)
@AutoConfigureMockMvc(addFilters = false)
class MessageControllerAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean MessageQueryService messageQueryService;
    @MockitoBean ChatAppMessageApplicationService chatAppMessageApplicationService;
    @MockitoBean ChatAppMediaApplicationService chatAppMediaApplicationService;
    @MockitoBean EmailSendService emailSendService;

    private UUID userId;

    @BeforeEach
    void authenticate() {
        userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId.toString(), "", java.util.List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void forbiddenMediaSendRemainsForbidden() throws Exception {
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        when(chatAppMediaApplicationService.accept(
                eq(contactId), eq(identityId), eq("image"), any(byte[].class),
                any(), any(), any(), any(), eq(userId)))
                .thenThrow(new SecurityException("CHATAPP_CONVERSATION_FORBIDDEN"));

        mvc.perform(multipart("/api/send/chatapp-media")
                        .file("file", "image".getBytes())
                        .param("contactId", contactId.toString())
                        .param("recipientIdentityId", identityId.toString())
                        .param("mediaType", "image"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void legacyChatAppEndpointUsesContactBoundIdentityAuthorization() throws Exception {
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        when(chatAppMessageApplicationService.acceptContactIdentity(
                eq(contactId), eq(identityId), eq("text"), any(), any(), eq(userId)))
                .thenThrow(new SecurityException("CHATAPP_CONVERSATION_FORBIDDEN"));

        mvc.perform(post("/api/send/chatapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper()
                                .writeValueAsString(Map.of(
                                        "contactId", contactId,
                                        "recipientIdentityId", identityId,
                                        "mode", "text",
                                        "text", "hello"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void messageBodyIsNotReturnedWhenConversationIsForbidden() throws Exception {
        UUID messageId = UUID.randomUUID();
        when(messageQueryService.getMessage(messageId, userId))
                .thenThrow(new SecurityException("CHATAPP_CONVERSATION_FORBIDDEN"));

        mvc.perform(get("/api/messages/{id}", messageId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void messageOwnerCanReadBody() throws Exception {
        UUID messageId = UUID.randomUUID();
        MessageResponse response = new MessageResponse(
                messageId, "provider-message-id", "inbound", "text", null, "owner-visible", null,
                "chatapp", null, null, null, "delivered", 0, List.of());
        when(messageQueryService.getMessage(messageId, userId)).thenReturn(response);

        mvc.perform(get("/api/messages/{id}", messageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bodyText").value("owner-visible"));

        verify(messageQueryService).getMessage(messageId, userId);
    }

    @Test
    void missingMessageRemainsNotFound() throws Exception {
        UUID messageId = UUID.randomUUID();
        when(messageQueryService.getMessage(messageId, userId)).thenReturn(null);

        mvc.perform(get("/api/messages/{id}", messageId))
                .andExpect(status().isNotFound());
    }

    @Test
    void jsonEmailSendPreservesUnknownDeliveryAsServiceUnavailable() throws Exception {
        when(emailSendService.send(eq(userId), eq("to@example.test"), eq("subject"), eq("body")))
                .thenThrow(new EmailException("EMAIL_SEND_OUTCOME_UNKNOWN", "Check delivery before retrying"));

        mvc.perform(post("/api/v1/email/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper()
                                .writeValueAsString(Map.of("to", "to@example.test",
                                        "subject", "subject", "body", "body"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMAIL_SEND_OUTCOME_UNKNOWN"));
    }

    @Test
    void loggedInUserCanReadChannelCapabilityAccountId() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(messageQueryService.channelCapabilities()).thenReturn(List.of(
                new ChannelCapabilityResponse("chatapp", accountId, "CAMS 一号账号", "active")));

        mvc.perform(get("/api/channel-capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].channelType").value("chatapp"))
                .andExpect(jsonPath("$[0].channelAccountId").value(accountId.toString()));
    }
}
