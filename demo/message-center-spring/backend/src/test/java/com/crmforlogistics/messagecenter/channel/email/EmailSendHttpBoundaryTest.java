package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.web.GlobalExceptionHandler;
import com.crmforlogistics.messagecenter.web.MessageController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EmailSendHttpBoundaryTest {
    private final EmailSendService sends = mock(EmailSendService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new EmailController(sends, mock(EmailSyncService.class)),
                    new MessageController(null, null, null, sends))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    private final UUID owner = UUID.randomUUID();

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/email/send", "/api/send/email", "/api/v1/email/messages"})
    void jsonRequiresAuthenticationWithoutCallingSend(String route) throws Exception {
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"person@example.test\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(sends);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/email/send", "/api/send/email", "/api/v1/email/messages"})
    void multipartRequiresAuthenticationWithoutCallingSend(String route) throws Exception {
        mvc.perform(multipart(route).file("file", new byte[]{1}).param("to", "person@example.test"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(sends);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/email/send", "/api/send/email", "/api/v1/email/messages"})
    void jsonPreservesOwnerAndRecipientError(String route) throws Exception {
        authenticate();
        when(sends.send(owner, "person@example.test", "", ""))
                .thenThrow(new EmailException("EMAIL_RECIPIENT_NOT_FOUND", "Recipient not found"));
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"person@example.test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_RECIPIENT_NOT_FOUND"));
        verify(sends).send(owner, "person@example.test", "", "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/email/send", "/api/send/email", "/api/v1/email/messages"})
    void multipartPreservesOwnerAndRecipientError(String route) throws Exception {
        authenticate();
        when(sends.send(eq(owner), eq("person@example.test"), eq(""), eq(""), anyList()))
                .thenThrow(new EmailException("EMAIL_RECIPIENT_NOT_FOUND", "Recipient not found"));
        mvc.perform(multipart(route).file("file", new byte[]{1}).param("to", "person@example.test"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_RECIPIENT_NOT_FOUND"));
        verify(sends).send(eq(owner), eq("person@example.test"), eq(""), eq(""), anyList());
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner.toString(), "", List.of()));
    }
}
