package com.crmforlogistics.messagecenter.channel.chatapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessageApplicationService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppTemplateService;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.crmforlogistics.messagecenter.service.message.MessageSendApplicationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatAppController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChatAppControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean ChatAppSendService sendService;
    @MockitoBean ChatAppMessageApplicationService messageApplicationService;
    @MockitoBean ChatAppMessageSyncService messageSyncService;
    @MockitoBean ChatAppTemplateSyncService templateSyncService;
    @MockitoBean ChatAppTemplateService templateService;
    @MockitoBean EventHub eventHub;

    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actorId.toString(), "", java.util.List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldSendText() throws Exception {
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(messageApplicationService.acceptContactIdentity(
                eq(contactId), eq(identityId), eq("text"), any(), any(), eq(actorId)))
                .thenReturn(new MessageSendApplicationService.MessageAccepted(
                        messageId, "pending", false));

        mvc.perform(post("/api/chatapp/send/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "contactId", contactId,
                                "recipientIdentityId", identityId,
                                "text", "hello"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.status").value("pending"));
        verifyNoInteractions(sendService);
    }

    @Test
    void shouldSendTemplate() throws Exception {
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(messageApplicationService.acceptContactIdentity(
                eq(contactId), eq(identityId), eq("template"), any(), any(), eq(actorId)))
                .thenReturn(new MessageSendApplicationService.MessageAccepted(
                        messageId, "pending", false));

        mvc.perform(post("/api/chatapp/send/template")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "contactId", contactId,
                                "recipientIdentityId", identityId,
                                "templateCode", "tpl-1",
                                "templateName", "greeting", "languageCode", "en"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.status").value("pending"));
        verifyNoInteractions(sendService);
    }

    @Test
    void forbiddenTemplateSendRemainsForbidden() throws Exception {
        when(messageApplicationService.acceptContactIdentity(
                any(), any(), any(), any(), any(), eq(actorId)))
                .thenThrow(new SecurityException("CHATAPP_CONVERSATION_FORBIDDEN"));

        mvc.perform(post("/api/chatapp/send/template")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "contactId", UUID.randomUUID(),
                                "recipientIdentityId", UUID.randomUUID(),
                                "templateCode", "tpl-1"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void shouldTriggerMessageSync() throws Exception {
        when(messageSyncService.runOwnedAccount(actorId))
                .thenReturn(new ChatAppMessageSyncService.SyncResultRecord(2, 10, 5, 3, 150));

        mvc.perform(post("/api/chatapp/sync/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetched").value(10));
        verify(messageSyncService).runOwnedAccount(actorId);
    }

    @Test
    void shouldTriggerTemplateSync() throws Exception {
        when(templateSyncService.runOwnedAccount(actorId))
                .thenReturn(new ChatAppTemplateSyncService.SyncResultRecord(1, 3, 3, 200));

        mvc.perform(post("/api/chatapp/sync/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetched").value(3));
    }
}
