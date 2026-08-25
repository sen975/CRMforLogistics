package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.channel.ChannelAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChannelSettingsController.class)
@Import(SecurityConfig.class)
class ChannelSettingsControllerAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean ChannelAccountService channelAccountService;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    @WithMockUser(roles = "AGENT")
    void salesUserCannotAccessAnyChannelSettingsEndpoint() throws Exception {
        UUID accountId = UUID.randomUUID();

        mvc.perform(get("/api/channel-accounts"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/channel-accounts/{id}", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Changed\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/channel-accounts/{id}/sync", accountId))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/channel-accounts/{id}/credentials", accountId))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/channel-accounts/{id}/credentials", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanListChannelAccounts() throws Exception {
        when(channelAccountService.list()).thenReturn(List.of());

        mvc.perform(get("/api/channel-accounts"))
                .andExpect(status().isOk());
    }
}
