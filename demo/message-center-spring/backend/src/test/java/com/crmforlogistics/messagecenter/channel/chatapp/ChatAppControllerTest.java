package com.crmforlogistics.messagecenter.channel.chatapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @MockitoBean ChatAppMessageSyncService messageSyncService;
    @MockitoBean ChatAppTemplateSyncService templateSyncService;
    @MockitoBean ChatAppTemplateService templateService;

    @Test
    void shouldSendText() throws Exception {
        when(sendService.sendText(eq("8612345678"), eq("hello"), any()))
                .thenReturn(new ChatAppSendService.SendResult("msg-1", "from", "to", "hello", "Submitted"));

        mvc.perform(post("/api/chatapp/send/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("to", "8612345678", "text", "hello"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value("msg-1"));
    }

    @Test
    void shouldSendTemplate() throws Exception {
        when(sendService.sendTemplate(eq("8612345678"), eq("tpl-1"), eq("greeting"), eq("en"), any(), any()))
                .thenReturn(new ChatAppSendService.SendResult("msg-2", "from", "to", "text", "Submitted"));

        mvc.perform(post("/api/chatapp/send/template")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "to", "8612345678", "templateCode", "tpl-1",
                                "templateName", "greeting", "languageCode", "en"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value("msg-2"));
    }

    @Test
    void shouldTriggerMessageSync() throws Exception {
        when(messageSyncService.runOnce())
                .thenReturn(new ChatAppMessageSyncService.SyncResultRecord(2, 10, 5, 150));

        mvc.perform(post("/api/chatapp/sync/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetched").value(10));
    }

    @Test
    void shouldTriggerTemplateSync() throws Exception {
        when(templateSyncService.runOnce())
                .thenReturn(new ChatAppTemplateSyncService.SyncResultRecord(1, 3, 3, 200));

        mvc.perform(post("/api/chatapp/sync/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetched").value(3));
    }
}
