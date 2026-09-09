package com.crmforlogistics.messagecenter.service.channel;

import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppMessageSyncService;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppTemplateSyncService;
import com.crmforlogistics.messagecenter.channel.email.EmailSyncService;
import com.crmforlogistics.messagecenter.dto.response.ChannelAccountSummary;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppProviderScopeService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateException;
import com.crmforlogistics.messagecenter.service.wecom.WeComChatDataSyncService;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelAccountServiceTest {

    @Test
    void rejectsIdentifierChangeForFixedChatAppAccount() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        ChannelAccountService service = service(mapper);
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ChannelAccountEntity account = account(id, "whatsapp", "60111111111");
        account.setOwnerUserId(owner);
        when(mapper.findByIdAndOwner(id, owner)).thenReturn(account);

        assertThatThrownBy(() -> service.update(owner, id, Map.of(
                "name", "Primary WhatsApp",
                "accountIdentifier", "60222222222")))
                .isInstanceOf(ChannelAccountException.class)
                .hasMessage("CHANNEL_ACCOUNT_IDENTIFIER_IMMUTABLE");

        verify(mapper, never()).updateAccountIdentifier(any(), any());
        verify(mapper, never()).updateNameAndIdentifier(any(), any(), any());
        verify(mapper, never()).updateName(any(), any());
    }

    @Test
    void allowsRenameOfFixedChatAppAccountWithoutChangingNumber() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        ChannelAccountService service = service(mapper);
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ChannelAccountEntity account = account(id, "whatsapp", "60111111111");
        account.setOwnerUserId(owner);
        when(mapper.findByIdAndOwner(id, owner)).thenReturn(account);

        ChannelAccountSummary summary = service.update(owner, id, Map.of("name", "Primary WhatsApp"));

        verify(mapper).updateNameOwned(owner, id, "Primary WhatsApp");
        assertThat(summary.name()).isEqualTo("Primary WhatsApp");
        assertThat(summary.accountIdentifier()).isEqualTo("60111111111");
    }

    @Test
    void returnsNullWhenAccountMissing() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        ChannelAccountService service = service(mapper);
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(mapper.findByIdAndOwner(id, owner)).thenReturn(null);

        assertThatThrownBy(() -> service.update(owner, id, Map.of("name", "X")))
                .isInstanceOf(ChannelAccountException.class)
                .hasMessage("RESOURCE_NOT_FOUND");
    }

    @Test
    void manualChatAppSyncUsesTheRequestedAccountForMessagesAndTemplates() throws Exception {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        ChatAppMessageSyncService messageSyncService = mock(ChatAppMessageSyncService.class);
        ChatAppTemplateSyncService templateSyncService = mock(ChatAppTemplateSyncService.class);
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ChannelAccountEntity account = account(id, "whatsapp", "60111111111");
        account.setOwnerUserId(owner);
        account.setAuthStatus("active");
        when(mapper.findByIdAndOwner(id, owner)).thenReturn(account);
        when(messageSyncService.runAccount(id))
                .thenReturn(new ChatAppMessageSyncService.SyncResultRecord(1, 2, 2, 0, 10));
        when(templateSyncService.runAccount(id))
                .thenReturn(new ChatAppTemplateSyncService.SyncResultRecord(1, 3, 1, 10));
        ChannelAccountService service = new ChannelAccountService(
                mapper, messageSyncService, templateSyncService,
                mock(EmailSyncService.class), mock(CredentialCipher.class));

        service.sync(owner, id);

        verify(messageSyncService).runAccount(id);
        verify(templateSyncService).runAccount(id);
        verify(mapper).updateSyncStatusOwned(eq(owner), eq(id), eq("success"), any());
    }

    @Test
    void maskedSecretSubmittedBySettingsPageKeepsExistingSecret() throws Exception {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        ChannelAccountService service = service(mapper, cipher);
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ChannelAccountEntity account = account(id, "email", "inbox@example.test");
        account.setOwnerUserId(owner);
        account.setAuthStatus("active");
        account.setEncryptedConfig("encrypted");
        when(mapper.findByIdAndOwner(id, owner)).thenReturn(account);
        when(cipher.decrypt("encrypted")).thenReturn(Map.of(
                "imapHost", "imap.old.example",
                "imapPassword", "real-password"));
        when(cipher.encrypt(any())).thenReturn("new-encrypted");

        service.updateCredentials(owner, id, Map.of(
                "imapHost", "imap.new.example",
                "imapPassword", "***"));

        var encryptedValues = forClass(Map.class);
        verify(cipher).encrypt(encryptedValues.capture());
        assertThat(encryptedValues.getValue())
                .containsEntry("imapHost", "imap.new.example")
                .containsEntry("imapPassword", "real-password");
    }

    @Test
    void rejectsGenericWhatsAppBindingOutsideTheOnboardingServices() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        WhatsAppProviderScopeService scopeService = mock(WhatsAppProviderScopeService.class);
        UUID owner = UUID.randomUUID();
        ChannelAccountService service = service(mapper, cipher, scopeService);

        assertThatThrownBy(() -> service.createOrBind(owner,
                new com.crmforlogistics.messagecenter.dto.request.CreateChannelAccountRequest(
                        "whatsapp", "Primary WhatsApp", "60111111111", Map.of())))
                .isInstanceOf(ChannelAccountException.class)
                .hasMessage("WHATSAPP_ONBOARDING_REQUIRED");

        verify(mapper, never()).insertOwned(any(), any());
        verify(scopeService, never()).assertCompatible(any());
    }

    @Test
    void rejectsCreatingActiveChatAppAccountWithoutAllCredentials() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        UUID owner = UUID.randomUUID();
        when(mapper.countActiveByOwnerAndChannel(owner, "chatapp")).thenReturn(0);
        ChannelAccountService service = service(mapper, cipher);

        assertThatThrownBy(() -> service.createOrBind(owner,
                new com.crmforlogistics.messagecenter.dto.request.CreateChannelAccountRequest(
                        "whatsapp", "Primary WhatsApp", "60111111111", Map.of(
                        "accessKeyId", "key-id",
                        "accessKeySecret", "key-secret",
                        "region", "ap-southeast-1",
                        "custSpaceId", "space-1",
                        "chatappFrom", "60111111111"))))
                .isInstanceOf(ChannelAccountException.class)
                .hasMessage("CHANNEL_ACCOUNT_INCOMPLETE_CREDENTIALS");

        verify(mapper, never()).insertOwned(any(), any());
    }

    @Test
    void credentialUpdateCannotCrossProviderScope() throws Exception {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        WhatsAppProviderScopeService scopeService = mock(WhatsAppProviderScopeService.class);
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        ChannelAccountEntity account = account(id, "chatapp", "60111111111");
        account.setOwnerUserId(owner);
        account.setAuthStatus("active");
        account.setEncryptedConfig("old-encrypted");
        when(mapper.findByIdAndOwner(id, owner)).thenReturn(account);
        when(cipher.decrypt("old-encrypted")).thenReturn(Map.of(
                "accessKeyId", "old-key",
                "accessKeySecret", "old-secret",
                "custSpaceId", "space-1",
                "chatappFrom", "60111111111"));
        org.mockito.Mockito.doThrow(new WhatsAppTemplateException(
                        "WHATSAPP_PROVIDER_SCOPE_MISMATCH", HttpStatus.CONFLICT,
                        "WhatsApp provider scope mismatch", Map.of(), null, false))
                .when(scopeService).assertCompatible("space-2");
        ChannelAccountService service = service(mapper, cipher, scopeService);

        assertThatThrownBy(() -> service.updateCredentials(owner, id, Map.of("custSpaceId", "space-2")))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class,
                        error -> assertThat(error.code()).isEqualTo("WHATSAPP_PROVIDER_SCOPE_MISMATCH"));
        verify(mapper, never()).updateEncryptedConfigOwned(any(), any(), any());
    }

    private static ChannelAccountService service(ChannelAccountMapper mapper) {
        return service(mapper, mock(CredentialCipher.class));
    }

    private static ChannelAccountService service(ChannelAccountMapper mapper, CredentialCipher cipher) {
        return new ChannelAccountService(mapper,
                mock(ChatAppMessageSyncService.class),
                mock(ChatAppTemplateSyncService.class),
                mock(EmailSyncService.class),
                cipher);
    }

    private static ChannelAccountService service(ChannelAccountMapper mapper, CredentialCipher cipher,
                                                 WhatsAppProviderScopeService scopeService) {
        return new ChannelAccountService(mapper,
                mock(ChatAppMessageSyncService.class),
                mock(ChatAppTemplateSyncService.class),
                mock(EmailSyncService.class),
                cipher,
                (WeComChatDataSyncService) null,
                scopeService);
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
