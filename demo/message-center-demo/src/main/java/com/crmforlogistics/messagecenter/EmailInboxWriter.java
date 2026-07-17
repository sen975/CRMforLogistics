package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class EmailInboxWriter {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Config config;

    public EmailInboxWriter(Config config) {
        this.config = config;
    }

    public boolean append(Message message, String direction) throws Exception {
        String normalizedDirection = normalizeDirection(direction);
        String from = addresses(message.getFrom());
        String to = firstNonBlank(
                addresses(message.getRecipients(Message.RecipientType.TO)),
                addresses(message.getRecipients(Message.RecipientType.CC)),
                addresses(message.getAllRecipients())
        );
        String contactSource = "out".equals(normalizedDirection) ? to : from;
        String contactEmail = ContactPointUtil.extractEmail(contactSource);
        String subject = decodeMimeText(message.getSubject());
        String bodyText = bodyText(message);
        String sentDate = message.getSentDate() == null ? "" : message.getSentDate().toInstant().toString();
        String messageId = firstHeader(message, "Message-ID");

        Map<String, String> record = new LinkedHashMap<>();
        record.put("id", UUID.randomUUID().toString());
        record.put("storedAt", Instant.now().toString());
        record.put("direction", normalizedDirection);
        record.put("contactEmail", contactEmail);
        record.put("contactName", ContactPointUtil.extractName(contactSource, contactEmail));
        record.put("from", from);
        record.put("to", "out".equals(normalizedDirection) ? to : config.value("IMAP_USERNAME", config.value("SMTP_USERNAME", "")));
        record.put("subject", subject);
        record.put("sentDate", sentDate);
        record.put("summary", summary(bodyText));
        record.put("bodyText", bodyText);
        record.put("messageId", messageId);
        return appendRecord(record);
    }

    private boolean appendRecord(Map<String, String> record) throws Exception {
        if (isDuplicate(record)) {
            return false;
        }
        if (config.emailInboxFile().getParent() != null) {
            Files.createDirectories(config.emailInboxFile().getParent());
        }
        Files.writeString(config.emailInboxFile(), GSON.toJson(record) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return true;
    }

    private boolean isDuplicate(Map<String, String> candidate) throws Exception {
        if (!Files.exists(config.emailInboxFile())) {
            return false;
        }
        String candidateMessageKey = messageKey(candidate);
        String candidateFallbackKey = fallbackKey(candidate);
        for (String line : Files.readAllLines(config.emailInboxFile(), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, String> existing = parseLine(line);
            String existingMessageKey = messageKey(existing);
            if (!candidateMessageKey.isBlank() && candidateMessageKey.equals(existingMessageKey)) {
                return true;
            }
            if (candidateMessageKey.isBlank() && candidateFallbackKey.equals(fallbackKey(existing))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> parseLine(String line) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            JsonObject object = JsonParser.parseString(line).getAsJsonObject();
            for (String key : object.keySet()) {
                result.put(key, JsonSupport.string(object, key));
            }
        } catch (RuntimeException ignored) {
        }
        return result;
    }

    private static String messageKey(Map<String, String> record) {
        String messageId = record.getOrDefault("messageId", "").trim().toLowerCase(Locale.ROOT);
        if (messageId.isBlank()) {
            return "";
        }
        return record.getOrDefault("direction", "") + "|" + messageId;
    }

    private static String fallbackKey(Map<String, String> record) {
        return record.getOrDefault("direction", "")
                + "|" + ContactPointUtil.extractEmail(record.getOrDefault("contactEmail", ""))
                + "|" + normalizeTextKey(record.getOrDefault("subject", ""))
                + "|" + normalizeTextKey(record.getOrDefault("sentDate", ""));
    }

    private static String addresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < addresses.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(address(addresses[i]));
        }
        return builder.toString();
    }

    private static String address(Address address) {
        if (address instanceof InternetAddress internetAddress) {
            String email = internetAddress.getAddress() == null ? "" : internetAddress.getAddress();
            String personal = decodeMimeText(internetAddress.getPersonal());
            if (!personal.isBlank() && !email.isBlank()) {
                return personal + " <" + email + ">";
            }
            return !email.isBlank() ? email : decodeMimeText(address.toString());
        }
        return decodeMimeText(address == null ? "" : address.toString());
    }

    private static String firstHeader(Message message, String name) throws Exception {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? "" : values[0];
    }

    private static String bodyText(Message message) throws Exception {
        return normalizeText(extractText(message));
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
        return html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n")
                .replaceAll("<[^>]+>", " ");
    }

    private static String summary(String value) {
        String text = normalizeText(value).replaceAll("\\s+", " ").trim();
        return text.length() > 240 ? text.substring(0, 240) + "..." : text;
    }

    private static String decodeMimeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return MimeUtility.decodeText(value).trim();
        } catch (Exception ignored) {
            return value.trim();
        }
    }

    private static String normalizeDirection(String direction) {
        String value = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        return "out".equals(value) || "outbound".equals(value) || "sent".equals(value) ? "out" : "in";
    }

    private static String normalizeText(String value) {
        return (value == null ? "" : value)
                .replace('\u00A0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String normalizeTextKey(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
