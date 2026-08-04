package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.infrastructure.ContactPointUtil;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import jakarta.mail.Address;
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

import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

    public EmailSyncService(AppConfig config, MessageMapper messageMapper,
                            ConversationMapper conversationMapper,
                            ChannelAccountMapper channelAccountMapper,
                            ContactIdentityMapper contactIdentityMapper) {
        this.config = config;
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.channelAccountMapper = channelAccountMapper;
        this.contactIdentityMapper = contactIdentityMapper;
    }

    public record SyncResult(String channel, int fetched, int saved, int skipped, String message) {}

    public SyncResult receiveLatest() throws Exception {
        SyncResult result = new SyncResult("email", 0, 0, 0, "");
        if (usesOpenSslImapFallback()) {
            receiveLatestWithOpenSsl(result);
            return new SyncResult("email", result.fetched(), result.saved(), result.skipped(),
                    "received " + result.saved() + " new email messages");
        }
        try (Store store = connectStore()) {
            receiveLatestFromFolder(store, config.inboxFolder(), "in", receiveLimit(), result);
            receiveLatestFromFolder(store, config.sentFolder(), "out", receiveLimit(), result);
        }
        return new SyncResult("email", result.fetched(), result.saved(), result.skipped(),
                "received " + result.saved() + " new email messages");
    }

    boolean usesOpenSslImapFallback() {
        return PROVIDER_139.equalsIgnoreCase(effectiveMailProvider()) && config.imap139UseOpenssl();
    }

    private void receiveLatestWithOpenSsl(SyncResult result) throws Exception {
        requireConfig("imapHost");
        requireConfig("imapUser");
        requireConfig("imapPassword");
        OpenSslImapClient client = new OpenSslImapClient(config);
        receiveLatestFromOpenSslFolder(client, config.inboxFolder(), "in", receiveLimit(), result);
        receiveLatestFromOpenSslFolder(client, config.sentFolder(), "out", receiveLimit(), result);
    }

    private void receiveLatestFromOpenSslFolder(OpenSslImapClient client, String folderName,
                                                 String direction, int limit, SyncResult result) throws Exception {
        if (folderName == null || folderName.isBlank()) return;
        for (Message message : client.fetchLatest(folderName, limit)) {
            SyncResult updated = appendReceived(result, message, direction);
            result = updated;
        }
    }

    private void receiveLatestFromFolder(Store store, String folderName, String direction,
                                          int limit, SyncResult result) throws Exception {
        if (folderName == null || folderName.isBlank()) return;
        Folder folder = store.getFolder(folderName);
        if (!folder.exists()) return;
        folder.open(Folder.READ_ONLY);
        try {
            int count = folder.getMessageCount();
            if (count == 0) return;
            int start = Math.max(1, count - Math.max(1, limit) + 1);
            Message[] messages = folder.getMessages(start, count);
            result = new SyncResult("email", result.fetched() + messages.length,
                    result.saved(), result.skipped(), result.message());
            for (Message message : messages) {
                SyncResult updated = appendReceived(result, message, direction);
                result = updated;
            }
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
            String bodyText = bodyText(message);
            Instant sentDate = message.getSentDate() != null
                    ? message.getSentDate().toInstant() : Instant.now();
            String messageId = firstHeader(message, "Message-ID");

            if (isDuplicate(messageId, normalizedDirection, subject, sentDate.toString(), contactEmail)) {
                return new SyncResult("email", result.fetched(), result.saved(), result.skipped() + 1, result.message());
            }

            ChannelAccountEntity account = resolveEmailAccount();
            if (account == null) {
                return new SyncResult("email", result.fetched(), result.saved(), result.skipped() + 1, result.message());
            }

            ContactIdentityEntity identity = resolveOrCreateIdentity(contactSource);
            ConversationEntity conversation = resolveOrCreateConversation(identity.getId(), account.getId());

            MessageEntity entity = new MessageEntity();
            entity.setId(UUID.randomUUID());
            entity.setChannelAccountId(account.getId());
            entity.setConversationId(conversation.getId());
            entity.setDirection(normalizedDirection.equals("out") ? "outbound" : "inbound");
            entity.setMessageKind("email");
            entity.setSubject(subject);
            entity.setBodyText(bodyText);
            entity.setOccurredAt(sentDate);
            entity.setCurrentStatus("received");
            entity.setIngestSequence(hashMessageId(messageId));
            messageMapper.insert(entity);

            return new SyncResult("email", result.fetched(), result.saved() + 1, result.skipped(), result.message());
        } catch (Exception e) {
            log.warn("Failed to persist received email", e);
            return new SyncResult("email", result.fetched(), result.saved(), result.skipped() + 1, result.message());
        }
    }

    private Long hashMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) return (long) System.nanoTime();
        return (long) messageId.hashCode();
    }

    private boolean isDuplicate(String messageId, String direction, String subject,
                                 String sentDate, String contactEmail) {
        if (messageId == null || messageId.isBlank()) return false;
        List<MessageEntity> existing = messageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageEntity>()
                        .eq(MessageEntity::getIngestSequence, hashMessageId(messageId)));
        return !existing.isEmpty();
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
        if (!existing.isEmpty()) return existing.get(0);
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setChannelType("email");
        identity.setIdentityScope("email");
        identity.setIdentityValue(normalized);
        identity.setDisplayName(ContactPointUtil.extractName(email, normalized));
        identity.setCreatedAt(Instant.now());
        identity.setUpdatedAt(Instant.now());
        contactIdentityMapper.insert(identity);
        return identity;
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

    private Store connectStore() throws MessagingException {
        requireConfig("imapHost");
        requireConfig("imapUser");
        requireConfig("imapPassword");
        Session session = Session.getInstance(imapProperties());
        Store store = session.getStore(storeProtocol());
        store.connect(config.imapHost(), imapPort(),
                config.imapUser(), config.imapPassword());
        return store;
    }

    Properties imapProperties() {
        Properties props = new Properties();
        putImapProperties(props, "mail.imap");
        putImapProperties(props, "mail.imaps");
        return props;
    }

    private void putImapProperties(Properties props, String prefix) {
        props.put(prefix + ".host", config.imapHost() != null ? config.imapHost() : "");
        props.put(prefix + ".port", Integer.toString(imapPort()));
        props.put(prefix + ".ssl.enable", Boolean.toString(config.imapSsl()));
        props.put(prefix + ".connectiontimeout", "15000");
        props.put(prefix + ".timeout", "30000");
        props.put(prefix + ".ssl.protocols", "TLSv1.2");
        String cipherSuite = javaMailCipherSuite();
        if (!cipherSuite.isBlank()) {
            props.put(prefix + ".ssl.ciphersuites", cipherSuite);
            props.put(prefix + ".ssl.socketFactory",
                    BouncyCastle139ImapSocketFactory.create(config.imapHost()));
            props.put(prefix + ".ssl.checkserveridentity", "true");
        }
    }

    private String storeProtocol() {
        return config.imapSsl() ? "imaps" : "imap";
    }

    private String javaMailCipherSuite() {
        return PROVIDER_139.equalsIgnoreCase(effectiveMailProvider())
                ? BouncyCastle139ImapSocketFactory.CIPHER_SUITE : "";
    }

    private String effectiveMailProvider() {
        String configured = config.mailProvider();
        if (configured == null || configured.isBlank() || "auto".equalsIgnoreCase(configured)) {
            String host = config.imapHost();
            if (host != null && (host.equals("139.com") || host.endsWith(".139.com"))) {
                return PROVIDER_139;
            }
            return "default";
        }
        return configured;
    }

    private int imapPort() {
        String port = config.imapPort();
        return port != null && !port.isBlank() ? Integer.parseInt(port) : 993;
    }

    private int receiveLimit() {
        return config.receiveLimit();
    }

    private void requireConfig(String key) {
        String value = switch (key) {
            case "imapHost" -> config.imapHost();
            case "imapUser" -> config.imapUser();
            case "imapPassword" -> config.imapPassword();
            default -> "";
        };
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing config: " + key);
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
