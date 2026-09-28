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
import java.util.UUID;
import org.springframework.security.test.context.support.WithMockUser;
import com.crmforlogistics.messagecenter.entity.EmailSubmissionEntity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({EmailController.class, EmailSubmissionController.class})
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
    void shouldExposeStructuredImapAuthenticationFailureWithoutCredentialDetails() throws Exception {
        when(syncService.receiveLatest()).thenThrow(new EmailException(
                "EMAIL_IMAP_AUTHENTICATION_FAILED", "IMAP authentication failed"));

        mvc.perform(post("/api/email/sync"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_IMAP_AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("IMAP authentication failed"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
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

    @Test
    void unknownSmtpOutcomeUsesRetryCautiousServiceUnavailableResponse() throws Exception {
        when(sendService.send(any(), any(), any())).thenThrow(new EmailException(
                "EMAIL_SEND_OUTCOME_UNKNOWN", "Check delivery before retrying"));

        mvc.perform(post("/api/email/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "to", "to@example.com", "subject", "subject", "body", "body"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMAIL_SEND_OUTCOME_UNKNOWN"));
    }

    @Test
    void unknownSubmissionListRequiresAnAuthenticatedOwner() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/email/submissions/unknown"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "8f4d4703-b805-48f4-9dc2-4cf646754c0a")
    void unknownSubmissionListIsOwnerScopedAndBounded() throws Exception {
        EmailSubmissionEntity row = new EmailSubmissionEntity();
        row.setId(UUID.randomUUID());
        row.setRecipient("recipient@example.test");
        row.setStatus("UNKNOWN");
        when(sendService.listUnknownSubmissions(UUID.fromString("8f4d4703-b805-48f4-9dc2-4cf646754c0a"), 100))
                .thenReturn(java.util.List.of(new EmailSendService.SubmissionOutcome(
                        row.getId(), null, row.getRecipient(), "subject", row.getStatus(), null)));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/email/submissions/unknown?limit=1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("UNKNOWN"))
                .andExpect(jsonPath("$[0].recipient").value("recipient@example.test"));
    }
}
