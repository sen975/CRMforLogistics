package com.crmforlogistics.messagecenter.service.channel;

import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppMessageSyncService;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppTemplateSyncService;
import com.crmforlogistics.messagecenter.channel.email.EmailSyncService;
import com.crmforlogistics.messagecenter.dto.response.ChannelAccountSummary;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
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
        ChannelAccountEntity account = account(id, "whatsapp", "60111111111");
        when(mapper.selectById(id)).thenReturn(account);

        assertThatThrownBy(() -> service.update(id, Map.of(
                "name", "Primary WhatsApp",
                "accountIdentifier", "60222222222")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_ACCOUNT_IDENTIFIER_IMMUTABLE");

        verify(mapper, never()).updateAccountIdentifier(any(), any());
        verify(mapper, never()).updateNameAndIdentifier(any(), any(), any());
        verify(mapper, never()).updateName(any(), any());
    }

    @Test
    void allowsRenameOfFixedChatAppAccountWithoutChangingNumber() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        ChannelAccountService service = service(mapper);
        UUID id = UUID.randomUUID();
        ChannelAccountEntity account = account(id, "whatsapp", "60111111111");
        when(mapper.selectById(id)).thenReturn(account);

        ChannelAccountSummary summary = service.update(id, Map.of("name", "Primary WhatsApp"));

        verify(mapper).updateName(id, "Primary WhatsApp");
        assertThat(summary.name()).isEqualTo("Primary WhatsApp");
        assertThat(summary.accountIdentifier()).isEqualTo("60111111111");
    }

    @Test
    void returnsNullWhenAccountMissing() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        ChannelAccountService service = service(mapper);
        UUID id = UUID.randomUUID();
        when(mapper.selectById(id)).thenReturn(null);

        assertThat(service.update(id, Map.of("name", "X"))).isNull();
    }

    @Test
    void manualChatAppSyncUsesTheRequestedAccountForMessagesAndTemplates() throws Exception {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        ChatAppMessageSyncService messageSyncService = mock(ChatAppMessageSyncService.class);
        ChatAppTemplateSyncService templateSyncService = mock(ChatAppTemplateSyncService.class);
        UUID id = UUID.randomUUID();
        ChannelAccountEntity account = account(id, "whatsapp", "60111111111");
        account.setAuthStatus("active");
        when(mapper.selectById(id)).thenReturn(account);
        when(messageSyncService.runAccount(id))
                .thenReturn(new ChatAppMessageSyncService.SyncResultRecord(1, 2, 2, 0, 10));
        when(templateSyncService.runAccount(id))
                .thenReturn(new ChatAppTemplateSyncService.SyncResultRecord(1, 3, 1, 10));
        ChannelAccountService service = new ChannelAccountService(
                mapper, messageSyncService, templateSyncService,
                mock(EmailSyncService.class), mock(CredentialCipher.class));

        service.sync(id);

        verify(messageSyncService).runAccount(id);
        verify(templateSyncService).runAccount(id);
        verify(mapper).updateSyncStatus(eq(id), eq("success"), any());
    }

    @Test
    void maskedSecretSubmittedBySettingsPageKeepsExistingSecret() throws Exception {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        ChannelAccountService service = service(mapper, cipher);
        UUID id = UUID.randomUUID();
        ChannelAccountEntity account = account(id, "email", "inbox@example.test");
        account.setEncryptedConfig("encrypted");
        when(mapper.selectById(id)).thenReturn(account);
        when(cipher.decrypt("encrypted")).thenReturn(Map.of(
                "imapHost", "imap.old.example",
                "imapPassword", "real-password"));
        when(cipher.encrypt(any())).thenReturn("new-encrypted");

        service.updateCredentials(id, Map.of(
                "imapHost", "imap.new.example",
                "imapPassword", "***"));

        var encryptedValues = forClass(Map.class);
        verify(cipher).encrypt(encryptedValues.capture());
        assertThat(encryptedValues.getValue())
                .containsEntry("imapHost", "imap.new.example")
                .containsEntry("imapPassword", "real-password");
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

    private static ChannelAccountEntity account(UUID id, String channelType, String identifier) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(id);
        account.setChannelType(channelType);
        account.setName("WhatsApp");
        account.setAccountIdentifier(identifier);
        return account;
    }
}
