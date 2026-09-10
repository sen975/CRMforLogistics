package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicActivityRecorder;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailOwnerIsolationTest {

    @Mock AppConfig config;
    @Mock MessageMapper messageMapper;
    @Mock ConversationMapper conversationMapper;
    @Mock ChannelAccountMapper channelAccountMapper;
    @Mock ContactIdentityMapper contactIdentityMapper;
    @Mock ContactMapper contactMapper;
    @Mock EventHub eventHub;
    @Mock CredentialCipher credentialCipher;
    @Mock EmailAttachmentStore attachmentStore;
    @Mock AiTopicActivityRecorder topicActivityRecorder;

    @Test
    void resolveSettingsUsesTheRequestedOwnedAccount() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = account(accountId, ownerId, "owner@example.test", "encrypted-owner");
        when(channelAccountMapper.findByIdAndOwner(accountId, ownerId)).thenReturn(account);
        when(credentialCipher.decrypt("encrypted-owner")).thenReturn(Map.of(
                "imapHost", "imap.owner.test", "imapUser", "owner@example.test", "imapPassword", "secret"));

        EmailSyncService service = service();

        EmailSyncSettings settings = service.resolveSettings(accountId, ownerId);

        assertEquals("imap.owner.test", settings.imapHost());
        assertEquals("owner@example.test", settings.imapUser());
        verify(channelAccountMapper).findByIdAndOwner(accountId, ownerId);
    }

    @Test
    void mismatchedOwnerCannotResolveEmailAccount() {
        UUID ownerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(channelAccountMapper.findByIdAndOwner(accountId, ownerId)).thenReturn(null);

        EmailSyncService service = service();

        assertThrows(EmailException.class, () -> service.resolveSettings(accountId, ownerId));
    }

    @Test
    void identityScopeIsTheEmailAccountId() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = account(accountId, ownerId, "owner@example.test", "{}");
        when(channelAccountMapper.findByIdAndOwner(accountId, ownerId)).thenReturn(account);
        when(contactIdentityMapper.selectList(any())).thenReturn(List.of());

        EmailSyncService service = service();
        Method resolve = EmailSyncService.class.getDeclaredMethod("resolveOrCreateIdentity", String.class, String.class);
        resolve.setAccessible(true);
        resolve.invoke(service, "Customer <customer@example.test>", accountId.toString());

        ArgumentCaptor<ContactIdentityEntity> captor = ArgumentCaptor.forClass(ContactIdentityEntity.class);
        verify(contactIdentityMapper).insert(captor.capture());
        assertEquals(accountId.toString(), captor.getValue().getIdentityScope());
    }

    @Test
    void outboundPersistenceUsesTheRequestedAccount() {
        UUID ownerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = account(accountId, ownerId, "owner@example.test", "{}");
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(channelAccountMapper.findByIdAndOwner(accountId, ownerId)).thenReturn(account);
        when(contactIdentityMapper.selectList(any())).thenReturn(List.of(identity));
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));

        EmailSendService service = sendService();
        service.persistOutbound(ownerId, accountId, "customer@example.test", "subject", "body", "<m@example.test>");

        verify(messageMapper).insert(any(com.crmforlogistics.messagecenter.entity.MessageEntity.class));
        verify(channelAccountMapper).findByIdAndOwner(accountId, ownerId);
    }

    private EmailSyncService service() {
        return new EmailSyncService(config, messageMapper, conversationMapper, channelAccountMapper,
                contactIdentityMapper, contactMapper, eventHub, credentialCipher, attachmentStore,
                topicActivityRecorder, null);
    }

    private EmailSendService sendService() {
        return new EmailSendService(config, messageMapper, conversationMapper, channelAccountMapper,
                contactIdentityMapper, attachmentStore, topicActivityRecorder);
    }

    private static ChannelAccountEntity account(UUID id, UUID ownerId, String identifier, String encrypted) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(id);
        account.setOwnerUserId(ownerId);
        account.setChannelType("email");
        account.setAccountIdentifier(identifier);
        account.setEncryptedConfig(encrypted);
        account.setAuthStatus("active");
        return account;
    }
}
