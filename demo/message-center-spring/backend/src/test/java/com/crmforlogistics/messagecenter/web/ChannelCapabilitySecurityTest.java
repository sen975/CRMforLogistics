package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.email.EmailSendService;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.dto.response.ChannelCapabilityResponse;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.channel.ChannelAccountService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMediaApplicationService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessageApplicationService;
import com.crmforlogistics.messagecenter.service.message.MessageQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({MessageController.class, ChannelSettingsController.class})
@Import(SecurityConfig.class)
class ChannelCapabilitySecurityTest {
    private static final String AGENT_ID = "00000000-0000-0000-0000-000000000005";

    @Autowired MockMvc mvc;
    @MockitoBean MessageQueryService messageQueryService;
    @MockitoBean ChatAppMessageApplicationService chatAppMessageApplicationService;
    @MockitoBean ChatAppMediaApplicationService chatAppMediaApplicationService;
    @MockitoBean EmailSendService emailSendService;
    @MockitoBean ChannelAccountService channelAccountService;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    @WithMockUser(username = AGENT_ID, roles = "AGENT")
    void agentCanReadCapabilitiesButCannotReadAdminChannelAccounts() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(messageQueryService.channelCapabilities()).thenReturn(List.of(
                new ChannelCapabilityResponse("chatapp", accountId, "CAMS 一号账号", "active")));

        mvc.perform(get("/api/channel-capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].channelAccountId").value(accountId.toString()));

        mvc.perform(get("/api/channel-accounts"))
                .andExpect(status().isForbidden());
    }
}
