package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.email.EmailException;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.dto.response.ChannelAccountSummary;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.channel.ChannelAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChannelSettingsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ChannelSettingsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ChannelAccountService channelAccountService;
    @MockitoBean AuthSessionService authSessionService;
    @MockitoBean CredentialCipher credentialCipher;

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000005", roles = "AGENT")
    void syncPreservesStructuredEmailAuthenticationFailure() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(channelAccountService.sync(any(), eq(accountId))).thenThrow(new EmailException(
                "EMAIL_IMAP_AUTHENTICATION_FAILED", "IMAP authentication failed"));

        mvc.perform(post("/api/channel-accounts/{id}/sync", accountId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_IMAP_AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("IMAP authentication failed"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }
}
