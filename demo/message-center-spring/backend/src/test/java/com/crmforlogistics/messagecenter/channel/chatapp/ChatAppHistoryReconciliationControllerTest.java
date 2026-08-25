package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppHistoryReconciliationResult;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessageApplicationService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppTemplateService;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatAppController.class)
@Import(SecurityConfig.class)
class ChatAppHistoryReconciliationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ChatAppSendService sendService;
    @MockitoBean ChatAppMessageApplicationService messageApplicationService;
    @MockitoBean ChatAppMessageSyncService messageSyncService;
    @MockitoBean ChatAppTemplateSyncService templateSyncService;
    @MockitoBean ChatAppTemplateService templateService;
    @MockitoBean EventHub eventHub;
    @MockitoBean AuthSessionService authSessionService;

    private static final UUID ACCOUNT_ID =
            UUID.fromString("d0a7f664-89ee-4658-b7c7-7c05e9a33552");
    private static final Instant START = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    @WithMockUser(roles = "AGENT")
    void ordinaryUserCannotRunHistoryReconciliation() throws Exception {
        mvc.perform(post("/api/chatapp/sync/messages/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(5, true)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanRunBoundedDryRun() throws Exception {
        when(messageSyncService.runAccount(
                eq(ACCOUNT_ID), eq(START), eq(END), eq(5), eq(true)))
                .thenReturn(new ChatAppHistoryReconciliationResult(
                        1, 2, 1, 1, 0, 0, 0, true, 12, List.of()));

        mvc.perform(post("/api/chatapp/sync/messages/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(5, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(2))
                .andExpect(jsonPath("$.moved").value(1))
                .andExpect(jsonPath("$.dryRun").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void invalidPageLimitReturnsBadRequest() throws Exception {
        when(messageSyncService.runAccount(
                eq(ACCOUNT_ID), eq(START), eq(END), eq(51), eq(true)))
                .thenThrow(new IllegalArgumentException("CHATAPP_HISTORY_MAX_PAGES_INVALID"));

        mvc.perform(post("/api/chatapp/sync/messages/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(51, true)))
                .andExpect(status().isBadRequest());
    }

    private static String requestJson(int maxPages, boolean dryRun) {
        return """
                {"accountId":"%s","startTime":"%s","endTime":"%s",\
                "maxPages":%d,"dryRun":%s}
                """.formatted(ACCOUNT_ID, START, END, maxPages, dryRun);
    }
}
