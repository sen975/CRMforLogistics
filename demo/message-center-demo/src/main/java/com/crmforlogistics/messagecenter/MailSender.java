package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
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
import java.util.UUID;

public class MailSender {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Config config;

    public MailSender(Config config) {
        this.config = config;
    }

    public UnifiedMessage send(String to, String subject, String body) throws Exception {
        String cleanTo = required(to, "to");
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
        message.setSubject(subject == null ? "" : subject, "UTF-8");
        message.setText(body == null ? "" : body, "UTF-8");
        message.saveChanges();
        message.setHeader("Message-ID", messageIdHeader(mailFrom(), endpoint.serverName()));

        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(endpoint.connectHost(), smtpPort(),
                    config.value("SMTP_USERNAME", ""), config.value("SMTP_PASSWORD", ""));
            transport.sendMessage(message, message.getAllRecipients());
        }

        String messageId = message.getMessageID() == null ? "" : message.getMessageID();
        appendOutgoing(cleanTo, subject, body, messageId);
        UnifiedMessage stored = new UnifiedMessageStore(config).thread("email:" + cleanTo).stream()
                .filter(item -> messageId.isBlank() || messageId.equals(extractEmailMessageId(item.raw)))
                .reduce((first, second) -> second)
                .orElse(null);
        return stored == null ? localResult(cleanTo, subject, body, messageId) : stored;
    }

    private void appendOutgoing(String to, String subject, String body, String messageId) throws Exception {
        if (config.emailInboxFile().getParent() != null) {
            Files.createDirectories(config.emailInboxFile().getParent());
        }
        Map<String, String> record = new LinkedHashMap<>();
        record.put("id", UUID.randomUUID().toString());
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

    private UnifiedMessage localResult(String to, String subject, String body, String messageId) {
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
        return message;
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
