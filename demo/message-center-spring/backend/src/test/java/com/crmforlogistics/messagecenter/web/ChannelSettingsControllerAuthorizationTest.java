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
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChannelSettingsController.class)
@Import(SecurityConfig.class)
class ChannelSettingsControllerAuthorizationTest {
    private static final String USER_ID = "00000000-0000-0000-0000-000000000005";
    @Autowired MockMvc mvc;
    @MockitoBean ChannelAccountService channelAccountService;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    @WithMockUser(username = USER_ID, roles = "AGENT")
    void salesUserCanAccessSelfManagedChannelSettingsEndpoints() throws Exception {
        UUID accountId = UUID.randomUUID();

        mvc.perform(get("/api/channel-accounts"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/channel-accounts/{id}", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Changed\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/channel-accounts/{id}/sync", accountId))
                .andExpect(status().isOk());
        mvc.perform(get("/api/channel-accounts/{id}/credentials", accountId))
                .andExpect(status().isOk());
        mvc.perform(put("/api/channel-accounts/{id}/credentials", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = USER_ID, roles = "ADMIN")
    void adminCanListChannelAccounts() throws Exception {
        when(channelAccountService.list(any())).thenReturn(List.of());

        mvc.perform(get("/api/channel-accounts"))
                .andExpect(status().isOk());
    }
}
