package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class UnifiedMessageStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Config config;
    private final TemplateStore templateStore;

    public UnifiedMessageStore(Config config) {
        this.config = config;
        this.templateStore = new TemplateStore(config.chatappTemplateFile());
    }

    public List<UnifiedContact> contacts() throws IOException {
        Map<String, List<String>> groups = contactGroups();
        Map<String, ContactAccumulator> byPrimary = new LinkedHashMap<>();
        for (UnifiedMessage message : messages()) {
            if (message.contactPointId == null || message.contactPointId.isBlank()) {
                continue;
            }
            String primary = primaryFor(message.contactPointId, groups);
            ContactAccumulator acc = byPrimary.computeIfAbsent(primary, ContactAccumulator::new);
            acc.add(message);
        }
        List<UnifiedContact> result = new ArrayList<>();
        for (ContactAccumulator acc : byPrimary.values()) {
            result.add(acc.toContact(groups.getOrDefault(acc.primaryPointId, List.of(acc.primaryPointId))));
        }
        result.sort(Comparator
                .comparing((UnifiedContact contact) -> MessageTime.parseInstant(contact.lastTime))
                .reversed()
                .thenComparing(contact -> ContactPointUtil.firstNonBlank(contact.displayName, contact.id)));
        return result;
    }

    public List<UnifiedMessage> messages() throws IOException {
        List<UnifiedMessage> result = new ArrayList<>();
        result.addAll(readEmailMessages());
        result.addAll(readChatAppMessages());
        result.sort(Comparator
                .comparing((UnifiedMessage message) -> MessageTime.parseInstant(message.timestamp))
                .thenComparing(message -> ContactPointUtil.firstNonBlank(message.id, message.sourceId)));
        return result;
    }

    public List<UnifiedMessage> thread(String contactPointId) throws IOException {
        Set<String> points = new LinkedHashSet<>(contactGroup(contactPointId));
        List<UnifiedMessage> result = new ArrayList<>();
        for (UnifiedMessage message : messages()) {
            if (points.contains(ContactPointUtil.normalizePointId(message.contactPointId))) {
                result.add(message);
            }
        }
        result.sort(Comparator
                .comparing((UnifiedMessage message) -> MessageTime.parseInstant(message.timestamp))
                .thenComparing(message -> ContactPointUtil.firstNonBlank(message.id, message.sourceId)));
        return result;
    }

    public UnifiedMessage findMessage(String id) throws IOException {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (UnifiedMessage message : messages()) {
            if (id.equals(message.id) || id.equals(message.sourceId)) {
                return message;
            }
        }
        return null;
    }

    public List<String> contactGroup(String contactPointId) throws IOException {
        String point = ContactPointUtil.normalizePointId(contactPointId);
        Map<String, List<String>> groups = contactGroups();
        String primary = primaryFor(point, groups);
        return new ArrayList<>(groups.getOrDefault(primary, List.of(primary)));
    }

    public void mergeContacts(String primaryPointId, String mergedPointId) throws IOException {
        String primary = ContactPointUtil.normalizePointId(primaryPointId);
        String merged = ContactPointUtil.normalizePointId(mergedPointId);
        if (primary.isBlank() || merged.isBlank() || primary.equals(merged)) {
            return;
        }
        Map<String, List<String>> groups = contactGroups();
        String primaryRoot = primaryFor(primary, groups);
        String mergedRoot = primaryFor(merged, groups);
        LinkedHashSet<String> points = new LinkedHashSet<>();
        points.add(primary);
        points.addAll(groups.getOrDefault(primaryRoot, List.of(primaryRoot)));
        points.add(merged);
        points.addAll(groups.getOrDefault(mergedRoot, List.of(mergedRoot)));
        points.remove("");
        groups.remove(primaryRoot);
        groups.remove(mergedRoot);
        groups.put(primary, new ArrayList<>(points));
        writeContactGroups(groups);
    }

    public void splitContact(String primaryPointId, String pointToSplit) throws IOException {
        String primary = ContactPointUtil.normalizePointId(primaryPointId);
        String split = ContactPointUtil.normalizePointId(pointToSplit);
        if (primary.isBlank() || split.isBlank()) {
            return;
        }
        Map<String, List<String>> groups = contactGroups();
        String root = primaryFor(primary, groups);
        List<String> points = new ArrayList<>(groups.getOrDefault(root, List.of(root)));
        points.removeIf(point -> Objects.equals(ContactPointUtil.normalizePointId(point), split));
        groups.remove(root);
        if (points.size() > 1) {
            String newRoot = points.contains(root) ? root : points.get(0);
            groups.put(newRoot, points);
        }
        writeContactGroups(groups);
    }

    public ChannelCapability channelCapability(String channel) {
        String normalized = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        if ("email".equals(normalized)) {
            boolean available = !config.value("SMTP_HOST", "").isBlank()
                    && !config.value("SMTP_USERNAME", "").isBlank()
                    && !config.value("SMTP_PASSWORD", "").isBlank();
            return new ChannelCapability("email", available,
                    available ? "" : "missing SMTP_HOST/SMTP_USERNAME/SMTP_PASSWORD");
        }
        if ("chatapp".equals(normalized)) {
            boolean available = !config.value("CUST_SPACE_ID", "").isBlank()
                    && !config.value("CHATAPP_FROM", "").isBlank();
            return new ChannelCapability("chatapp", available,
                    available ? "" : "missing CUST_SPACE_ID/CHATAPP_FROM or Aliyun credentials");
        }
        if ("wecom".equals(normalized)) {
            return new ChannelCapability("wecom", false, "reserved for future WeCom API adapter");
        }
        return new ChannelCapability(normalized, false, "unknown channel");
    }

    public List<TemplateStore.TemplateRecord> templates() throws IOException {
        return templateStore.readAll();
    }

    private List<UnifiedMessage> readEmailMessages() throws IOException {
        Path file = config.emailInboxFile();
        List<UnifiedMessage> result = new ArrayList<>();
        if (!Files.exists(file)) {
            return result;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                UnifiedMessage message = new UnifiedMessage();
                message.sourceId = JsonSupport.string(object, "id");
                message.id = "email:" + ContactPointUtil.firstNonBlank(message.sourceId, Integer.toHexString(line.hashCode()));
                message.channel = "email";
                message.direction = normalizeDirection(JsonSupport.string(object, "direction"));
                message.contactPointId = ContactPointUtil.normalizePointId("email:" + ContactPointUtil.firstNonBlank(
                        JsonSupport.string(object, "contactEmail"),
                        "outbound".equals(message.direction) ? JsonSupport.string(object, "to") : JsonSupport.string(object, "from")
                ));
                message.timestamp = mailTimestamp(JsonSupport.string(object, "sentDate"), JsonSupport.string(object, "storedAt"));
                message.from = JsonSupport.string(object, "from");
                message.to = JsonSupport.string(object, "to");
                message.title = JsonSupport.string(object, "subject");
                message.summary = JsonSupport.string(object, "summary");
                message.bodyText = JsonSupport.string(object, "bodyText");
                message.text = ContactPointUtil.firstNonBlank(message.summary, message.bodyText, message.title);
                message.raw = line;
                result.add(message);
            } catch (RuntimeException ignored) {
                // Keep the projection resilient when one JSONL row is damaged.
            }
        }
        return result;
    }

    private List<UnifiedMessage> readChatAppMessages() throws IOException {
        Path file = config.chatappDataFile();
        List<UnifiedMessage> rawMessages = new ArrayList<>();
        Map<String, StatusRecord> statusBySourceId = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return rawMessages;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                String sourceId = JsonSupport.string(object, "id");
                String direction = normalizeDirection(JsonSupport.string(object, "direction"));
                if ("status".equals(direction)) {
                    statusBySourceId.put(statusTargetId(sourceId), new StatusRecord(cleanStatus(JsonSupport.string(object, "text")),
                            MessageTime.timestampString(JsonSupport.string(object, "timestamp"))));
                    continue;
                }
                UnifiedMessage message = new UnifiedMessage();
                message.sourceId = sourceId;
                message.id = "chatapp:" + ContactPointUtil.firstNonBlank(sourceId, Integer.toHexString(line.hashCode()));
                message.channel = "chatapp";
                message.direction = direction;
                message.timestamp = MessageTime.timestampString(JsonSupport.string(object, "timestamp"));
                message.raw = JsonSupport.string(object, "raw");
                message.from = ContactPointUtil.firstNonBlank(JsonSupport.string(object, "from"), firstRaw(message.raw, "From", "from", "businessNumber"));
                message.to = ContactPointUtil.firstNonBlank(JsonSupport.string(object, "to"), firstRaw(message.raw, "To", "to", "userNumber"));
                String peer = "outbound".equals(message.direction) ? message.to : message.from;
                message.contactPointId = ContactPointUtil.normalizePointId("chatapp:whatsapp:" + peer);
                message.title = chatAppTitle(object, message.raw);
                message.text = chatAppDisplayText(object, message.raw);
                message.summary = message.text;
                message.status = cleanStatus(ContactPointUtil.firstNonBlank(JsonSupport.string(object, "status"), rawStatus(message.raw)));
                message.statusTimestamp = ContactPointUtil.firstNonBlank(JsonSupport.string(object, "statusTimestamp"), message.timestamp);
                message.mediaType = ContactPointUtil.firstNonBlank(JsonSupport.string(object, "mediaType"), firstRaw(message.raw, "messageTypeName", "messageType", "Type"));
                message.mediaUrl = ContactPointUtil.firstNonBlank(JsonSupport.string(object, "mediaUrl"), firstRaw(message.raw, "link", "mediaUrl"));
                message.fileName = ContactPointUtil.firstNonBlank(JsonSupport.string(object, "fileName"), firstRaw(message.raw, "fileName"));
                rawMessages.add(message);
            } catch (RuntimeException ignored) {
                // Keep reading the remaining local history when one line is malformed.
            }
        }
        Map<String, UnifiedMessage> bySourceId = new LinkedHashMap<>();
        for (UnifiedMessage message : rawMessages) {
            UnifiedMessage existing = bySourceId.get(message.sourceId);
            if (existing == null) {
                bySourceId.put(message.sourceId, message);
            } else {
                mergeMessage(existing, message);
            }
        }
        for (Map.Entry<String, StatusRecord> entry : statusBySourceId.entrySet()) {
            UnifiedMessage target = bySourceId.get(entry.getKey());
            if (target != null) {
                target.status = entry.getValue().status;
                target.statusTimestamp = entry.getValue().timestamp;
            }
        }
        return new ArrayList<>(bySourceId.values());
    }

    private String chatAppTitle(JsonObject object, String raw) {
        String mediaType = ContactPointUtil.firstNonBlank(JsonSupport.string(object, "mediaType"), firstRaw(raw, "messageTypeName", "messageType", "Type"));
        if (!mediaType.isBlank()
                && !"TEXT".equalsIgnoreCase(mediaType)
                && !"template".equalsIgnoreCase(mediaType)
                && !"TEMPLATE".equalsIgnoreCase(mediaType)) {
            return mediaType.toLowerCase(Locale.ROOT);
        }
        String templateName = ContactPointUtil.firstNonBlank(firstRaw(raw, "templateName", "TemplateName"),
                firstRaw(raw, "templateCode", "TemplateCode"));
        return templateName.isBlank() ? "" : "Template: " + templateName;
    }

    private String chatAppDisplayText(JsonObject object, String raw) {
        String templateText = templateText(raw);
        if (!templateText.isBlank()) {
            return templateText;
        }
        String stored = JsonSupport.string(object, "text");
        String textFromJson = JsonSupport.textField(stored);
        if (!textFromJson.isBlank()) {
            return textFromJson;
        }
        String rawMessage = firstRaw(raw, "message", "Message", "text", "body", "content");
        String rawText = JsonSupport.textField(rawMessage);
        return ContactPointUtil.firstNonBlank(rawText, rawMessage, stored);
    }

    private String templateText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String messageType = firstRaw(raw, "messageType", "messageTypeName", "Type", "type");
        String templateCode = firstRaw(raw, "templateCode", "TemplateCode");
        if (templateCode.isBlank() && !"template".equalsIgnoreCase(messageType)) {
            return "";
        }
        String params = firstRaw(raw, "message", "Message", "templateParams", "TemplateParams");
        return templateStore.render(templateCode, firstRaw(raw, "languageCode", "Language", "language"),
                params, firstRaw(raw, "templateName", "TemplateName"));
    }

    private static void mergeMessage(UnifiedMessage target, UnifiedMessage source) {
        target.text = ContactPointUtil.firstNonBlank(target.text, source.text);
        target.raw = ContactPointUtil.firstNonBlank(target.raw, source.raw);
        target.status = ContactPointUtil.firstNonBlank(target.status, source.status);
        target.statusTimestamp = ContactPointUtil.firstNonBlank(target.statusTimestamp, source.statusTimestamp);
        target.mediaType = ContactPointUtil.firstNonBlank(target.mediaType, source.mediaType);
        target.mediaUrl = ContactPointUtil.firstNonBlank(target.mediaUrl, source.mediaUrl);
        target.fileName = ContactPointUtil.firstNonBlank(target.fileName, source.fileName);
    }

    private Map<String, List<String>> contactGroups() throws IOException {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        readUnifiedContactGroups(config.contactGroupFile(), groups);
        readLegacyEmailContactGroups(config.emailContactGroupFile(), groups);
        return groups;
    }

    private void readUnifiedContactGroups(Path file, Map<String, List<String>> groups) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                String primary = ContactPointUtil.normalizePointId(JsonSupport.string(object, "primaryPointId"));
                LinkedHashSet<String> points = new LinkedHashSet<>();
                if (!primary.isBlank()) {
                    points.add(primary);
                }
                JsonElement array = object.get("points");
                if (array != null && array.isJsonArray()) {
                    for (JsonElement item : array.getAsJsonArray()) {
                        String point = ContactPointUtil.normalizePointId(item.getAsString());
                        if (!point.isBlank()) {
                            points.add(point);
                        }
                    }
                }
                if (points.size() > 1) {
                    groups.put(primary, new ArrayList<>(points));
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void readLegacyEmailContactGroups(Path file, Map<String, List<String>> groups) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                String primary = ContactPointUtil.normalizePointId("email:" + JsonSupport.string(object, "primaryEmail"));
                LinkedHashSet<String> points = new LinkedHashSet<>();
                if (!primary.isBlank()) {
                    points.add(primary);
                }
                JsonElement emails = object.get("emails");
                if (emails != null && emails.isJsonArray()) {
                    for (JsonElement item : emails.getAsJsonArray()) {
                        String point = ContactPointUtil.normalizePointId("email:" + item.getAsString());
                        if (!point.isBlank()) {
                            points.add(point);
                        }
                    }
                }
                if (points.size() > 1 && !groups.containsKey(primary)) {
                    groups.put(primary, new ArrayList<>(points));
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void writeContactGroups(Map<String, List<String>> groups) throws IOException {
        Path file = config.contactGroupFile();
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        StringBuilder output = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            LinkedHashSet<String> points = new LinkedHashSet<>();
            for (String point : entry.getValue()) {
                String normalized = ContactPointUtil.normalizePointId(point);
                if (!normalized.isBlank()) {
                    points.add(normalized);
                }
            }
            if (points.size() <= 1) {
                continue;
            }
            String primary = ContactPointUtil.normalizePointId(entry.getKey());
            if (primary.isBlank() || !points.contains(primary)) {
                primary = points.iterator().next();
            }
            GroupRecord record = new GroupRecord();
            record.primaryPointId = primary;
            record.points = new ArrayList<>(points);
            output.append(GSON.toJson(record)).append(System.lineSeparator());
        }
        Files.writeString(file, output.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String primaryFor(String contactPointId, Map<String, List<String>> groups) {
        String point = ContactPointUtil.normalizePointId(contactPointId);
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            for (String item : entry.getValue()) {
                if (Objects.equals(ContactPointUtil.normalizePointId(item), point)) {
                    return ContactPointUtil.normalizePointId(entry.getKey());
                }
            }
        }
        return point;
    }

    private static String mailTimestamp(String sentDate, String storedAt) {
        Instant sent = MessageTime.parseInstant(sentDate);
        if (!sent.equals(Instant.EPOCH)) {
            return sent.toString();
        }
        return MessageTime.timestampString(storedAt);
    }

    private static String normalizeDirection(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("in".equals(normalized) || "inbound".equals(normalized)) {
            return "inbound";
        }
        if ("out".equals(normalized) || "outbound".equals(normalized)) {
            return "outbound";
        }
        if ("status".equals(normalized)) {
            return "status";
        }
        return normalized.isBlank() ? "inbound" : normalized;
    }

    private static String statusTargetId(String id) {
        if (id == null) {
            return "";
        }
        return id.endsWith("-status") ? id.substring(0, id.length() - "-status".length()) : id;
    }

    private static String rawStatus(String raw) {
        String status = ContactPointUtil.firstNonBlank(firstRaw(raw, "clientReadStatusName", "ClientReadStatusName"),
                firstRaw(raw, "clientAcceptStatusName", "ClientAcceptStatusName"),
                firstRaw(raw, "messageStatusName", "MessageStatusName"),
                firstRaw(raw, "Status", "status"));
        String failReason = firstRaw(raw, "failReason", "FailReason", "ErrorDescription");
        return failReason.isBlank() ? status : ContactPointUtil.firstNonBlank(status, "Failed") + " - " + failReason;
    }

    private static String cleanStatus(String value) {
        String status = value == null ? "" : value.trim();
        if (status.regionMatches(true, 0, "Status:", 0, "Status:".length())) {
            status = status.substring("Status:".length()).trim();
        }
        if (status.startsWith("状态:")) {
            status = status.substring("状态:".length()).trim();
        }
        return status;
    }

    private static String firstRaw(String raw, String... keys) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            JsonElement root = JsonParser.parseString(raw);
            for (String key : keys) {
                String value = findRecursive(root, key);
                if (!value.isBlank()) {
                    return value;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    private static String findRecursive(JsonElement element, String key) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    return scalar(entry.getValue());
                }
                String child = findRecursive(entry.getValue(), key);
                if (!child.isBlank()) {
                    return child;
                }
            }
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                String value = findRecursive(child, key);
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private static String scalar(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        return element.toString();
    }

    private static int channelOrder(String channel) {
        if ("email".equals(channel)) return 0;
        if ("chatapp".equals(channel)) return 1;
        if ("wecom".equals(channel)) return 2;
        return 9;
    }

    private static class StatusRecord {
        final String status;
        final String timestamp;

        StatusRecord(String status, String timestamp) {
            this.status = status;
            this.timestamp = timestamp;
        }
    }

    private static class GroupRecord {
        String primaryPointId;
        List<String> points = new ArrayList<>();
    }

    private static class ContactAccumulator {
        final String primaryPointId;
        final Map<String, ContactPoint> points = new LinkedHashMap<>();
        final Set<String> channels = new LinkedHashSet<>();
        String displayName = "";
        String lastText = "";
        String lastTime = "";
        int messageCount;

        ContactAccumulator(String primaryPointId) {
            this.primaryPointId = primaryPointId;
        }

        void add(UnifiedMessage message) {
            ContactPoint point = ContactPointUtil.fromId(message.contactPointId, message);
            points.putIfAbsent(point.id, point);
            channels.add(point.channel);
            if (displayName.isBlank() || Objects.equals(point.id, primaryPointId)) {
                displayName = ContactPointUtil.firstNonBlank(point.label, point.value, displayName);
            }
            lastText = ContactPointUtil.firstNonBlank(message.title, message.text, message.summary, lastText);
            lastTime = ContactPointUtil.firstNonBlank(message.timestamp, lastTime);
            messageCount++;
        }

        UnifiedContact toContact(List<String> groupPoints) {
            for (String groupPoint : groupPoints) {
                points.putIfAbsent(groupPoint, ContactPointUtil.fromId(groupPoint, null));
            }
            UnifiedContact contact = new UnifiedContact();
            contact.id = primaryPointId;
            contact.displayName = ContactPointUtil.firstNonBlank(displayName, primaryPointId);
            contact.lastText = lastText;
            contact.lastTime = lastTime;
            contact.messageCount = messageCount;
            List<ContactPoint> orderedPoints = new ArrayList<>(points.values());
            orderedPoints.sort(Comparator
                    .comparingInt((ContactPoint point) -> channelOrder(point.channel))
                    .thenComparing(point -> point.id));
            contact.points.addAll(orderedPoints);
            List<String> orderedChannels = new ArrayList<>(channels);
            orderedChannels.sort(Comparator.comparingInt(UnifiedMessageStore::channelOrder));
            contact.channels.addAll(orderedChannels);
            return contact;
        }
    }
}
