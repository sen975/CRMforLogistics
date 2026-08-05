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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class EmailInboxWriter {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Config config;
    private EmailAttachmentStore attachmentStore;

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
        String localMessageId = UUID.randomUUID().toString();
        List<StagedAttachment> staged = new ArrayList<>();
        List<EmailAttachment> attachments = new ArrayList<>();
        String bodyText = bodyText(message, staged, attachments);
        if (!staged.isEmpty()) {
            List<EmailAttachment> rejected = attachments.stream()
                    .filter(item -> "rejected".equals(item.state())).toList();
            try {
                List<EmailAttachment> stored = attachmentStore().publish(localMessageId, staged);
                attachments = new ArrayList<>(stored);
                attachments.addAll(rejected);
            } catch (EmailAttachmentStoreException exception) {
                attachmentStore().discard(staged);
                attachments.clear();
                addRejected(attachments, "attachment", "application/octet-stream", 0L,
                        exception.errorCode());
            }
        }
        String sentDate = message.getSentDate() == null ? "" : message.getSentDate().toInstant().toString();
        String messageId = firstHeader(message, "Message-ID");

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", localMessageId);
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
        record.put("attachments", attachments);
        try {
            return appendRecord(record);
        } catch (Exception exception) {
            if (!staged.isEmpty()) attachmentStore().deletePublished(localMessageId);
            throw exception;
        }
    }

    private boolean appendRecord(Map<String, Object> record) throws Exception {
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

    private boolean isDuplicate(Map<String, ?> candidate) throws Exception {
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

    private static String messageKey(Map<String, ?> record) {
        String messageId = value(record, "messageId").trim().toLowerCase(Locale.ROOT);
        if (messageId.isBlank()) {
            return "";
        }
        return value(record, "direction") + "|" + messageId;
    }

    private static String fallbackKey(Map<String, ?> record) {
        return value(record, "direction")
                + "|" + ContactPointUtil.extractEmail(value(record, "contactEmail"))
                + "|" + normalizeTextKey(value(record, "subject"))
                + "|" + normalizeTextKey(value(record, "sentDate"));
    }

    private static String value(Map<String, ?> record, String key) {
        Object value = record.get(key);
        return value == null ? "" : String.valueOf(value);
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

    private EmailAttachmentStore attachmentStore() throws IOException {
        if (attachmentStore == null) attachmentStore = new EmailAttachmentStore(config);
        return attachmentStore;
    }

    private String bodyText(Message message, List<StagedAttachment> staged,
                             List<EmailAttachment> attachments) throws Exception {
        StringBuilder body = new StringBuilder();
        extractText(message, body, staged, attachments, false);
        return normalizeText(body.toString());
    }

    private void extractText(Part part, StringBuilder body, List<StagedAttachment> staged,
                             List<EmailAttachment> attachments, boolean alternative) throws Exception {
        String disposition = part.getDisposition();
        if (Part.INLINE.equalsIgnoreCase(disposition)
                && (!decodeMimeText(part.getFileName()).isBlank() || part.getHeader("Content-ID") != null)) {
            drain(part, config.emailAttachmentMaxTotalBytes());
            return;
        }
        if (isAttachmentPart(part)) {
            saveAttachment(part, staged, attachments);
            return;
        }
        if (part.isMimeType("multipart/alternative")) {
            Multipart multipart = (Multipart) part.getContent();
            Part plain = null;
            Part html = null;
            for (int i = 0; i < multipart.getCount(); i++) {
                Part child = multipart.getBodyPart(i);
                if (isAttachmentPart(child)) {
                    saveAttachment(child, staged, attachments);
                } else if (child.isMimeType("text/plain") && plain == null) {
                    plain = child;
                } else if (child.isMimeType("text/html") && html == null) {
                    html = child;
                }
            }
            if (plain != null) extractText(plain, body, staged, attachments, true);
            else if (html != null) extractText(html, body, staged, attachments, true);
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                extractText(multipart.getBodyPart(i), body, staged, attachments, false);
            }
            return;
        }
        if (part.isMimeType("text/plain") || part.isMimeType("text/html")) {
            Object content = part.getContent();
            if (content != null) {
                body.append(part.isMimeType("text/html") ? htmlToText(content.toString()) : content).append(' ');
            }
        }
    }

    private void saveAttachment(Part part, List<StagedAttachment> staged,
                                List<EmailAttachment> attachments) throws Exception {
        String fileName = decodeMimeText(part.getFileName());
        String mimeType = part.getContentType().split(";", 2)[0].trim();
        if (fileName.isBlank()) fileName = "attachment";
        int storedCount = staged.size();
        if (storedCount >= config.emailAttachmentMaxCount()
                || attachments.stream().anyMatch(item -> "rejected".equals(item.state()))) {
            drain(part, config.emailAttachmentMaxTotalBytes());
            addRejected(attachments, fileName, mimeType, 0L, "EMAIL_ATTACHMENT_COUNT_LIMIT");
            return;
        }
        try {
            long remaining = config.emailAttachmentMaxTotalBytes() - staged.stream()
                    .mapToLong(StagedAttachment::sizeBytes).sum();
            if (remaining <= 0) {
                drain(part, config.emailAttachmentMaxTotalBytes());
                addRejected(attachments, fileName, mimeType, 0L, "EMAIL_ATTACHMENT_SIZE_LIMIT");
                return;
            }
            StagedAttachment item = attachmentStore().stage(part.getInputStream(), fileName, mimeType,
                    new AttachmentBudget(config.emailAttachmentMaxCount(), config.emailAttachmentMaxTotalBytes(),
                            config.emailAttachmentStorageMaxBytes()));
            if (staged.stream().mapToLong(StagedAttachment::sizeBytes).sum() + item.sizeBytes()
                    > config.emailAttachmentMaxTotalBytes()) {
                attachmentStore().discard(List.of(item));
                addRejected(attachments, fileName, mimeType, item.sizeBytes(), "EMAIL_ATTACHMENT_SIZE_LIMIT");
                return;
            }
            staged.add(item);
        } catch (EmailAttachmentStoreException exception) {
            addRejected(attachments, fileName, mimeType, 0L, exception.errorCode());
        } catch (Exception exception) {
            addRejected(attachments, fileName, mimeType, 0L, "EMAIL_ATTACHMENT_READ_FAILED");
        }
    }

    private static boolean isAttachmentPart(Part part) throws Exception {
        String disposition = part.getDisposition();
        String fileName = decodeMimeText(part.getFileName());
        if (Part.INLINE.equalsIgnoreCase(disposition)) return false;
        return Part.ATTACHMENT.equalsIgnoreCase(disposition) || !fileName.isBlank();
    }

    private static void addRejected(List<EmailAttachment> attachments, String fileName,
                                    String mimeType, long sizeBytes, String errorCode) {
        if (attachments.stream().noneMatch(item -> "rejected".equals(item.state()))) {
            attachments.add(new EmailAttachment(UUID.randomUUID().toString(), fileName, mimeType,
                    Math.max(0L, sizeBytes), null, "rejected", errorCode));
        }
    }

    private static void drain(Part part, long maxBytes) throws Exception {
        try (var input = part.getInputStream()) {
            byte[] buffer = new byte[8192];
            long readTotal = 0;
            for (int read; (read = input.read(buffer)) >= 0 && readTotal < maxBytes;) {
                if (read > 0) readTotal += read;
            }
        }
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
