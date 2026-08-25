package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailSyncServiceTest {

    @Mock AppConfig config;
    @Mock MessageMapper messageMapper;
    @Mock ConversationMapper conversationMapper;
    @Mock ChannelAccountMapper channelAccountMapper;
    @Mock ContactIdentityMapper contactIdentityMapper;
    @Mock ContactMapper contactMapper;
    @Mock EventHub eventHub;
    @Mock EmailAttachmentStore attachmentStore;
    @Mock CredentialCipher credentialCipher;

    @Test
    void shouldConstructWithDependencies() {
        EmailSyncService service = new EmailSyncService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper, contactMapper, eventHub);
        assertNotNull(service);
    }

    @Test
    void springInjectionConstructorMustIncludeAttachmentStore() {
        Constructor<?> injectionConstructor = Arrays.stream(EmailSyncService.class.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .findFirst()
                .orElseThrow();

        assertEquals(9, injectionConstructor.getParameterCount());
        assertEquals(CredentialCipher.class, injectionConstructor.getParameterTypes()[7]);
        assertEquals(EmailAttachmentStore.class, injectionConstructor.getParameterTypes()[8]);
    }

    @Test
    void channelAccountEmailSettingsOverrideEnvironmentDefaults() throws Exception {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setEncryptedConfig("encrypted");
        when(channelAccountMapper.selectList(any())).thenReturn(List.of(account));
        when(credentialCipher.decrypt("encrypted")).thenReturn(java.util.Map.of(
                "imapHost", "imap.example.test",
                "imapPort", "1993",
                "imapUser", "saved@example.test",
                "imapPassword", "saved-password",
                "imapSsl", "false",
                "mailProvider", "custom"));
        when(config.imapHost()).thenReturn("imap.139.com");
        when(config.imapPort()).thenReturn("993");
        when(config.imapUser()).thenReturn("env@example.test");
        when(config.imapPassword()).thenReturn("env-password");
        when(config.imapSsl()).thenReturn(true);
        when(config.mailProvider()).thenReturn("139");

        EmailSyncService service = new EmailSyncService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper, contactMapper,
                eventHub, credentialCipher, attachmentStore);

        EmailSyncSettings settings = service.resolveSettings();

        assertEquals("imap.example.test", settings.imapHost());
        assertEquals("1993", settings.imapPort());
        assertEquals("saved@example.test", settings.imapUser());
        assertEquals("saved-password", settings.imapPassword());
        assertEquals(false, settings.imapSsl());
        assertEquals("custom", settings.mailProvider());
    }

    @Test
    void emailAccountIdentifierIsUsedWhenSavedImapUserAndEnvironmentUserAreBlank() throws Exception {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setAccountIdentifier("mailbox@example.test");
        account.setEncryptedConfig("encrypted");
        when(channelAccountMapper.selectList(any())).thenReturn(List.of(account));
        when(credentialCipher.decrypt("encrypted")).thenReturn(java.util.Map.of(
                "imapHost", "imap.example.test",
                "imapPassword", "authorization-code"));
        when(config.imapUser()).thenReturn("");

        EmailSyncService service = new EmailSyncService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper, contactMapper,
                eventHub, credentialCipher, attachmentStore);

        assertEquals("mailbox@example.test", service.resolveSettings().imapUser());
    }

    @Test
    void javaMailAuthenticationFailureUsesStableEmailErrorCode() {
        EmailException exception = assertThrows(EmailException.class,
                () -> EmailSyncService.mapConnectionFailure(
                        new AuthenticationFailedException("139 errno=1310")));

        assertEquals("EMAIL_IMAP_AUTHENTICATION_FAILED", exception.code());
        assertEquals("IMAP authentication failed", exception.getMessage());
    }

    @Test
    void receivedMimeAttachmentIsStoredAfterMessageInsert() throws Exception {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());

        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(channelAccountMapper.selectList(any())).thenReturn(List.of(account));
        when(contactIdentityMapper.selectList(any())).thenReturn(List.of(identity));
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));
        when(config.emailAttachmentMaxCount()).thenReturn(16);
        when(config.emailAttachmentMaxTotalBytes()).thenReturn(20_971_520L);

        EmailSyncService service = new EmailSyncService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper, contactMapper,
                eventHub, attachmentStore);
        MimeMessage message = attachmentMessage();

        Method appendReceived = EmailSyncService.class.getDeclaredMethod(
                "appendReceived", EmailSyncService.SyncResult.class, Message.class, String.class);
        appendReceived.setAccessible(true);
        EmailSyncService.SyncResult result = (EmailSyncService.SyncResult) appendReceived.invoke(
                service, new EmailSyncService.SyncResult("email", 1, 0, 0, ""), message, "in");

        assertEquals(1, result.saved());
        verify(attachmentStore).store(any(UUID.class), argThat(payloads ->
                payloads.size() == 1
                        && "photo.png".equals(payloads.get(0).fileName())
                        && "image".equals(payloads.get(0).mediaKind())
                        && Arrays.equals("png".getBytes(StandardCharsets.UTF_8), payloads.get(0).bytes())),
                anyBoolean());
    }

    @Test
    void newlyResolvedEmailContactHasAnIdBeforeIdentityIsLinked() throws Exception {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        when(channelAccountMapper.selectList(any())).thenReturn(List.of(account));
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(contactIdentityMapper.selectList(any())).thenReturn(List.of());
        when(conversationMapper.selectList(any())).thenReturn(List.of());

        EmailSyncService service = new EmailSyncService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper, contactMapper,
                eventHub, attachmentStore);
        Method appendReceived = EmailSyncService.class.getDeclaredMethod(
                "appendReceived", EmailSyncService.SyncResult.class, Message.class, String.class);
        appendReceived.setAccessible(true);

        EmailSyncService.SyncResult result = (EmailSyncService.SyncResult) appendReceived.invoke(
                service, new EmailSyncService.SyncResult("email", 1, 0, 0, ""),
                attachmentMessage(), "in");

        assertEquals(1, result.saved());
        verify(contactMapper).insert(argThat((com.crmforlogistics.messagecenter.entity.ContactEntity contact)
                -> contact.getId() != null));
        verify(contactIdentityMapper).insert(argThat((ContactIdentityEntity identity)
                -> identity.getContactId() != null));
    }

    @Test
    void duplicateMessageWithoutAttachmentsRepairsAttachmentsWithoutInsertingMessage() throws Exception {
        MessageEntity existing = new MessageEntity();
        UUID existingId = UUID.randomUUID();
        existing.setId(existingId);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        when(channelAccountMapper.selectList(any())).thenReturn(List.of(account));
        when(messageMapper.findByProviderMessageId(account.getId(), "<attachment-test@example.com>"))
                .thenReturn(java.util.Optional.of(existing));
        when(config.emailAttachmentMaxCount()).thenReturn(16);
        when(config.emailAttachmentMaxTotalBytes()).thenReturn(20_971_520L);

        EmailSyncService service = new EmailSyncService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper, contactMapper,
                eventHub, attachmentStore);
        Method appendReceived = EmailSyncService.class.getDeclaredMethod(
                "appendReceived", EmailSyncService.SyncResult.class, Message.class, String.class);
        appendReceived.setAccessible(true);

        EmailSyncService.SyncResult result = (EmailSyncService.SyncResult) appendReceived.invoke(
                service, new EmailSyncService.SyncResult("email", 1, 0, 0, ""),
                attachmentMessage(), "in");

        assertEquals(0, result.saved());
        assertEquals(1, result.skipped());
        verify(attachmentStore).storeIfMissing(eq(existingId), argThat(payloads -> payloads.size() == 1));
    }

    @Test
    void openSslFolderReturnsUpdatedSyncCounters() throws Exception {
        EmailSyncService service = new EmailSyncService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper, contactMapper,
                eventHub, attachmentStore);
        OpenSslImapClient client = org.mockito.Mockito.mock(OpenSslImapClient.class);
        when(client.fetchLatest("INBOX", 10)).thenReturn(List.of(attachmentMessage()));
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        when(channelAccountMapper.selectList(any())).thenReturn(List.of(account));
        when(config.emailAttachmentMaxCount()).thenReturn(16);
        when(config.emailAttachmentMaxTotalBytes()).thenReturn(20_971_520L);
        when(messageMapper.findByProviderMessageId(account.getId(), "<attachment-test@example.com>"))
                .thenReturn(java.util.Optional.of(new MessageEntity()));

        Method receiveFolder = EmailSyncService.class.getDeclaredMethod(
                "receiveLatestFromOpenSslFolder", OpenSslImapClient.class, String.class,
                String.class, int.class, EmailSyncService.SyncResult.class);
        receiveFolder.setAccessible(true);

        EmailSyncService.SyncResult result = (EmailSyncService.SyncResult) receiveFolder.invoke(
                service, client, "INBOX", "in", 10,
                new EmailSyncService.SyncResult("email", 0, 0, 0, ""));

        assertEquals(1, result.fetched());
        assertEquals(1, result.skipped());
    }

    @Test
    void attachmentStoreFailureDoesNotTurnPersistedMessageIntoSkippedResult() throws Exception {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(UUID.randomUUID());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(channelAccountMapper.selectList(any())).thenReturn(List.of(account));
        when(messageMapper.findByProviderMessageId(account.getId(), "<attachment-test@example.com>"))
                .thenReturn(java.util.Optional.empty());
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(contactIdentityMapper.selectList(any())).thenReturn(List.of(identity));
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));
        when(config.emailAttachmentMaxCount()).thenReturn(16);
        when(config.emailAttachmentMaxTotalBytes()).thenReturn(20_971_520L);
        org.mockito.Mockito.doThrow(new java.io.IOException("storage unavailable"))
                .when(attachmentStore).store(any(UUID.class), any(), eq(true));

        EmailSyncService service = new EmailSyncService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper, contactMapper,
                eventHub, attachmentStore);
        Method appendReceived = EmailSyncService.class.getDeclaredMethod(
                "appendReceived", EmailSyncService.SyncResult.class, Message.class, String.class);
        appendReceived.setAccessible(true);

        EmailSyncService.SyncResult result = (EmailSyncService.SyncResult) appendReceived.invoke(
                service, new EmailSyncService.SyncResult("email", 1, 0, 0, ""), attachmentMessage(), "in");

        assertEquals(1, result.saved());
        assertEquals(0, result.skipped());
        verify(messageMapper).insertWithSequence(argThat(entity ->
                "<attachment-test@example.com>".equals(entity.getProviderMessageId())));
        verify(messageMapper, never()).insert(any(MessageEntity.class));
    }

    private static MimeMessage attachmentMessage() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("receiver@example.com"));
        message.setSubject("attachment message", StandardCharsets.UTF_8.name());
        message.setSentDate(Date.from(Instant.parse("2026-08-13T00:00:00Z")));

        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart body = new MimeBodyPart();
        body.setText("body", StandardCharsets.UTF_8.name());
        mixed.addBodyPart(body);
        MimeBodyPart image = new MimeBodyPart();
        image.setFileName("photo.png");
        image.setDisposition(Message.ATTACHMENT);
        image.setDataHandler(new DataHandler(new ByteArrayDataSource(
                "png".getBytes(StandardCharsets.UTF_8), "image/png")));
        mixed.addBodyPart(image);
        message.setContent(mixed);
        message.saveChanges();
        message.setHeader("Message-ID", "<attachment-test@example.com>");
        return message;
    }

}
