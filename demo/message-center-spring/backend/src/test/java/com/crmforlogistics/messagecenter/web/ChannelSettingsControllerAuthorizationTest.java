package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppMessageSyncService;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppTemplateSyncService;
import com.crmforlogistics.messagecenter.channel.email.EmailSyncService;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChannelSettingsController.class)
@Import(SecurityConfig.class)
class ChannelSettingsControllerAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean ChannelAccountMapper channelAccountMapper;
    @MockitoBean ChatAppMessageSyncService chatAppMessageSyncService;
    @MockitoBean ChatAppTemplateSyncService chatAppTemplateSyncService;
    @MockitoBean EmailSyncService emailSyncService;
    @MockitoBean CredentialCipher credentialCipher;
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
        when(channelAccountMapper.selectList(any())).thenReturn(List.of());

        mvc.perform(get("/api/channel-accounts"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"chatapp", "whatsapp"})
    @WithMockUser(roles = "ADMIN")
    void adminCannotChangeFixedChatAppNumberThroughSettings(String channelType) throws Exception {
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = account(accountId, channelType, "60111111111");
        when(channelAccountMapper.selectById(accountId)).thenReturn(account);

        mvc.perform(put("/api/channel-accounts/{id}", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Primary WhatsApp\",\"accountIdentifier\":\"60222222222\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("CHATAPP_ACCOUNT_IDENTIFIER_IMMUTABLE"));

        verify(channelAccountMapper, never()).updateAccountIdentifier(any(), any());
        verify(channelAccountMapper, never()).updateNameAndIdentifier(any(), any(), any());
        verify(channelAccountMapper, never()).updateName(any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanRenameFixedChatAppAccountWithoutChangingNumber() throws Exception {
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = account(accountId, "chatapp", "60111111111");
        when(channelAccountMapper.selectById(accountId)).thenReturn(account);

        mvc.perform(put("/api/channel-accounts/{id}", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Primary WhatsApp\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Primary WhatsApp"))
                .andExpect(jsonPath("$.accountIdentifier").value("60111111111"));

        verify(channelAccountMapper).updateName(accountId, "Primary WhatsApp");
    }

    private static ChannelAccountEntity account(UUID id, String channelType, String identifier) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(id);
        account.setChannelType(channelType);
        account.setName("WhatsApp");
        account.setAccountIdentifier(identifier);
        return account;
    }
}
