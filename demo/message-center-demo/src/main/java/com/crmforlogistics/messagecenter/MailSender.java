package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
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
        Session session = Session.getInstance(smtpProperties());
        MimeMessage message = new MimeMessage(session);
        try {
            message.setFrom(new InternetAddress(mailFrom(), config.value("MAIL_FROM_NAME", "CRM Logistics Message Center"), "UTF-8"));
        } catch (UnsupportedEncodingException ex) {
            throw new MessagingException("Failed to encode sender name", ex);
        }
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(cleanTo, false));
        message.setSubject(subject == null ? "" : subject, "UTF-8");
        message.setText(body == null ? "" : body, "UTF-8");
        message.saveChanges();

        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(config.value("SMTP_HOST", ""), smtpPort(),
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

    private Properties smtpProperties() {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", config.value("SMTP_HOST", ""));
        props.put("mail.smtp.port", Integer.toString(smtpPort()));
        props.put("mail.smtp.ssl.enable", Boolean.toString(bool("SMTP_SSL", true)));
        props.put("mail.smtp.starttls.enable", Boolean.toString(bool("SMTP_STARTTLS", false)));
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
}
