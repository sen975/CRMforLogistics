package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.dto.response.ChannelAccountSummary;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.channel.ChannelAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChannelSettingsController.class)
@Import(SecurityConfig.class)
class ChannelAccountControllerOwnerSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean ChannelAccountService channelAccountService;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    void authenticatedAgentCanReadOwnAccounts() throws Exception {
        UUID owner = UUID.randomUUID();
        when(channelAccountService.list(owner)).thenReturn(List.of());

        mvc.perform(get("/api/channel-accounts")
                        .with(user(owner.toString()).roles("AGENT")))
                .andExpect(status().isOk());
        verify(channelAccountService).list(owner);
    }

    @Test
    void createCannotOverrideAuthenticatedOwnerFromRequestBody() throws Exception {
        UUID owner = UUID.randomUUID();
        when(channelAccountService.createOrBind(eq(owner), any())).thenReturn(null);

        mvc.perform(post("/api/channel-accounts")
                        .with(user(owner.toString()).roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelType\":\"email\",\"name\":\"Inbox\",\"accountIdentifier\":\"a@example.test\",\"ownerUserId\":\"00000000-0000-0000-0000-000000000001\"}"))
                .andExpect(status().isCreated());
        verify(channelAccountService).createOrBind(eq(owner), any());
    }
}
