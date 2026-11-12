package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsResolver;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppCallbackAuditMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppCallbackConfigMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppCallbackAuditEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppCallbackConfigEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentials;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WhatsAppCallbackConfigServiceTest {
    @Test
    void rejectsNonHttpsBeforeResolvingCredentialsOrCallingCams() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppCallbackConfigMapper configs = mock(WhatsAppCallbackConfigMapper.class);
        WhatsAppCallbackAuditMapper audits = mock(WhatsAppCallbackAuditMapper.class);
        ChatAppAccountCredentialsResolver credentials = mock(ChatAppAccountCredentialsResolver.class);
        WhatsAppCallbackGateway gateway = mock(WhatsAppCallbackGateway.class);
        WhatsAppCallbackConfigService service = new WhatsAppCallbackConfigService(
                scopes, accounts, configs, audits, credentials, gateway);

        assertThatThrownBy(() -> service.applyPhone(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new WhatsAppCallbackConfigService.PhoneRequest(
                        "http://attacker.example/callback", "https://crm.example/status", "Y", "N", 0)))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_CALLBACK_URL_INVALID");
        verifyNoInteractions(scopes, accounts, configs, audits, credentials, gateway);
    }

    @Test
    void providerTimeoutIsPersistedAsSubmissionUnknown() {
        UUID scopeId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppCallbackConfigMapper configs = mock(WhatsAppCallbackConfigMapper.class);
        WhatsAppCallbackAuditMapper audits = mock(WhatsAppCallbackAuditMapper.class);
        ChatAppAccountCredentialsResolver credentials = mock(ChatAppAccountCredentialsResolver.class);
        WhatsAppCallbackGateway gateway = mock(WhatsAppCallbackGateway.class);
        WhatsAppProviderScopeEntity scope = scope(scopeId);
        ChannelAccountEntity account = account(scopeId, accountId);
        WhatsAppCallbackConfigEntity config = config(configId, scopeId, accountId, 3);
        when(scopes.findAdminScopeById(scopeId)).thenReturn(scope);
        when(accounts.selectById(accountId)).thenReturn(account);
        when(configs.findPhone(scopeId, accountId)).thenReturn(config);
        when(configs.claimApply(eq(configId), eq(3L), any(UUID.class))).thenReturn(1);
        when(configs.updateApplyUnknown(eq(configId), eq(4L), isNull(String.class),
                eq("WHATSAPP_CALLBACK_PROVIDER_TIMEOUT"), any(UUID.class))).thenReturn(1);
        when(credentials.resolveSpace(scope)).thenReturn(new ChatAppAccountCredentials(
                "key", "secret", "space", "phone", "region", "endpoint"));
        when(gateway.updatePhone(any(), any())).thenThrow(
                new WhatsAppAuthorizationException("WHATSAPP_CALLBACK_PROVIDER_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT));
        WhatsAppCallbackConfigService service = new WhatsAppCallbackConfigService(
                scopes, accounts, configs, audits, credentials, gateway);

        assertThatThrownBy(() -> service.applyPhone(UUID.randomUUID(), scopeId, accountId,
                new WhatsAppCallbackConfigService.PhoneRequest(
                        "https://crm.example/up", "https://crm.example/status", "Y", "N", 3)))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_CALLBACK_PROVIDER_TIMEOUT");

        verify(configs).updateApplyUnknown(eq(configId), eq(4L), isNull(String.class),
                eq("WHATSAPP_CALLBACK_PROVIDER_TIMEOUT"), any(UUID.class));
        verify(audits).insert(any(WhatsAppCallbackAuditEntity.class));
    }

    @Test
    void providerSuccessWithLostCasIsPersistedAsSubmissionUnknown() {
        UUID scopeId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppCallbackConfigMapper configs = mock(WhatsAppCallbackConfigMapper.class);
        WhatsAppCallbackAuditMapper audits = mock(WhatsAppCallbackAuditMapper.class);
        ChatAppAccountCredentialsResolver credentials = mock(ChatAppAccountCredentialsResolver.class);
        WhatsAppCallbackGateway gateway = mock(WhatsAppCallbackGateway.class);
        WhatsAppProviderScopeEntity scope = scope(scopeId);
        ChannelAccountEntity account = account(scopeId, accountId);
        WhatsAppCallbackConfigEntity config = config(configId, scopeId, accountId, 5);
        when(scopes.findAdminScopeById(scopeId)).thenReturn(scope);
        when(accounts.selectById(accountId)).thenReturn(account);
        when(configs.findPhone(scopeId, accountId)).thenReturn(config);
        when(configs.claimApply(eq(configId), eq(5L), any(UUID.class))).thenReturn(1);
        when(credentials.resolveSpace(scope)).thenReturn(new ChatAppAccountCredentials(
                "key", "secret", "space", "phone", "region", "endpoint"));
        when(gateway.updatePhone(any(), any())).thenReturn(new WhatsAppCallbackGateway.ProviderApplyResult("req-1"));
        when(configs.updatePhoneDesiredAndApplySuccess(eq(configId), eq(6L), any(), any(), eq("Y"), eq("N"),
                eq("req-1"), any(UUID.class))).thenReturn(0);
        when(configs.updateApplyUnknown(eq(configId), eq(6L), eq("req-1"),
                eq("WHATSAPP_CALLBACK_VERSION_CONFLICT"), any(UUID.class))).thenReturn(1);
        WhatsAppCallbackConfigService service = new WhatsAppCallbackConfigService(
                scopes, accounts, configs, audits, credentials, gateway);

        assertThatThrownBy(() -> service.applyPhone(UUID.randomUUID(), scopeId, accountId,
                new WhatsAppCallbackConfigService.PhoneRequest(
                        "https://crm.example/up", "https://crm.example/status", "Y", "N", 5)))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_CALLBACK_VERSION_CONFLICT");

        verify(configs).updateApplyUnknown(eq(configId), eq(6L), eq("req-1"),
                eq("WHATSAPP_CALLBACK_VERSION_CONFLICT"), any(UUID.class));
    }

    private static WhatsAppProviderScopeEntity scope(UUID id) {
        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setId(id);
        scope.setProvider("ALIYUN_CAMS");
        scope.setStatus("READY");
        scope.setExternalScopeId("space-1");
        return scope;
    }

    private static ChannelAccountEntity account(UUID scopeId, UUID id) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(id);
        account.setProviderScopeId(scopeId);
        account.setChannelType("chatapp");
        account.setAccountIdentifier("8613800001234");
        account.setAccountIdentifierNormalized("8613800001234");
        return account;
    }

    private static WhatsAppCallbackConfigEntity config(UUID id, UUID scopeId, UUID accountId, long version) {
        WhatsAppCallbackConfigEntity config = new WhatsAppCallbackConfigEntity();
        config.setId(id);
        config.setProviderScopeId(scopeId);
        config.setChannelAccountId(accountId);
        config.setVersion(version);
        return config;
    }
}
