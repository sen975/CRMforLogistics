package com.crmforlogistics.emaildemo;

import jakarta.mail.Message;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InboxStore {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);

    private final MailConfig config;

    public InboxStore(MailConfig config) {
        this.config = config;
    }

    public void append(Message message) throws Exception {
        String from = MailPrinter.addresses(message.getFrom());
        appendMessage(message, "in", from, config.imapUsername(), from);
    }

    public void appendSent(Message message) throws Exception {
        String from = MailPrinter.addresses(message.getFrom());
        String to = firstNonBlank(
                MailPrinter.addresses(message.getRecipients(Message.RecipientType.TO)),
                MailPrinter.addresses(message.getRecipients(Message.RecipientType.CC)),
                MailPrinter.addresses(message.getAllRecipients())
        );
        appendMessage(message, "out", from, to, to);
    }

    private void appendMessage(Message message, String direction, String from, String to, String contactSource) throws Exception {
        String contactEmail = extractEmail(contactSource);
        String contactName = extractName(contactSource, contactEmail);
        appendRecord(new InboxRecord(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                direction,
                contactEmail,
                contactName,
                from,
                to,
                MailPrinter.subject(message),
                message.getSentDate() == null ? "" : message.getSentDate().toString(),
                MailPrinter.summary(message),
                MailPrinter.bodyText(message),
                firstHeader(message, "Message-ID")
        ));
    }

    public void appendOutgoing(String to, String subject, String bodyText, String messageId) throws Exception {
        String contactEmail = extractEmail(to);
        String contactName = extractName(to, contactEmail);
        appendRecord(new InboxRecord(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                "out",
                contactEmail,
                contactName,
                config.mailFrom(),
                to,
                MailPrinter.normalizeStoredText(subject),
                Instant.now().toString(),
                MailPrinter.normalizeStoredText(bodyText),
                MailPrinter.normalizeStoredText(bodyText),
                messageId == null ? "" : messageId
        ));
    }

    public List<ContactRecord> contacts() throws Exception {
        Map<String, List<String>> groups = contactGroups();
        Map<String, ContactAccumulator> contacts = new LinkedHashMap<>();
        for (InboxRecord record : chronologicalList()) {
            String recordEmail = emptyTo(record.contactEmail(), "(unknown)");
            String email = primaryFor(recordEmail, groups);
            ContactAccumulator acc = contacts.computeIfAbsent(email, key -> new ContactAccumulator(email));
            if (normalizeEmail(recordEmail).equals(normalizeEmail(email)) || acc.name == null || acc.name.isBlank()) {
                acc.name = firstNonBlank(record.contactName(), record.contactEmail(), email);
            }
            acc.lastSubject = record.subject();
            acc.lastTime = firstNonBlank(record.sentDate(), record.storedAt());
            acc.messageCount++;
        }
        List<ContactRecord> result = new ArrayList<>();
        for (ContactAccumulator acc : contacts.values()) {
            result.add(new ContactRecord(acc.email, acc.name, acc.lastSubject, acc.lastTime, acc.messageCount));
        }
        Collections.reverse(result);
        return result;
    }

    public List<InboxRecord> listByContact(String contactEmail) throws Exception {
        Set<String> groupEmails = new LinkedHashSet<>();
        for (String email : contactGroup(contactEmail)) {
            groupEmails.add(normalizeEmail(email));
        }
        List<InboxRecord> result = new ArrayList<>();
        for (InboxRecord record : chronologicalList()) {
            if (groupEmails.contains(normalizeEmail(record.contactEmail()))) {
                result.add(record);
            }
        }
        return result;
    }

    public List<String> contactGroup(String contactEmail) throws Exception {
        String normalized = normalizeEmail(contactEmail);
        Map<String, List<String>> groups = contactGroups();
        String primary = primaryFor(normalized, groups);
        List<String> emails = groups.get(primary);
        if (emails == null || emails.isEmpty()) {
            return new ArrayList<>(List.of(normalized));
        }
        return new ArrayList<>(emails);
    }

    public void mergeContacts(String primaryEmail, String mergedEmail) throws Exception {
        String primary = normalizeEmail(primaryEmail);
        String merged = normalizeEmail(mergedEmail);
        if (primary.isBlank() || merged.isBlank() || primary.equals(merged)) {
            return;
        }
        Map<String, List<String>> groups = contactGroups();
        String primaryRoot = primaryFor(primary, groups);
        String mergedRoot = primaryFor(merged, groups);
        LinkedHashSet<String> emails = new LinkedHashSet<>();
        emails.add(primaryRoot);
        emails.addAll(groups.getOrDefault(primaryRoot, List.of(primaryRoot)));
        emails.add(mergedRoot);
        emails.addAll(groups.getOrDefault(mergedRoot, List.of(mergedRoot)));
        groups.remove(primaryRoot);
        groups.remove(mergedRoot);
        List<String> result = new ArrayList<>();
        result.add(primaryRoot);
        for (String email : emails) {
            if (!email.equals(primaryRoot)) {
                result.add(email);
            }
        }
        groups.put(primaryRoot, result);
        writeContactGroups(groups);
    }

    public void splitContact(String primaryEmail, String emailToSplit) throws Exception {
        String primary = normalizeEmail(primaryEmail);
        String email = normalizeEmail(emailToSplit);
        if (primary.isBlank() || email.isBlank()) {
            return;
        }
        Map<String, List<String>> groups = contactGroups();
        String primaryRoot = primaryFor(primary, groups);
        List<String> emails = new ArrayList<>(groups.getOrDefault(primaryRoot, List.of(primaryRoot)));
        emails.removeIf(item -> normalizeEmail(item).equals(email));
        if (emails.size() <= 1) {
            groups.remove(primaryRoot);
        } else {
            if (normalizeEmail(emails.get(0)).equals(email)) {
                emails.remove(0);
            }
            groups.put(primaryRoot, emails);
        }
        writeContactGroups(groups);
    }

    public List<InboxRecord> list() throws Exception {
        List<InboxRecord> records = chronologicalList();
        Collections.reverse(records);
        return records;
    }

    public InboxRecord findById(String id) throws Exception {
        for (InboxRecord record : chronologicalList()) {
            if (record.id().equals(id)) {
                return record;
            }
        }
        return null;
    }

    private void appendRecord(InboxRecord record) throws Exception {
        Files.createDirectories(config.dataDir());
        Path file = config.dataDir().resolve("inbox.jsonl");
        if (isDuplicate(record)) {
            System.out.println("[INFO] Skip duplicate email: " + record.subject());
            return;
        }
        String json = "{"
                + "\"id\":\"" + escape(record.id()) + "\","
                + "\"storedAt\":\"" + escape(record.storedAt()) + "\","
                + "\"direction\":\"" + escape(record.direction()) + "\","
                + "\"contactEmail\":\"" + escape(record.contactEmail()) + "\","
                + "\"contactName\":\"" + escape(record.contactName()) + "\","
                + "\"from\":\"" + escape(record.from()) + "\","
                + "\"to\":\"" + escape(record.to()) + "\","
                + "\"subject\":\"" + escape(record.subject()) + "\","
                + "\"sentDate\":\"" + escape(record.sentDate()) + "\","
                + "\"summary\":\"" + escape(record.summary()) + "\","
                + "\"bodyText\":\"" + escape(record.bodyText()) + "\","
                + "\"messageId\":\"" + escape(record.messageId()) + "\""
                + "}";
        Files.writeString(file, json + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private boolean isDuplicate(InboxRecord candidate) throws Exception {
        String candidateMessageKey = messageKey(candidate);
        String candidateFallbackKey = fallbackKey(candidate);
        for (InboxRecord existing : chronologicalList()) {
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

    private List<InboxRecord> chronologicalList() throws Exception {
        Path file = config.dataDir().resolve("inbox.jsonl");
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        List<InboxRecord> records = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, String> item = parseFlatJson(line);
            InboxRecord record = normalizeRecord(item);
            records.add(record);
        }
        records.sort(Comparator.comparing(InboxStore::recordSortInstant));
        return records;
    }

    private Map<String, List<String>> contactGroups() throws Exception {
        Path file = config.dataDir().resolve("contact-groups.jsonl");
        Map<String, List<String>> groups = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return groups;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, String> item = parseFlatJson(line);
            String primary = normalizeEmail(item.get("primaryEmail"));
            if (primary.isBlank()) {
                continue;
            }
            LinkedHashSet<String> emails = new LinkedHashSet<>();
            emails.add(primary);
            for (String email : parseJsonStringArray(line, "emails")) {
                String normalized = normalizeEmail(email);
                if (!normalized.isBlank()) {
                    emails.add(normalized);
                }
            }
            if (emails.size() > 1) {
                groups.put(primary, new ArrayList<>(emails));
            }
        }
        return groups;
    }

    private void writeContactGroups(Map<String, List<String>> groups) throws Exception {
        Files.createDirectories(config.dataDir());
        Path file = config.dataDir().resolve("contact-groups.jsonl");
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            LinkedHashSet<String> emails = new LinkedHashSet<>();
            for (String email : entry.getValue()) {
                String normalized = normalizeEmail(email);
                if (!normalized.isBlank()) {
                    emails.add(normalized);
                }
            }
            if (emails.size() <= 1) {
                continue;
            }
            String primary = normalizeEmail(entry.getKey());
            if (primary.isBlank()) {
                primary = emails.iterator().next();
            }
            StringBuilder json = new StringBuilder("{\"primaryEmail\":\"")
                    .append(escape(primary))
                    .append("\",\"emails\":[");
            int i = 0;
            for (String email : emails) {
                if (i > 0) {
                    json.append(',');
                }
                json.append('"').append(escape(email)).append('"');
                i++;
            }
            json.append("]}");
            lines.add(json.toString());
        }
        Files.write(file, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String primaryFor(String contactEmail, Map<String, List<String>> groups) {
        String normalized = normalizeEmail(contactEmail);
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            for (String email : entry.getValue()) {
                if (normalizeEmail(email).equals(normalized)) {
                    return normalizeEmail(entry.getKey());
                }
            }
        }
        return normalized;
    }


    private static Instant recordSortInstant(InboxRecord record) {
        Instant sent = parseMailInstant(record.sentDate());
        if (sent != null) {
            return sent;
        }
        Instant stored = parseMailInstant(record.storedAt());
        return stored == null ? Instant.EPOCH : stored;
    }

    private static Instant parseMailInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (Exception ignored) {
            // Fall through to java.util.Date#toString format used by Jakarta Mail.
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH);
            Date parsed = format.parse(value.trim());
            return parsed == null ? null : parsed.toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }
    private InboxRecord normalizeRecord(Map<String, String> item) {
        String direction = firstNonBlank(item.get("direction"), "in");
        String from = MailPrinter.normalizeStoredText(item.getOrDefault("from", ""));
        String to = MailPrinter.normalizeStoredText(item.getOrDefault("to", ""));
        String rawContact = "out".equals(direction) ? firstNonBlank(to, item.get("contactEmail")) : firstNonBlank(item.get("contactEmail"), from);
        String contactEmail = extractEmail(rawContact);
        String contactName = firstNonBlank(
                MailPrinter.normalizeStoredText(item.getOrDefault("contactName", "")),
                extractName(rawContact, contactEmail),
                contactEmail
        );
        return new InboxRecord(
                item.getOrDefault("id", ""),
                item.getOrDefault("storedAt", ""),
                direction,
                contactEmail,
                contactName,
                from,
                to,
                MailPrinter.normalizeStoredText(item.getOrDefault("subject", "")),
                item.getOrDefault("sentDate", ""),
                MailPrinter.normalizeStoredText(item.getOrDefault("summary", "")),
                MailPrinter.normalizeStoredText(item.getOrDefault("bodyText", "")),
                item.getOrDefault("messageId", "")
        );
    }

    private static String messageKey(InboxRecord record) {
        if (record.messageId() == null || record.messageId().isBlank()) {
            return "";
        }
        return record.direction() + "|" + record.messageId().trim().toLowerCase(Locale.ROOT);
    }

    private static String fallbackKey(InboxRecord record) {
        return record.direction()
                + "|" + normalizeEmail(record.contactEmail())
                + "|" + normalizeTextKey(record.subject())
                + "|" + normalizeTextKey(record.sentDate());
    }

    static String extractEmail(String value) {
        if (value == null) {
            return "";
        }
        Matcher matcher = EMAIL_PATTERN.matcher(value);
        return matcher.find() ? matcher.group().toLowerCase(Locale.ROOT) : value.trim();
    }

    static String extractName(String value, String email) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String result = value.replace("<" + email + ">", "").replace(email, "").trim();
        if (result.startsWith("\"") && result.endsWith("\"") && result.length() > 1) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result.isBlank() ? email : result;
    }

    private static String firstHeader(Message message, String name) throws Exception {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? "" : values[0];
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String emptyTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String normalizeEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeTextKey(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String escape(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    static Map<String, String> parseFlatJson(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        int i = 0;
        while (i < json.length()) {
            int keyStart = json.indexOf('"', i);
            if (keyStart < 0) {
                break;
            }
            int keyEnd = findStringEnd(json, keyStart + 1);
            if (keyEnd < 0) {
                break;
            }
            String key = unescape(json.substring(keyStart + 1, keyEnd));
            int colon = json.indexOf(':', keyEnd);
            if (colon < 0) {
                break;
            }
            int valueStart = json.indexOf('"', colon + 1);
            if (valueStart < 0) {
                break;
            }
            int valueEnd = findStringEnd(json, valueStart + 1);
            if (valueEnd < 0) {
                break;
            }
            result.put(key, unescape(json.substring(valueStart + 1, valueEnd)));
            i = valueEnd + 1;
        }
        return result;
    }

    static List<String> parseJsonStringArray(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyStart = json.indexOf(marker);
        if (keyStart < 0) {
            return new ArrayList<>();
        }
        int arrayStart = json.indexOf('[', keyStart + marker.length());
        if (arrayStart < 0) {
            return new ArrayList<>();
        }
        int arrayEnd = json.indexOf(']', arrayStart + 1);
        if (arrayEnd < 0) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        int i = arrayStart + 1;
        while (i < arrayEnd) {
            int valueStart = json.indexOf('"', i);
            if (valueStart < 0 || valueStart >= arrayEnd) {
                break;
            }
            int valueEnd = findStringEnd(json, valueStart + 1);
            if (valueEnd < 0 || valueEnd > arrayEnd) {
                break;
            }
            result.add(unescape(json.substring(valueStart + 1, valueEnd)));
            i = valueEnd + 1;
        }
        return result;
    }

    private static int findStringEnd(String text, int start) {
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String value) {
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!escaped) {
                if (c == '\\') {
                    escaped = true;
                } else {
                    builder.append(c);
                }
                continue;
            }
            switch (c) {
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                default -> builder.append(c);
            }
            escaped = false;
        }
        return builder.toString();
    }

    private static class ContactAccumulator {
        private final String email;
        private String name;
        private String lastSubject;
        private String lastTime;
        private int messageCount;

        private ContactAccumulator(String email) {
            this.email = email;
        }
    }
}
