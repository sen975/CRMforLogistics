package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.infrastructure.ContactPointUtil;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

@Service
public class EmailSyncService {

    private static final Logger log = LoggerFactory.getLogger(EmailSyncService.class);
    private static final String PROVIDER_139 = "139";

    private final AppConfig config;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final ChannelAccountMapper channelAccountMapper;
    private final ContactIdentityMapper contactIdentityMapper;
    private final ContactMapper contactMapper;
    private final EventHub eventHub;
    private final CredentialCipher credentialCipher;
    private final EmailMimeParser mimeParser = new EmailMimeParser();
    private final EmailAttachmentStore attachmentStore;

    public EmailSyncService(AppConfig config, MessageMapper messageMapper,
                            ConversationMapper conversationMapper,
                            ChannelAccountMapper channelAccountMapper,
                            ContactIdentityMapper contactIdentityMapper,
                            ContactMapper contactMapper,
                            EventHub eventHub) {
        this(config, messageMapper, conversationMapper, channelAccountMapper,
                contactIdentityMapper, contactMapper, eventHub, null);
    }

    public EmailSyncService(AppConfig config, MessageMapper messageMapper,
                            ConversationMapper conversationMapper,
                            ChannelAccountMapper channelAccountMapper,
                            ContactIdentityMapper contactIdentityMapper,
                            ContactMapper contactMapper,
                            EventHub eventHub,
                            EmailAttachmentStore attachmentStore) {
        this(config, messageMapper, conversationMapper, channelAccountMapper,
                contactIdentityMapper, contactMapper, eventHub, null, attachmentStore);
    }

    @Autowired
    public EmailSyncService(AppConfig config, MessageMapper messageMapper,
                            ConversationMapper conversationMapper,
                            ChannelAccountMapper channelAccountMapper,
                            ContactIdentityMapper contactIdentityMapper,
                            ContactMapper contactMapper,
                            EventHub eventHub,
                            CredentialCipher credentialCipher,
                            EmailAttachmentStore attachmentStore) {
        this.config = config;
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.channelAccountMapper = channelAccountMapper;
        this.contactIdentityMapper = contactIdentityMapper;
        this.contactMapper = contactMapper;
        this.eventHub = eventHub;
        this.credentialCipher = credentialCipher;
        this.attachmentStore = attachmentStore;
    }

    public record SyncResult(String channel, int fetched, int saved, int skipped, String message) {}

    public SyncResult receiveLatest() throws Exception {
        EmailSyncSettings settings = resolveSettings();
        SyncResult result = new SyncResult("email", 0, 0, 0, "");
        if (usesOpenSslImapFallback(settings)) {
            SyncResult finalResult = receiveLatestWithOpenSsl(settings, result);
            if (finalResult.saved() > 0) {
                eventHub.publish("message-new", "{}");
            }
            return new SyncResult("email", finalResult.fetched(), finalResult.saved(), finalResult.skipped(),
                    "received " + finalResult.saved() + " new email messages");
        }
        try (Store store = connectStore(settings)) {
            result = receiveLatestFromFolder(store, settings.inboxFolder(), "in", settings.receiveLimit(), result);
            result = receiveLatestFromFolder(store, settings.sentFolder(), "out", settings.receiveLimit(), result);
        }
        SyncResult finalResult = new SyncResult("email", result.fetched(), result.saved(), result.skipped(),
                "received " + result.saved() + " new email messages");
        if (finalResult.saved() > 0) {
            eventHub.publish("message-new", "{}");
        }
        return finalResult;
    }

    boolean usesOpenSslImapFallback() {
        return usesOpenSslImapFallback(resolveSettings());
    }

    private boolean usesOpenSslImapFallback(EmailSyncSettings settings) {
        return PROVIDER_139.equalsIgnoreCase(effectiveMailProvider(settings)) && settings.imap139UseOpenssl();
    }

    private SyncResult receiveLatestWithOpenSsl(EmailSyncSettings settings, SyncResult result) throws Exception {
        logSettings(settings);
        requireConfig(settings.imapHost(), "imapHost");
        requireConfig(settings.imapUser(), "imapUser");
        requireConfig(settings.imapPassword(), "imapPassword");
        OpenSslImapClient client = new OpenSslImapClient(settings);
        result = receiveLatestFromOpenSslFolder(client, settings.inboxFolder(), "in", settings.receiveLimit(), result);
        return receiveLatestFromOpenSslFolder(client, settings.sentFolder(), "out", settings.receiveLimit(), result);
    }

    private SyncResult receiveLatestFromOpenSslFolder(OpenSslImapClient client, String folderName,
                                                       String direction, int limit, SyncResult result) throws Exception {
        if (folderName == null || folderName.isBlank()) return result;
        var messages = client.fetchLatest(folderName, limit);
        result = new SyncResult("email", result.fetched() + messages.size(),
                result.saved(), result.skipped(), result.message());
        for (Message message : messages) {
            SyncResult updated = appendReceived(result, message, direction);
            result = updated;
        }
        return result;
    }

    private SyncResult receiveLatestFromFolder(Store store, String folderName, String direction,
                                                int limit, SyncResult result) throws Exception {
        if (folderName == null || folderName.isBlank()) return result;
        Folder folder = store.getFolder(folderName);
        if (!folder.exists()) return result;
        folder.open(Folder.READ_ONLY);
        try {
            int count = folder.getMessageCount();
            if (count == 0) return result;
            int start = Math.max(1, count - Math.max(1, limit) + 1);
            Message[] messages = folder.getMessages(start, count);
            result = new SyncResult("email", result.fetched() + messages.length,
                    result.saved(), result.skipped(), result.message());
            for (Message message : messages) {
                SyncResult updated = appendReceived(result, message, direction);
                result = updated;
            }
            return result;
        } finally {
            folder.close(false);
        }
    }

    private SyncResult appendReceived(SyncResult result, Message message, String direction) {
        try {
            String normalizedDirection = normalizeDirection(direction);
            String from = addresses(message.getFrom());
            String to = firstNonBlank(
                    addresses(message.getRecipients(Message.RecipientType.TO)),
                    addresses(message.getRecipients(Message.RecipientType.CC)),
                    addresses(message.getAllRecipients()));
            String contactSource = "out".equals(normalizedDirection) ? to : from;
            String contactEmail = ContactPointUtil.extractEmail(contactSource);
            String subject = decodeMimeText(message.getSubject());
            var parsed = mimeParser.parse(message);
            String bodyText = parsed.bodyText();
            Instant sentDate = message.getSentDate() != null
                    ? message.getSentDate().toInstant() : Instant.now();
            String messageId = firstHeader(message, "Message-ID");

            ChannelAccountEntity account = resolveEmailAccount();
            if (account == null) {
                return new SyncResult("email", result.fetched(), result.saved(), result.skipped() + 1, result.message());
            }
            Optional<MessageEntity> duplicate = findDuplicate(account.getId(), messageId, subject, sentDate);
            if (duplicate.isPresent()) {
                MessageEntity existing = duplicate.get();
                if ((existing.getProviderMessageId() == null || existing.getProviderMessageId().isBlank())
                        && messageId != null && !messageId.isBlank()) {
                    messageMapper.updateProviderMessageId(existing.getId(), messageId);
                }
                repairAttachments(existing, parsed);
                return new SyncResult("email", result.fetched(), result.saved(), result.skipped() + 1, result.message());
            }

            ContactIdentityEntity identity = resolveOrCreateIdentity(contactSource);
            ConversationEntity conversation = resolveOrCreateConversation(identity.getId(), account.getId());

            MessageEntity entity = new MessageEntity();
            entity.setId(UUID.randomUUID());
            entity.setChannelAccountId(account.getId());
            entity.setConversationId(conversation.getId());
            entity.setDirection(normalizedDirection.equals("out") ? "outbound" : "inbound");
            entity.setCountsAsUnread("in".equals(normalizedDirection));
            entity.setMessageKind("email");
            entity.setProviderMessageId(messageId);
            entity.setSubject(subject);
            entity.setBodyText(bodyText);
            entity.setOccurredAt(sentDate);
            entity.setCurrentStatus("delivered");
            entity.setCurrentStatusAt(Instant.now());
            messageMapper.insertWithSequence(entity);
            if (attachmentStore != null && !parsed.attachments().isEmpty()) {
                try {
                    var payloads = new EmailAttachmentReader(
                            config.emailAttachmentMaxCount(), config.emailAttachmentMaxTotalBytes())
                            .readAvailable(parsed.attachments());
                    attachmentStore.store(entity.getId(), payloads, true);
                } catch (Exception attachmentError) {
                    String code = attachmentError instanceof EmailException emailError
                            ? emailError.code() : "EMAIL_ATTACHMENT_STORE_FAILED";
                    log.warn("Email attachment persistence failed code={} messageId={}",
                            code, entity.getId(), attachmentError);
                }
            }

            return new SyncResult("email", result.fetched(), result.saved() + 1, result.skipped(), result.message());
        } catch (Exception e) {
            log.warn("Failed to persist received email", e);
            return new SyncResult("email", result.fetched(), result.saved(), result.skipped() + 1, result.message());
        }
    }

    private Optional<MessageEntity> findDuplicate(UUID accountId, String messageId,
                                                   String subject, Instant sentDate) {
        if (messageId == null || messageId.isBlank()) return Optional.empty();
        Optional<MessageEntity> current = messageMapper.findByProviderMessageId(accountId, messageId);
        if (current.isPresent()) return current;

        long legacySequence = (long) messageId.hashCode() & 0x7FFFFFFFFFFFFFFFL;
        List<MessageEntity> legacy = messageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageEntity>()
                        .eq(MessageEntity::getChannelAccountId, accountId)
                        .isNull(MessageEntity::getProviderMessageId)
                        .eq(MessageEntity::getIngestSequence, legacySequence)
                        .eq(MessageEntity::getSubject, subject)
                        .eq(MessageEntity::getOccurredAt, sentDate));
        return legacy.size() == 1 ? Optional.of(legacy.get(0)) : Optional.empty();
    }

    private void repairAttachments(MessageEntity entity, EmailMimeParser.ParsedEmail parsed) {
        if (attachmentStore == null || parsed.attachments().isEmpty()) return;
        try {
            var payloads = new EmailAttachmentReader(
                    config.emailAttachmentMaxCount(), config.emailAttachmentMaxTotalBytes())
                    .readAvailable(parsed.attachments());
            attachmentStore.storeIfMissing(entity.getId(), payloads);
        } catch (EmailException attachmentError) {
            log.warn("Email attachment repair failed code={} messageId={}",
                    attachmentError.code(), entity.getId());
        } catch (Exception attachmentError) {
            log.warn("Email attachment repair failed messageId={}", entity.getId(), attachmentError);
        }
    }

    private ChannelAccountEntity resolveEmailAccount() {
        List<ChannelAccountEntity> accounts = channelAccountMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChannelAccountEntity>()
                        .eq(ChannelAccountEntity::getChannelType, "email")
                        .isNull(ChannelAccountEntity::getDeletedAt));
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    private ContactIdentityEntity resolveOrCreateIdentity(String email) {
        String normalized = ContactPointUtil.extractEmail(email);
        List<ContactIdentityEntity> existing = contactIdentityMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ContactIdentityEntity>()
                        .eq(ContactIdentityEntity::getChannelType, "email")
                        .eq(ContactIdentityEntity::getIdentityScope, "email")
                        .eq(ContactIdentityEntity::getIdentityValue, normalized));
        if (!existing.isEmpty()) {
            ContactIdentityEntity identity = existing.get(0);
            if (identity.getContactId() == null) {
                linkToNewContact(identity, email, normalized);
            }
            return identity;
        }
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setChannelType("email");
        identity.setIdentityScope("email");
        identity.setIdentityValue(normalized);
        identity.setNormalizedValue(normalized);
        identity.setDisplayName(ContactPointUtil.extractName(email, normalized));
        identity.setCreatedAt(Instant.now());
        identity.setUpdatedAt(Instant.now());

        ContactEntity contact = new ContactEntity();
        contact.setId(UUID.randomUUID());
        contact.setDisplayName(ContactPointUtil.extractName(email, normalized));
        if (contact.getDisplayName() == null || contact.getDisplayName().isBlank()) {
            contact.setDisplayName(normalized);
        }
        contact.setCreatedAt(Instant.now());
        contact.setUpdatedAt(Instant.now());
        contactMapper.insert(contact);
        identity.setContactId(contact.getId());
        contactIdentityMapper.insert(identity);
        return identity;
    }

    private void linkToNewContact(ContactIdentityEntity identity, String email, String normalized) {
        ContactEntity contact = new ContactEntity();
        contact.setId(UUID.randomUUID());
        String displayName = ContactPointUtil.extractName(email, normalized);
        contact.setDisplayName(!displayName.isBlank() ? displayName : normalized);
        contact.setCreatedAt(Instant.now());
        contact.setUpdatedAt(Instant.now());
        contactMapper.insert(contact);
        identity.setContactId(contact.getId());
        contactIdentityMapper.updateById(identity);
    }

    private ConversationEntity resolveOrCreateConversation(UUID identityId, UUID accountId) {
        List<ConversationEntity> existing = conversationMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getContactIdentityId, identityId)
                        .eq(ConversationEntity::getChannelAccountId, accountId));
        if (!existing.isEmpty()) return existing.get(0);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setContactIdentityId(identityId);
        conversation.setChannelAccountId(accountId);
        conversation.setCreatedAt(Instant.now());
        conversation.setUpdatedAt(Instant.now());
        conversationMapper.insert(conversation);
        return conversation;
    }

    private Store connectStore(EmailSyncSettings settings) throws MessagingException {
        logSettings(settings);
        requireConfig(settings.imapHost(), "imapHost");
        requireConfig(settings.imapUser(), "imapUser");
        requireConfig(settings.imapPassword(), "imapPassword");
        Session session = Session.getInstance(imapProperties(settings));
        Store store = session.getStore(storeProtocol(settings));
        try {
            store.connect(settings.imapHost(), imapPort(settings),
                    settings.imapUser(), settings.imapPassword());
        } catch (MessagingException exception) {
            mapConnectionFailure(exception);
            throw exception;
        }
        return store;
    }

    static void mapConnectionFailure(MessagingException exception) {
        if (isAuthenticationFailure(exception)) {
            throw new EmailException("EMAIL_IMAP_AUTHENTICATION_FAILED",
                    "IMAP authentication failed", exception);
        }
    }

    private static boolean isAuthenticationFailure(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof AuthenticationFailedException) return true;
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).matches(
                    ".*(authentication\\s*failed|login\\s*failed|errno\\s*=\\s*1310).*")) {
                return true;
            }
        }
        return false;
    }

    Properties imapProperties() {
        return imapProperties(resolveSettings());
    }

    private Properties imapProperties(EmailSyncSettings settings) {
        Properties props = new Properties();
        putImapProperties(props, "mail.imap", settings);
        putImapProperties(props, "mail.imaps", settings);
        return props;
    }

    private void putImapProperties(Properties props, String prefix) {
        putImapProperties(props, prefix, resolveSettings());
    }

    private void putImapProperties(Properties props, String prefix, EmailSyncSettings settings) {
        props.put(prefix + ".host", settings.imapHost() != null ? settings.imapHost() : "");
        props.put(prefix + ".port", Integer.toString(imapPort(settings)));
        props.put(prefix + ".ssl.enable", Boolean.toString(settings.imapSsl()));
        props.put(prefix + ".connectiontimeout", "15000");
        props.put(prefix + ".timeout", "30000");
        props.put(prefix + ".ssl.protocols", "TLSv1.2");
        String cipherSuite = javaMailCipherSuite(settings);
        if (!cipherSuite.isBlank()) {
            props.put(prefix + ".ssl.ciphersuites", cipherSuite);
            props.put(prefix + ".ssl.socketFactory",
                    BouncyCastle139ImapSocketFactory.create(settings.imapHost()));
            props.put(prefix + ".ssl.checkserveridentity", "true");
        }
    }

    private String storeProtocol(EmailSyncSettings settings) {
        return settings.imapSsl() ? "imaps" : "imap";
    }

    private String javaMailCipherSuite() {
        return javaMailCipherSuite(resolveSettings());
    }

    private String javaMailCipherSuite(EmailSyncSettings settings) {
        return PROVIDER_139.equalsIgnoreCase(effectiveMailProvider(settings))
                ? BouncyCastle139ImapSocketFactory.CIPHER_SUITE : "";
    }

    private String effectiveMailProvider() {
        return effectiveMailProvider(resolveSettings());
    }

    private String effectiveMailProvider(EmailSyncSettings settings) {
        String configured = settings.mailProvider();
        if (configured == null || configured.isBlank() || "auto".equalsIgnoreCase(configured)) {
            String host = settings.imapHost();
            if (host != null && (host.equals("139.com") || host.endsWith(".139.com"))) {
                return PROVIDER_139;
            }
            return "default";
        }
        return configured;
    }

    private int imapPort(EmailSyncSettings settings) {
        String port = settings.imapPort();
        return port != null && !port.isBlank() ? Integer.parseInt(port) : 993;
    }

    private int receiveLimit() {
        return resolveSettings().receiveLimit();
    }

    private void requireConfig(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing config: " + key);
        }
    }

    EmailSyncSettings resolveSettings() {
        return EmailSyncSettings.from(config, resolveEmailAccount(), credentialCipher, log);
    }

    private void logSettings(EmailSyncSettings settings) {
        String user = settings.imapUser() == null ? "" : settings.imapUser().trim();
        String password = settings.imapPassword() == null ? "" : settings.imapPassword();
        log.info("event=email.imap_settings_resolved host={} port={} userSha256={} passwordLength={} ssl={} provider={} openssl={} inbox={} sent={}",
                settings.imapHost(), settings.imapPort(), sha256(user), password.length(),
                settings.imapSsl(), effectiveMailProvider(settings), settings.imap139UseOpenssl(),
                settings.inboxFolder(), settings.sentFolder());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("EMAIL_DIAGNOSTIC_HASH_FAILED", exception);
        }
    }

    private static String normalizeDirection(String direction) {
        String value = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        return "out".equals(value) || "outbound".equals(value) || "sent".equals(value) ? "out" : "in";
    }

    private static String addresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < addresses.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(address(addresses[i]));
        }
        return builder.toString();
    }

    private static String address(Address address) {
        if (address instanceof InternetAddress internetAddress) {
            String email = internetAddress.getAddress() == null ? "" : internetAddress.getAddress();
            String personal = decodeMimeText(internetAddress.getPersonal());
            if (!personal.isBlank() && !email.isBlank()) return personal + " <" + email + ">";
            return !email.isBlank() ? email : decodeMimeText(address.toString());
        }
        return decodeMimeText(address == null ? "" : address.toString());
    }

    private static String firstHeader(Message message, String name) throws Exception {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? "" : values[0];
    }

    private static String bodyText(Message message) throws Exception {
        String text = extractText(message);
        return text == null ? "" : text.replace(' ', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String extractText(Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            return content == null ? "" : content.toString();
        }
        if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            return content == null ? "" : htmlToText(content.toString());
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                builder.append(extractText(multipart.getBodyPart(i))).append(' ');
            }
            return builder.toString();
        }
        return "";
    }

    private static String htmlToText(String html) {
        return html == null ? "" : html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n")
                .replaceAll("<[^>]+>", " ");
    }

    private static String decodeMimeText(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return MimeUtility.decodeText(value).trim();
        } catch (Exception ignored) {
            return value.trim();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }
}
