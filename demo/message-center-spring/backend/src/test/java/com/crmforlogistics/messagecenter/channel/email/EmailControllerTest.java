package com.crmforlogistics.messagecenter.channel.email;

import com.fasterxml.jackson.databind.ObjectMapper;
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

@WebMvcTest(EmailController.class)
@AutoConfigureMockMvc(addFilters = false)
class EmailControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean EmailSendService sendService;
    @MockitoBean EmailSyncService syncService;

    @Test
    void shouldSendEmail() throws Exception {
        when(sendService.send(eq("to@example.com"), eq("subject"), eq("body")))
                .thenReturn(new EmailSendService.SendResult("msg-1", "from@ex.com",
                        "to@example.com", "subject", "sent"));

        mvc.perform(post("/api/email/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "to", "to@example.com", "subject", "subject", "body", "body"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value("msg-1"));
    }

    @Test
    void shouldTriggerEmailSync() throws Exception {
        when(syncService.receiveLatest())
                .thenReturn(new EmailSyncService.SyncResult("email", 2, 1, 1,
                        "received 1 new email messages"));

        mvc.perform(post("/api/email/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(1));
    }

    @Test
    void shouldReturnBadRequestOnSendError() throws Exception {
        when(sendService.send(any(), any(), any()))
                .thenThrow(new RuntimeException("SMTP connection failed"));

        mvc.perform(post("/api/email/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "to", "bad@ex.com", "subject", "s", "body", "b"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SMTP connection failed"));
    }
}
