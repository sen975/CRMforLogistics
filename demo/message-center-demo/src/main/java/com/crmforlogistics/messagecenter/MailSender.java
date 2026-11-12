package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.SendFailedException;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.activation.DataHandler;
import jakarta.activation.FileDataSource;
import jakarta.mail.internet.MimeMessage;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class MailSender {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Config config;
    private final MailTransport injectedTransport;
    private EmailAttachmentStore attachmentStore;

    public MailSender(Config config) {
        this(config, null);
    }

    MailSender(Config config, MailTransport injectedTransport) {
        this(config, injectedTransport, null);
    }

    MailSender(Config config, MailTransport injectedTransport, EmailAttachmentStore attachmentStore) {
        this.config = config;
        this.injectedTransport = injectedTransport;
        this.attachmentStore = attachmentStore;
    }

    public UnifiedMessage send(String to, String subject, String body) throws Exception {
        return send(new EmailSendCommand(to, subject, body, UUID.randomUUID().toString(), List.of()));
    }

    public UnifiedMessage send(EmailSendCommand command) throws Exception {
        if (command == null) throw new IllegalArgumentException("command is required");
        try {
            String cleanTo = required(command.to(), "to");
            String subject = command.subject() == null ? "" : command.subject();
            String body = command.body() == null ? "" : command.body();
            validateAttachmentBudget(command.attachments());
            return sendValidated(command, cleanTo, subject, body);
        } catch (EmailSendException exception) {
            throw exception;
        } catch (Exception exception) {
            attachmentStore().discard(command.attachments());
            throw exception;
        }
    }

    private UnifiedMessage sendValidated(EmailSendCommand command, String cleanTo,
                                         String subject, String body) throws Exception {
        SmtpEndpoint endpoint = smtpEndpoint();
        Session session = Session.getInstance(smtpProperties(endpoint));
        MimeMessage message = new MimeMessage(session);
        try {
            String fromName = config.value("MAIL_FROM_NAME", "").trim();
            if (fromName.isBlank()) {
                message.setFrom(new InternetAddress(mailFrom()));
            } else {
                message.setFrom(new InternetAddress(mailFrom(), fromName, "UTF-8"));
            }
        } catch (UnsupportedEncodingException ex) {
            throw new MessagingException("Failed to encode sender name", ex);
        }
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(cleanTo, false));
        message.setSubject(subject, "UTF-8");
        if (command.attachments().isEmpty()) {
            message.setText(body, "UTF-8");
        } else {
            MimeMultipart multipart = new MimeMultipart("mixed");
            MimeBodyPart text = new MimeBodyPart();
            text.setText(body, "UTF-8");
            multipart.addBodyPart(text);
            for (StagedAttachment attachment : command.attachments()) {
                MimeBodyPart part = new MimeBodyPart();
                part.setDataHandler(new DataHandler(new FileDataSource(attachment.temporaryPath().toFile())));
                part.setFileName(MimeUtility.encodeText(attachment.fileName(), "UTF-8", null));
                part.setHeader("Content-Type", attachment.mimeType());
                multipart.addBodyPart(part);
            }
            message.setContent(multipart);
        }
        message.saveChanges();
        message.setHeader("Message-ID", messageIdHeader(mailFrom(), endpoint.serverName()));
        EmailRecoveryJournal journal = new EmailRecoveryJournal(config.emailDataDir());
        EmailAttachmentStore attachmentStore = attachmentStore();
        journal.prepare(command);
        try {
            if (injectedTransport != null) {
                injectedTransport.send(message, message.getAllRecipients());
            } else {
                try (Transport transport = session.getTransport("smtp")) {
                    transport.connect(endpoint.connectHost(), smtpPort(),
                            config.value("SMTP_USERNAME", ""), config.value("SMTP_PASSWORD", ""));
                    transport.sendMessage(message, message.getAllRecipients());
                }
            }
        } catch (SendFailedException exception) {
            if (exception.getValidSentAddresses() != null && exception.getValidSentAddresses().length > 0) {
                throw new EmailSendException("EMAIL_SEND_OUTCOME_UNKNOWN", exception);
            }
            journal.remove(command.messageId());
            attachmentStore.discard(command.attachments());
            throw exception;
        } catch (Exception exception) {
            throw new EmailSendException("EMAIL_SEND_OUTCOME_UNKNOWN", exception);
        }

        List<EmailAttachment> attachments;
        String messageId = message.getMessageID() == null ? "" : message.getMessageID();
        try {
            journal.accepted(command, messageId);
            attachments = command.attachments().isEmpty()
                    ? List.of() : attachmentStore.publish(command.messageId(), command.attachments());
            appendOutgoing(cleanTo, subject, body, command.messageId(), messageId, attachments);
            journal.remove(command.messageId());
        } catch (Exception exception) {
            throw new EmailSendException("EMAIL_SENT_HISTORY_FAILED", exception);
        }
        UnifiedMessage stored = new UnifiedMessageStore(config).thread("email:" + cleanTo).stream()
                .filter(item -> messageId.isBlank() || messageId.equals(extractEmailMessageId(item.raw)))
                .reduce((first, second) -> second)
                .orElse(null);
        return stored == null ? localResult(cleanTo, subject, body, messageId, attachments) : stored;
    }

    int reconcile(int maxEntries) throws Exception {
        EmailRecoveryJournal journal = new EmailRecoveryJournal(config.emailDataDir());
        List<RecoveryEntry> entries = journal.entries(maxEntries);
        Set<java.nio.file.Path> protectedDirectories = new HashSet<>();
        for (RecoveryEntry entry : entries) {
            for (StagedAttachment attachment : entry.stagedAttachments()) {
                if (attachment.temporaryPath() != null) protectedDirectories.add(attachment.temporaryPath().getParent());
            }
        }
        int recovered = 0;
        for (RecoveryEntry entry : entries) {
            if (!"accepted".equals(entry.state())) continue;
            if (isDuplicate(entry.smtpMessageId())) {
                journal.remove(entry.messageId());
                recovered++;
                continue;
            }
            List<StagedAttachment> staged = entry.stagedAttachments();
            List<EmailAttachment> attachments = List.of();
            if (!staged.isEmpty()) {
                try {
                    attachments = attachmentStore().describePublished(entry.messageId(), staged);
                } catch (EmailAttachmentStoreException exception) {
                    if (!"EMAIL_ATTACHMENT_NOT_FOUND".equals(exception.errorCode())) throw exception;
                    attachments = attachmentStore().publish(entry.messageId(), staged);
                }
            }
            appendOutgoing(entry.to(), entry.subject(), entry.body(), entry.messageId(),
                    entry.smtpMessageId(), attachments);
            journal.remove(entry.messageId());
            recovered++;
        }
        attachmentStore().reconcile(maxEntries, protectedDirectories);
        return recovered;
    }

    private EmailAttachmentStore attachmentStore() throws IOException {
        if (attachmentStore == null) {
            attachmentStore = new EmailAttachmentStore(config);
        }
        return attachmentStore;
    }

    private void validateAttachmentBudget(List<StagedAttachment> attachments) throws Exception {
        String errorCode = null;
        if (attachments.size() > config.emailAttachmentMaxCount()) {
            errorCode = "EMAIL_ATTACHMENT_COUNT_LIMIT";
        } else {
            long totalBytes = 0;
            try {
                for (StagedAttachment attachment : attachments) {
                    if (attachment == null || attachment.sizeBytes() < 0) {
                        errorCode = "EMAIL_ATTACHMENT_SIZE_LIMIT";
                        break;
                    }
                    totalBytes = Math.addExact(totalBytes, attachment.sizeBytes());
                }
            } catch (ArithmeticException overflow) {
                errorCode = "EMAIL_ATTACHMENT_SIZE_LIMIT";
            }
            if (errorCode == null && totalBytes > config.emailAttachmentMaxTotalBytes()) {
                errorCode = "EMAIL_ATTACHMENT_SIZE_LIMIT";
            }
        }
        if (errorCode != null) {
            attachmentStore().discard(attachments);
            throw new EmailSendException(errorCode, null);
        }
    }

    private void appendOutgoing(String to, String subject, String body, String localMessageId, String messageId,
                                List<EmailAttachment> attachments) throws Exception {
        if (config.emailInboxFile().getParent() != null) {
            Files.createDirectories(config.emailInboxFile().getParent());
        }
        Map<String, Object> record = new LinkedHashMap<>();
        // The JSONL row id is also the attachment directory key used by the
        // download route; keep both sides bound to the same stable message id.
        record.put("id", localMessageId == null || localMessageId.isBlank() ? UUID.randomUUID().toString() : localMessageId);
        record.put("storedAt", Instant.now().toString());
        record.put("direction", "out");
        record.put("contactEmail", ContactPointUtil.extractEmail(to));
        record.put("contactName", ContactPointUtil.extractName(to, ContactPointUtil.extractEmail(to)));
        record.put("from", mailFrom());
        record.put("to", to);
        record.put("subject", subject == null ? "" : subject);
        record.put("sentDate", Instant.now().toString());
        record.put("summary", body == null ? "" : body);
        record.put("bodyText", body == null ? "" : body);
        record.put("messageId", messageId == null ? "" : messageId);
        record.put("attachments", attachments);
        if (isDuplicate(messageId)) {
            return;
        }
        Files.writeString(config.emailInboxFile(), GSON.toJson(record) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private boolean isDuplicate(String messageId) throws Exception {
        if (messageId == null || messageId.isBlank() || !Files.exists(config.emailInboxFile())) {
            return false;
        }
        for (String line : Files.readAllLines(config.emailInboxFile(), StandardCharsets.UTF_8)) {
            if (line.contains("\"messageId\":\"" + escapeForContains(messageId) + "\"")) {
                return true;
            }
        }
        return false;
    }

    private UnifiedMessage localResult(String to, String subject, String body, String messageId,
                                       List<EmailAttachment> attachments) {
        UnifiedMessage message = new UnifiedMessage();
        message.id = "email:local-" + UUID.randomUUID();
        message.sourceId = messageId == null ? "" : messageId;
        message.channel = "email";
        message.direction = "outbound";
        message.timestamp = Instant.now().toString();
        message.contactPointId = ContactPointUtil.normalizePointId("email:" + to);
        message.from = mailFrom();
        message.to = to;
        message.title = subject == null ? "" : subject;
        message.text = body == null ? "" : body;
        message.bodyText = message.text;
        message.attachments = attachments == null ? List.of() : List.copyOf(attachments);
        return message;
    }

    @FunctionalInterface
    interface MailTransport {
        void send(MimeMessage message, jakarta.mail.Address[] recipients) throws MessagingException;
    }

    private Properties smtpProperties(SmtpEndpoint endpoint) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", endpoint.connectHost());
        props.put("mail.smtp.port", Integer.toString(smtpPort()));
        props.put("mail.smtp.ssl.enable", Boolean.toString(bool("SMTP_SSL", true)));
        props.put("mail.smtp.starttls.enable", Boolean.toString(bool("SMTP_STARTTLS", false)));
        props.put("mail.smtp.from", mailFrom());
        String smtpLocalhost = config.value("SMTP_LOCALHOST", "").trim();
        if (!smtpLocalhost.isBlank()) {
            props.put("mail.smtp.localhost", smtpLocalhost);
        }
        if (!endpoint.connectHost().equalsIgnoreCase(endpoint.serverName()) && bool("SMTP_SSL", true)) {
            props.put("mail.smtp.ssl.socketFactory", new SniSocketFactory(endpoint.serverName()));
        }
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "30000");
        return props;
    }

    private String mailFrom() {
        return config.value("MAIL_FROM", config.value("SMTP_USERNAME", ""));
    }

    private int smtpPort() {
        return Integer.parseInt(config.value("SMTP_PORT", "465"));
    }

    private boolean bool(String key, boolean fallback) {
        String value = config.value(key, Boolean.toString(fallback));
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private SmtpEndpoint smtpEndpoint() throws Exception {
        String configuredHost = config.value("SMTP_HOST", "");
        String serverName = smtpServername(configuredHost, config.value("SMTP_USERNAME", ""));
        String connectHost = configuredHost;
        if (bool("SMTP_RESOLVE_IPV4", true) && !isIpAddress(configuredHost)) {
            connectHost = firstIpv4Address(List.of(InetAddress.getAllByName(configuredHost)));
            if (connectHost.isBlank()) {
                connectHost = configuredHost;
            }
        }
        return new SmtpEndpoint(connectHost, serverName);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String extractEmailMessageId(String raw) {
        if (raw == null) {
            return "";
        }
        int marker = raw.indexOf("\"messageId\":\"");
        if (marker < 0) {
            return "";
        }
        int start = marker + "\"messageId\":\"".length();
        int end = raw.indexOf('"', start);
        return end < 0 ? "" : raw.substring(start, end);
    }

    private static String escapeForContains(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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

    private static final class SniSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();
        private final String serverName;

        private SniSocketFactory(String serverName) {
            this.serverName = serverName;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return applySni(delegate.createSocket(socket, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return applySni(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return applySni(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return applySni(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
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

class EmailSendException extends Exception {
    private final String errorCode;

    EmailSendException(String errorCode, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    String errorCode() {
        return errorCode;
    }
}
