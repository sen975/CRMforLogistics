package com.crmforlogistics.messagecenter.service.channel;

import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppMessageSyncService;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppTemplateSyncService;
import com.crmforlogistics.messagecenter.channel.email.EmailSyncService;
import com.crmforlogistics.messagecenter.dto.request.CreateChannelAccountRequest;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppProviderScopeService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelAccountOwnerIsolationTest {

    @Test
    void listingUsesTheAuthenticatedOwner() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        UUID owner = UUID.randomUUID();
        when(mapper.findAllByOwner(owner)).thenReturn(List.of());
        ChannelAccountService service = service(mapper);

        assertThat(service.list(owner)).isEmpty();
        verify(mapper).findAllByOwner(owner);
    }

    @Test
    void anotherOwnersAccountCannotBeUpdatedOrSynced() throws Exception {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        UUID owner = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(mapper.findByIdAndOwner(accountId, owner)).thenReturn(null);
        ChannelAccountService service = service(mapper);

        assertThatThrownBy(() -> service.update(owner, accountId, java.util.Map.of("name", "changed")))
                .isInstanceOf(ChannelAccountException.class).hasMessage("RESOURCE_NOT_FOUND");
        assertThatThrownBy(() -> service.getCredentials(owner, accountId))
                .isInstanceOf(ChannelAccountException.class).hasMessage("RESOURCE_NOT_FOUND");
        assertThatThrownBy(() -> service.sync(owner, accountId))
                .isInstanceOf(ChannelAccountException.class).hasMessage("RESOURCE_NOT_FOUND");
        verify(mapper, never()).updateName(any(), any());
    }

    @Test
    void onlyOneActiveAccountPerOwnerAndChannelCanBeBound() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        UUID owner = UUID.randomUUID();
        when(mapper.countActiveByOwnerAndChannel(owner, "email")).thenReturn(1);
        ChannelAccountService service = service(mapper);

        var request = new CreateChannelAccountRequest("email", "Inbox", "a@example.test", null);
        assertThatThrownBy(() -> service.createOrBind(owner, request))
                .isInstanceOf(ChannelAccountException.class)
                .hasMessage("CHANNEL_ACCOUNT_ALREADY_EXISTS");
        verify(mapper, never()).insertOwned(any(), eq(owner));
    }

    @Test
    void unbindingOwnedAccountAllowsBindingAnotherAccount() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        UUID owner = UUID.randomUUID();
        UUID oldId = UUID.randomUUID();
        ChannelAccountEntity old = account(oldId, owner, "email", "old@example.test");
        when(mapper.findByIdAndOwner(oldId, owner)).thenReturn(old);
        when(mapper.disableOwned(owner, oldId)).thenReturn(1);
        when(mapper.countActiveByOwnerAndChannel(owner, "email")).thenReturn(0);
        when(mapper.insertOwned(any(), eq(owner))).thenReturn(1);
        ChannelAccountService service = service(mapper);

        assertThat(service.unbind(owner, oldId)).isTrue();
        assertThat(service.createOrBind(owner,
                new CreateChannelAccountRequest("email", "New", "new@example.test", emailCredentials()))).isNotNull();
        verify(mapper).disableOwned(owner, oldId);
        verify(mapper).insertOwned(any(), eq(owner));
    }

    private static ChannelAccountService service(ChannelAccountMapper mapper) {
        return new ChannelAccountService(mapper, registry(), mock(CredentialCipher.class));
    }

    private static ChannelTypeRegistry registry() {
        return new ChannelTypeRegistry(List.of(
                new EmailChannelType(mock(EmailSyncService.class)),
                new ChatAppChannelType(mock(ChatAppMessageSyncService.class),
                        mock(ChatAppTemplateSyncService.class), mock(WhatsAppProviderScopeService.class))));
    }

    private static ChannelAccountEntity account(UUID id, UUID owner, String type, String identifier) {
        ChannelAccountEntity e = new ChannelAccountEntity();
        e.setId(id);
        e.setOwnerUserId(owner);
        e.setChannelType(type);
        e.setName(type);
        e.setAccountIdentifier(identifier);
        e.setAuthStatus("active");
        e.setSyncStatus("idle");
        return e;
    }

    private static java.util.Map<String, String> emailCredentials() {
        return java.util.Map.ofEntries(
                java.util.Map.entry("smtpHost", "smtp.example.test"),
                java.util.Map.entry("smtpPort", "465"),
                java.util.Map.entry("smtpSsl", "true"),
                java.util.Map.entry("smtpUser", "sender@example.test"),
                java.util.Map.entry("smtpPassword", "test-password"),
                java.util.Map.entry("imapHost", "imap.example.test"),
                java.util.Map.entry("imapPort", "993"),
                java.util.Map.entry("imapSsl", "true"),
                java.util.Map.entry("imapUser", "sender@example.test"),
                java.util.Map.entry("imapPassword", "test-password"),
                java.util.Map.entry("provider", "imap"),
                java.util.Map.entry("mailFrom", "sender@example.test"));
    }
}
