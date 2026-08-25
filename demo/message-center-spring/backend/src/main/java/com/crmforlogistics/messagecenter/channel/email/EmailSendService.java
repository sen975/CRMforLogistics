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
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class EmailSendService {

    private static final Logger log = LoggerFactory.getLogger(EmailSendService.class);

    private final AppConfig config;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final ChannelAccountMapper channelAccountMapper;
    private final ContactIdentityMapper contactIdentityMapper;
    private final EmailAttachmentReader attachmentReader;
    private final EmailAttachmentStore attachmentStore;

    public EmailSendService(AppConfig config, MessageMapper messageMapper,
                            ConversationMapper conversationMapper,
                            ChannelAccountMapper channelAccountMapper,
                            ContactIdentityMapper contactIdentityMapper) {
        this.config = config;
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.channelAccountMapper = channelAccountMapper;
        this.contactIdentityMapper = contactIdentityMapper;
        this.attachmentReader = new EmailAttachmentReader(
                positiveIntOrDefault(config.emailAttachmentMaxCount(), 16),
                positiveOrDefault(config.emailAttachmentMaxTotalBytes(), 20_971_520L));
        this.attachmentStore = null;
    }

    @Autowired
    public EmailSendService(AppConfig config, MessageMapper messageMapper,
                            ConversationMapper conversationMapper,
                            ChannelAccountMapper channelAccountMapper,
                            ContactIdentityMapper contactIdentityMapper,
                            EmailAttachmentStore attachmentStore) {
        this.config = config;
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.channelAccountMapper = channelAccountMapper;
        this.contactIdentityMapper = contactIdentityMapper;
        this.attachmentReader = new EmailAttachmentReader(
                positiveIntOrDefault(config.emailAttachmentMaxCount(), 16),
                positiveOrDefault(config.emailAttachmentMaxTotalBytes(), 20_971_520L));
        this.attachmentStore = attachmentStore;
    }

    public record SendResult(String messageId, String from, String to, String subject, String status) {}

    public SendResult send(String to, String subject, String body) throws Exception {
        return send(to, subject, body, List.of());
    }

    public SendResult send(String to, String subject, String body, List<EmailAttachmentInput> attachmentInputs) throws Exception {
        String cleanTo = required(to, "to");
        var attachments = attachmentReader.read(attachmentInputs == null ? List.of() : attachmentInputs);
        SmtpEndpoint endpoint = smtpEndpoint();
        Session session = Session.getInstance(smtpProperties(endpoint));
        MimeMessage message = new MimeMessage(session);
        try {
            String fromName = config.mailFromName();
            if (fromName == null || fromName.isBlank()) {
                message.setFrom(new InternetAddress(mailFrom()));
            } else {
                message.setFrom(new InternetAddress(mailFrom(), fromName, "UTF-8"));
            }
        } catch (UnsupportedEncodingException ex) {
            throw new MessagingException("Failed to encode sender name", ex);
        }
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(cleanTo, false));
        message.setSubject(subject == null ? "" : subject, "UTF-8");
        if (attachments.isEmpty()) {
            message.setText(body == null ? "" : body, "UTF-8");
        } else {
            MimeMultipart multipart = new MimeMultipart("mixed");
            MimeBodyPart bodyPart = new MimeBodyPart();
            bodyPart.setText(body == null ? "" : body, "UTF-8");
            multipart.addBodyPart(bodyPart);
            for (EmailAttachmentPayload attachment : attachments) {
                MimeBodyPart part = new MimeBodyPart();
                part.setContent(attachment.bytes(), attachment.mimeType());
                part.setFileName(jakarta.mail.internet.MimeUtility.encodeText(attachment.fileName(), "UTF-8", null));
                part.setDisposition(jakarta.mail.Part.ATTACHMENT);
                multipart.addBodyPart(part);
            }
            message.setContent(multipart);
        }
        message.saveChanges();
        message.setHeader("Message-ID", messageIdHeader(mailFrom(), endpoint.serverName()));

        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(endpoint.connectHost(), smtpPort(),
                    config.smtpUser(), smtpPassword());
            transport.sendMessage(message, message.getAllRecipients());
        }

        String messageId = message.getMessageID() == null ? "" : message.getMessageID();
        UUID dbId = persistOutbound(cleanTo, subject, body, messageId);
        if (dbId != null && attachmentStore != null && !attachments.isEmpty()) {
            attachmentStore.store(dbId, attachments, true);
        }
        return new SendResult(dbId != null ? dbId.toString() : messageId,
                mailFrom(), cleanTo, subject, "sent");
    }

    private UUID persistOutbound(String to, String subject, String body, String messageId) {
        try {
            ChannelAccountEntity account = resolveEmailAccount();
            if (account == null) {
                log.warn("No email channel account found, skipping DB persist");
                return null;
            }
            ContactIdentityEntity identity = resolveOrCreateIdentity(to);
            ConversationEntity conversation = resolveOrCreateConversation(identity.getId(), account.getId());
            MessageEntity entity = new MessageEntity();
            entity.setId(UUID.randomUUID());
            entity.setChannelAccountId(account.getId());
            entity.setConversationId(conversation.getId());
            entity.setDirection("outbound");
            entity.setMessageKind("email");
            entity.setSubject(subject);
            entity.setBodyText(body);
            entity.setOccurredAt(Instant.now());
            entity.setCurrentStatus("sent");
            messageMapper.insert(entity);
            return entity.getId();
        } catch (Exception e) {
            log.error("Failed to persist outbound email to DB", e);
            return null;
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
            return existing.get(0);
        }
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
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setContactIdentityId(identityId);
        conversation.setChannelAccountId(accountId);
        conversation.setCreatedAt(Instant.now());
        conversation.setUpdatedAt(Instant.now());
        conversationMapper.insert(conversation);
        return conversation;
    }

    private Properties smtpProperties(SmtpEndpoint endpoint) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", endpoint.connectHost());
        props.put("mail.smtp.port", Integer.toString(smtpPort()));
        props.put("mail.smtp.ssl.enable", Boolean.toString(config.smtpSsl()));
        props.put("mail.smtp.starttls.enable", Boolean.toString(config.smtpStartTls()));
        props.put("mail.smtp.from", mailFrom());
        String localhost = config.smtpLocalhost();
        if (localhost != null && !localhost.isBlank()) {
            props.put("mail.smtp.localhost", localhost);
        }
        if (!endpoint.connectHost().equalsIgnoreCase(endpoint.serverName()) && config.smtpSsl()) {
            props.put("mail.smtp.ssl.socketFactory", new SniSocketFactory(endpoint.serverName()));
        }
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "30000");
        return props;
    }

    private String mailFrom() {
        String from = config.mailFrom();
        if (from != null && !from.isBlank()) return from;
        String user = config.smtpUser();
        return user != null ? user : "";
    }

    private String smtpPassword() {
        String pw = config.smtpPassword();
        return pw != null ? pw : "";
    }

    private int smtpPort() {
        String port = config.smtpPort();
        return port != null && !port.isBlank() ? Integer.parseInt(port) : 465;
    }

    private SmtpEndpoint smtpEndpoint() throws Exception {
        String configuredHost = config.smtpHost();
        String serverName = smtpServername(configuredHost, config.smtpUser());
        String connectHost = configuredHost;
        if (config.smtpResolveIpv4() && configuredHost != null && !isIpAddress(configuredHost)) {
            connectHost = firstIpv4Address(List.of(InetAddress.getAllByName(configuredHost)));
            if (connectHost.isBlank()) {
                connectHost = configuredHost;
            }
        }
        return new SmtpEndpoint(connectHost, serverName);
    }

    static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static long positiveOrDefault(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private static int positiveIntOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    static String messageIdHeader(String from, String smtpHost) {
        return "<" + UUID.randomUUID() + "." + Instant.now().toEpochMilli() + "@" + messageIdDomain(from, smtpHost) + ">";
    }

    static String messageIdDomain(String from, String smtpHost) {
        String email = ContactPointUtil.extractEmail(from);
        int at = email.lastIndexOf('@');
        if (at >= 0 && at + 1 < email.length()) {
            return email.substring(at + 1).toLowerCase();
        }
        String host = smtpHost == null ? "" : smtpHost.trim().toLowerCase();
        if (host.startsWith("smtp.") && host.length() > 5) {
            return host.substring(5);
        }
        return host.isBlank() ? "localhost.localdomain" : host;
    }

    static String smtpServername(String smtpHost, String smtpUsername) {
        String host = smtpHost == null ? "" : smtpHost.trim().toLowerCase();
        if (!isIpAddress(host)) {
            return host;
        }
        String email = ContactPointUtil.extractEmail(smtpUsername);
        int at = email.lastIndexOf('@');
        if (at >= 0 && at + 1 < email.length()) {
            return "smtp." + email.substring(at + 1).toLowerCase();
        }
        return host;
    }

    static String firstIpv4Address(List<InetAddress> addresses) {
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address) {
                return address.getHostAddress();
            }
        }
        return "";
    }

    static boolean isIpAddress(String value) {
        return value != null && value.trim().matches("(\\d{1,3}\\.){3}\\d{1,3}");
    }

    private record SmtpEndpoint(String connectHost, String serverName) {}

    static final class SniSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();
        private final String serverName;

        SniSocketFactory(String serverName) {
            this.serverName = serverName;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return applySni(delegate.createSocket(socket, host, port, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return applySni(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return applySni(delegate.createSocket(host, port, localHost, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return applySni(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return applySni(delegate.createSocket(address, port, localAddress, localPort));
        }

        private Socket applySni(Socket socket) {
            if (socket instanceof SSLSocket sslSocket && !isIpAddress(serverName)) {
                SSLParameters parameters = sslSocket.getSSLParameters();
                parameters.setServerNames(List.of(new SNIHostName(serverName)));
                sslSocket.setSSLParameters(parameters);
            }
            return socket;
        }
    }
}
