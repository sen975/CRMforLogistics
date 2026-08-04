package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class UnifiedMessageStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int DEFAULT_THREAD_LIMIT = 10;
    private static final int MAX_THREAD_LIMIT = 50;

    private final Config config;
    private final TemplateStore templateStore;
    private final ContactRepository contactRepository;
    private final MessageRepository messageRepository;
    private final UUID userId;

    public static class ThreadPage {
        public final List<UnifiedMessage> items;
        public final String nextCursor;
        public final int messageCount;
        public final String threadRevision;

        ThreadPage(List<UnifiedMessage> items, String nextCursor) {
            this(items, nextCursor, items == null ? 0 : items.size());
        }

        ThreadPage(List<UnifiedMessage> items, String nextCursor, int messageCount) {
            this(items, nextCursor, messageCount, threadRevision(items));
        }

        ThreadPage(List<UnifiedMessage> items, String nextCursor, int messageCount, String threadRevision) {
            this.items = items;
            this.nextCursor = nextCursor;
            this.messageCount = messageCount;
            this.threadRevision = threadRevision == null ? "" : threadRevision;
        }
    }

    private record ThreadCursor(Instant timestamp, String messageId) {}

    public UnifiedMessageStore(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.templateStore = new TemplateStore(config.chatappTemplateFile());
        this.contactRepository = null;
        this.messageRepository = null;
        this.userId = null;
    }

    public UnifiedMessageStore(ContactRepository contactRepository,
                               MessageRepository messageRepository,
                               UUID userId) {
        this.config = null;
        this.templateStore = null;
        this.contactRepository = Objects.requireNonNull(contactRepository, "contactRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.userId = Objects.requireNonNull(userId, "userId");
    }

    public List<UnifiedContact> contacts() throws IOException {
        if (databaseBacked()) {
            try {
                return contactRepository.listForUser(userId, new ContactQuery("", null, null, 100));
            } catch (Exception exception) {
                throw databaseFailure("Unable to query contacts", exception);
            }
        }
        Map<String, List<String>> groups = contactGroups();
        Map<String, String> remarks = contactRemarks();
        Map<String, List<String>> tags = contactTags();
        Map<String, ContactAccumulator> byPrimary = new LinkedHashMap<>();
        for (UnifiedMessage message : messages()) {
            if (message.contactPointId == null || message.contactPointId.isBlank()) {
                continue;
            }
            String primary = primaryFor(message.contactPointId, groups);
            ContactAccumulator acc = byPrimary.computeIfAbsent(primary, ContactAccumulator::new);
            acc.add(message);
        }
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            List<String> points = entry.getValue();
            if (!points.isEmpty() && points.stream().allMatch(UnifiedMessageStore::isPhonePoint)) {
                String primary = ContactPointUtil.normalizePointId(entry.getKey());
                byPrimary.computeIfAbsent(primary, ContactAccumulator::new);
            }
        }
        List<UnifiedContact> result = new ArrayList<>();
        for (ContactAccumulator acc : byPrimary.values()) {
            result.add(acc.toContact(groups.getOrDefault(acc.primaryPointId, List.of(acc.primaryPointId)),
                    remarks.getOrDefault(acc.primaryPointId, ""),
                    tags.getOrDefault(acc.primaryPointId, List.of())));
        }
        result.sort(Comparator
                .comparing((UnifiedContact contact) -> MessageTime.parseInstant(contact.lastTime))
                .reversed()
                .thenComparing(contact -> ContactPointUtil.firstNonBlank(contact.displayName, contact.id)));
        return result;
    }

    public List<UnifiedMessage> messages() throws IOException {
        if (databaseBacked()) {
            throw new IOException("Database-backed message queries require a contact or conversation scope");
        }
        List<UnifiedMessage> result = new ArrayList<>();
        result.addAll(readEmailMessages());
        result.addAll(readChatAppMessages());
        result.addAll(readWeComMessages());
        result.sort(Comparator
                .comparing((UnifiedMessage message) -> MessageTime.parseInstant(message.timestamp))
                .thenComparing(message -> ContactPointUtil.firstNonBlank(message.id, message.sourceId)));
        return result;
    }

    public List<UnifiedMessage> thread(String contactPointId) throws IOException {
        if (databaseBacked()) {
            try {
                return messageRepository.unifiedTimeline(userId, UUID.fromString(contactPointId), null, 100);
            } catch (Exception exception) {
                throw databaseFailure("Unable to query contact timeline", exception);
            }
        }
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

    /** Read-only snapshot used by projections that combine messages with other item types. */
    public List<UnifiedMessage> timelineMessages(String contactPointId) throws IOException {
        return List.copyOf(thread(contactPointId));
    }

    public ThreadPage threadPage(String contactPointId, String cursor, int limit) throws IOException {
        int safeLimit = threadLimit(limit);
        if (databaseBacked()) {
            try {
                ThreadCursor decoded = decodeThreadCursor(cursor);
                UUID contactId = UUID.fromString(contactPointId);
                MessageCursor messageCursor = decoded == null ? null
                        : new MessageCursor(decoded.timestamp(), UUID.fromString(decoded.messageId()));
                UnifiedTimelineSnapshot snapshot = messageRepository.unifiedTimelinePage(
                        userId, contactId, messageCursor, safeLimit + 1);
                return toThreadPage(snapshot.fetched(), safeLimit, snapshot.messageCount(), snapshot.threadRevision());
            } catch (Exception exception) {
                throw databaseFailure("Unable to query contact timeline page", exception);
            }
        }

        List<UnifiedMessage> all = thread(contactPointId);
        ThreadCursor decoded = decodeThreadCursor(cursor);
        int endExclusive = all.size();
        if (decoded != null) {
            for (int i = 0; i < all.size(); i++) {
                if (compareMessageToCursor(all.get(i), decoded) >= 0) {
                    endExclusive = i;
                    break;
                }
            }
        }
        int startInclusive = Math.max(0, endExclusive - safeLimit);
        List<UnifiedMessage> items = new ArrayList<>(all.subList(startInclusive, endExclusive));
        String nextCursor = startInclusive > 0 && !items.isEmpty() ? encodeThreadCursor(items.get(0)) : null;
        return new ThreadPage(items, nextCursor, all.size(), threadRevision(all));
    }

    public UnifiedMessage findMessage(String id) throws IOException {
        if (id == null || id.isBlank()) {
            return null;
        }
        if (databaseBacked()) {
            try {
                return messageRepository.findAuthorized(userId, UUID.fromString(id)).orElse(null);
            } catch (Exception exception) {
                throw databaseFailure("Unable to query message", exception);
            }
        }
        for (UnifiedMessage message : messages()) {
            if (id.equals(message.id) || id.equals(message.sourceId)) {
                return message;
            }
        }
        return null;
    }

    public List<String> contactGroup(String contactPointId) throws IOException {
        if (databaseBacked()) {
            UnifiedContact contact = databaseContact(contactPointId);
            return contact.points.stream().map(point -> point.id).toList();
        }
        String point = ContactPointUtil.normalizePointId(contactPointId);
        Map<String, List<String>> groups = contactGroups();
        String primary = primaryFor(point, groups);
        return new ArrayList<>(groups.getOrDefault(primary, List.of(primary)));
    }

    public UnifiedContact ensurePhoneContact(String contactPointId, String phoneNumber, String displayName)
            throws IOException {
        requireFileContactStore();
        String phonePointId = normalizedPhonePoint(phoneNumber);
        String contactPoint = ContactPointUtil.normalizePointId(contactPointId);
        if (contactPoint.isBlank()) {
            String name = displayName == null ? "" : displayName.trim();
            if (name.isBlank()) {
                throw new IllegalArgumentException("PHONE_CONTACT_REQUIRED");
            }
            Map<String, List<String>> groups = contactGroups();
            String existingPrimary = primaryFor(phonePointId, groups);
            if (!existingPrimary.equals(phonePointId) || groups.containsKey(phonePointId)) {
                return contactProjection(existingPrimary);
            }
            Map<String, String> remarks = contactRemarks();
            Map<String, List<String>> tags = contactTags();
            groups.put(phonePointId, List.of(phonePointId));
            remarks.put(phonePointId, name);
            writeContactGroups(groups, remarks, tags);
            return contactProjection(phonePointId);
        }

        bindPhonePoint(contactPoint, phoneNumber);
        return contactProjection(contactPoint);
    }

    public String bindPhonePoint(String contactPointId, String phoneNumber) throws IOException {
        requireFileContactStore();
        String contactPoint = ContactPointUtil.normalizePointId(contactPointId);
        if (contactPoint.isBlank()) {
            throw new IllegalArgumentException("CONTACT_NOT_FOUND");
        }
        String phonePointId = normalizedPhonePoint(phoneNumber);
        Map<String, List<String>> groups = contactGroups();
        String primary = primaryFor(contactPoint, groups);
        if (!contactExists(contactPoint, groups)) {
            throw new IllegalArgumentException("CONTACT_NOT_FOUND");
        }
        String existingPrimary = primaryFor(phonePointId, groups);
        if (!existingPrimary.equals(phonePointId) || groups.containsKey(phonePointId)) {
            if (!existingPrimary.equals(primary)) {
                throw new IllegalStateException("PHONE_POINT_CONFLICT");
            }
            return phonePointId;
        }

        Map<String, String> remarks = contactRemarks();
        Map<String, List<String>> tags = contactTags();
        LinkedHashSet<String> points = new LinkedHashSet<>(groups.getOrDefault(primary, List.of(primary)));
        points.add(phonePointId);
        groups.put(primary, new ArrayList<>(points));
        writeContactGroups(groups, remarks, tags);
        return phonePointId;
    }

    public void mergeContacts(String primaryPointId, String mergedPointId) throws IOException {
        if (databaseBacked()) {
            try {
                contactRepository.merge(UUID.fromString(primaryPointId), UUID.fromString(mergedPointId), userId);
                return;
            } catch (Exception exception) {
                throw databaseFailure("Unable to merge contacts", exception);
            }
        }
        String primary = ContactPointUtil.normalizePointId(primaryPointId);
        String merged = ContactPointUtil.normalizePointId(mergedPointId);
        if (primary.isBlank() || merged.isBlank() || primary.equals(merged)) {
            return;
        }
        Map<String, List<String>> groups = contactGroups();
        Map<String, String> remarks = contactRemarks();
        Map<String, List<String>> tags = contactTags();
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
        String remark = ContactPointUtil.firstNonBlank(remarks.remove(primaryRoot), remarks.remove(mergedRoot));
        if (!remark.isBlank()) {
            remarks.put(primary, remark);
        }
        LinkedHashSet<String> mergedTags = new LinkedHashSet<>();
        List<String> primaryTags = tags.remove(primaryRoot);
        List<String> mergedRootTags = tags.remove(mergedRoot);
        mergedTags.addAll(primaryTags == null ? List.of() : primaryTags);
        mergedTags.addAll(mergedRootTags == null ? List.of() : mergedRootTags);
        if (!mergedTags.isEmpty()) {
            tags.put(primary, new ArrayList<>(mergedTags));
        }
        writeContactGroups(groups, remarks, tags);
    }

    public void splitContact(String primaryPointId, String pointToSplit) throws IOException {
        if (databaseBacked()) {
            try {
                ContactIdentity identity = contactRepository.findIdentity(UUID.fromString(pointToSplit));
                String displayName = ContactPointUtil.firstNonBlank(identity.displayName(), identity.identityValue(), "Split contact");
                contactRepository.splitIdentity(identity.id(), displayName, userId);
                return;
            } catch (Exception exception) {
                throw databaseFailure("Unable to split contact identity", exception);
            }
        }
        String primary = ContactPointUtil.normalizePointId(primaryPointId);
        String split = ContactPointUtil.normalizePointId(pointToSplit);
        if (primary.isBlank() || split.isBlank()) {
            return;
        }
        Map<String, List<String>> groups = contactGroups();
        Map<String, String> remarks = contactRemarks();
        Map<String, List<String>> tags = contactTags();
        String root = primaryFor(primary, groups);
        String remark = ContactPointUtil.firstNonBlank(remarks.remove(root), "");
        List<String> retainedTags = tags.remove(root);
        List<String> points = new ArrayList<>(groups.getOrDefault(root, List.of(root)));
        points.removeIf(point -> Objects.equals(ContactPointUtil.normalizePointId(point), split));
        groups.remove(root);
        if (!points.isEmpty()) {
            String newRoot = points.contains(root) ? root : points.get(0);
            groups.put(newRoot, points);
            if (!remark.isBlank()) {
                remarks.put(newRoot, remark);
            }
            if (retainedTags != null && !retainedTags.isEmpty()) {
                tags.put(newRoot, retainedTags);
            }
            if (!Objects.equals(newRoot, root)) {
                groups.put(root, List.of(root));
            }
        }
        writeContactGroups(groups, remarks, tags);
    }

    public void updateContactRemark(String contactPointId, String remark) throws IOException {
        if (databaseBacked()) {
            UnifiedContact contact = databaseContact(contactPointId);
            updateDatabaseProfile(contact, contact.displayName, remark, contact.tags);
            return;
        }
        String point = ContactPointUtil.normalizePointId(contactPointId);
        if (point.isBlank()) {
            return;
        }
        Map<String, List<String>> groups = contactGroups();
        String primary = primaryFor(point, groups);
        updateContactProfile(contactPointId, remark, contactTags().getOrDefault(primary, List.of()));
    }

    public void updateContactProfile(String contactPointId, String nickname, List<String> newTags) throws IOException {
        if (databaseBacked()) {
            UnifiedContact contact = databaseContact(contactPointId);
            String displayName = nickname == null || nickname.isBlank() ? contact.displayName : nickname.trim();
            updateDatabaseProfile(contact, displayName, contact.remark, cleanTags(newTags));
            return;
        }
        String point = ContactPointUtil.normalizePointId(contactPointId);
        if (point.isBlank()) {
            return;
        }
        Map<String, List<String>> groups = contactGroups();
        Map<String, String> remarks = contactRemarks();
        Map<String, List<String>> tags = contactTags();
        String primary = primaryFor(point, groups);
        groups.putIfAbsent(primary, List.of(primary));
        String cleaned = nickname == null ? "" : nickname.trim();
        if (cleaned.isBlank()) {
            remarks.remove(primary);
        } else {
            remarks.put(primary, cleaned);
        }
        List<String> cleanedTags = cleanTags(newTags);
        if (cleanedTags.isEmpty()) {
            tags.remove(primary);
        } else {
            tags.put(primary, cleanedTags);
        }
        writeContactGroups(groups, remarks, tags);
    }

    public ChannelCapability channelCapability(String channel) {
        if (databaseBacked()) {
            return new ChannelCapability(channel == null ? "" : channel, false,
                    "Channel capabilities are provided by channel account services");
        }
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
            return new ChannelCapability("wecom", true, "");
        }
        return new ChannelCapability(normalized, false, "unknown channel");
    }

    public List<TemplateStore.TemplateRecord> templates() throws IOException {
        if (databaseBacked()) {
            return List.of();
        }
        return templateStore.readAll();
    }

    private boolean databaseBacked() {
        return contactRepository != null;
    }

    private UnifiedContact databaseContact(String contactId) throws IOException {
        try {
            UnifiedContact contact = contactRepository.findForUser(userId, UUID.fromString(contactId));
            if (contact == null) throw new IllegalArgumentException("Contact not found or unauthorized");
            return contact;
        } catch (Exception exception) {
            throw databaseFailure("Unable to query contact profile", exception);
        }
    }

    private void updateDatabaseProfile(UnifiedContact contact, String displayName,
                                       String remark, List<String> tags) throws IOException {
        try {
            contactRepository.updateProfile(UUID.fromString(contact.id), displayName, remark, tags, userId);
        } catch (Exception exception) {
            throw databaseFailure("Unable to update contact profile", exception);
        }
    }

    private static IOException databaseFailure(String message, Exception cause) {
        return new IOException(message, cause);
    }

    private static int threadLimit(int limit) {
        if (limit <= 0) return DEFAULT_THREAD_LIMIT;
        return Math.max(1, Math.min(MAX_THREAD_LIMIT, limit));
    }

    private static ThreadPage toThreadPage(List<UnifiedMessage> fetched, int safeLimit, int messageCount,
                                           String threadRevision) {
        boolean hasMore = fetched.size() > safeLimit;
        List<UnifiedMessage> items = hasMore
                ? new ArrayList<>(fetched.subList(1, fetched.size()))
                : new ArrayList<>(fetched);
        String nextCursor = hasMore && !items.isEmpty() ? encodeThreadCursor(items.get(0)) : null;
        return new ThreadPage(items, nextCursor, messageCount, threadRevision);
    }

    private static String encodeThreadCursor(UnifiedMessage message) {
        if (message == null || message.timestamp == null || message.timestamp.isBlank()) return null;
        String id = stableMessageId(message);
        if (id.isBlank()) return null;
        JsonObject object = new JsonObject();
        object.addProperty("timestamp", MessageTime.parseInstant(message.timestamp).toString());
        object.addProperty("id", id);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(object.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static ThreadCursor decodeThreadCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            JsonObject object = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject();
            String timestamp = JsonSupport.string(object, "timestamp");
            String id = JsonSupport.string(object, "id");
            if (timestamp.isBlank() || id.isBlank()) throw new IllegalArgumentException("invalid thread cursor");
            return new ThreadCursor(MessageTime.parseInstant(timestamp), id);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid thread cursor");
        }
    }

    private static int compareMessageToCursor(UnifiedMessage message, ThreadCursor cursor) {
        int time = MessageTime.parseInstant(message.timestamp).compareTo(cursor.timestamp());
        if (time != 0) return time;
        return stableMessageId(message).compareTo(cursor.messageId());
    }

    private static String stableMessageId(UnifiedMessage message) {
        return ContactPointUtil.firstNonBlank(message.id, message.sourceId);
    }

    private static String threadRevision(List<UnifiedMessage> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (messages != null) {
                for (UnifiedMessage message : messages) {
                    digestPart(digest, stableMessageId(message));
                    digestPart(digest, message.channel);
                    digestPart(digest, message.direction);
                    digestPart(digest, message.timestamp);
                    digestPart(digest, message.status);
                    digestPart(digest, message.statusTimestamp);
                    digestPart(digest, message.title);
                    digestPart(digest, message.text);
                    digestPart(digest, message.summary);
                    digestPart(digest, message.bodyText);
                    digestPart(digest, message.mediaType);
                    digestPart(digest, message.mediaUrl);
                    digestPart(digest, message.objectKey);
                    digestPart(digest, message.fileName);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void digestPart(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
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
                String storedText = JsonSupport.string(object, "text");
                message.mediaType = cleanMediaType(ContactPointUtil.firstNonBlank(JsonSupport.string(object, "mediaType"),
                        firstJsonValue(storedText, "mediaType", "messageType", "type"),
                        firstRaw(message.raw, "messageTypeName", "messageType", "Type")));
                message.mediaUrl = ContactPointUtil.firstNonBlank(JsonSupport.string(object, "mediaUrl"),
                        firstJsonValue(storedText, "url", "link", "mediaUrl"),
                        firstRaw(message.raw, "link", "mediaUrl", "url"));
                message.objectKey = ContactPointUtil.firstNonBlank(JsonSupport.string(object, "objectKey"),
                        firstJsonValue(storedText, "objectKey", "ossObjectKey"),
                        firstRaw(message.raw, "objectKey", "ossObjectKey"));
                message.mimeType = ContactPointUtil.firstNonBlank(JsonSupport.string(object, "mimeType"),
                        firstJsonValue(storedText, "mimeType", "contentType"),
                        firstRaw(message.raw, "mimeType", "contentType"));
                message.fileName = ContactPointUtil.firstNonBlank(JsonSupport.string(object, "fileName"),
                        firstJsonValue(storedText, "fileName", "name"),
                        firstRaw(message.raw, "fileName"));
                message.title = chatAppTitle(object, message.raw, message.mediaType);
                message.text = chatAppDisplayText(object, message.raw, message.mediaType);
                message.summary = message.text;
                message.status = cleanStatus(ContactPointUtil.firstNonBlank(JsonSupport.string(object, "status"), rawStatus(message.raw)));
                message.statusTimestamp = ContactPointUtil.firstNonBlank(JsonSupport.string(object, "statusTimestamp"), message.timestamp);
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

    private List<UnifiedMessage> readWeComMessages() throws IOException {
        Path file = config.wecomDataFile();
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
                String msgid = JsonSupport.string(object, "msgid");
                String msgtype = JsonSupport.string(object, "msgtype");
                long sendTime = object.has("send_time") ? object.get("send_time").getAsLong() : 0;
                String externalUserid = JsonSupport.string(object, "external_userid");
                String openKfid = JsonSupport.string(object, "open_kfid");
                int origin = object.has("origin") ? object.get("origin").getAsInt() : 3;

                UnifiedMessage message = new UnifiedMessage();
                message.sourceId = msgid;
                message.id = "wecom:" + ContactPointUtil.firstNonBlank(msgid, Integer.toHexString(line.hashCode()));
                message.channel = "wecom";
                message.direction = origin == 3 ? "inbound" : "outbound";
                message.timestamp = sendTime > 0 ? Instant.ofEpochSecond(sendTime).toString() : "";
                message.raw = JsonSupport.string(object, "_raw");
                message.from = externalUserid;
                message.to = openKfid;
                String peer = "outbound".equals(message.direction) ? message.to : message.from;
                message.contactPointId = ContactPointUtil.normalizePointId("wecom:" + peer);
                message.text = wecomDisplayText(object, msgtype);
                message.summary = message.text;
                result.add(message);
            } catch (RuntimeException ignored) {
                // Skip malformed lines.
            }
        }
        return result;
    }

    private static String wecomDisplayText(JsonObject object, String msgtype) {
        if (msgtype == null || msgtype.isBlank()) return "";
        return switch (msgtype) {
            case "text" -> {
                JsonObject text = object.getAsJsonObject("text");
                yield text != null ? JsonSupport.string(text, "content") : "";
            }
            case "image" -> "[图片]";
            case "voice" -> "[语音]";
            case "video" -> "[视频]";
            case "file" -> "[文件]";
            case "location" -> {
                JsonObject loc = object.getAsJsonObject("location");
                yield loc != null ? "[位置] " + JsonSupport.string(loc, "name") : "[位置]";
            }
            case "link" -> {
                JsonObject link = object.getAsJsonObject("link");
                yield link != null ? JsonSupport.string(link, "title") : "[链接]";
            }
            case "event" -> "[事件]";
            default -> "";
        };
    }

    private String chatAppTitle(JsonObject object, String raw, String projectedMediaType) {
        return "";
    }

    private String chatAppDisplayText(JsonObject object, String raw, String projectedMediaType) {
        String templateText = templateText(raw);
        if (!templateText.isBlank()) {
            return templateText;
        }
        String stored = JsonSupport.string(object, "text");
        String textFromJson = JsonSupport.textField(stored);
        if (!textFromJson.isBlank()) {
            return textFromJson;
        }
        String storedCaptionText = stripMediaPlaceholderPrefix(stored);
        if (!storedCaptionText.isBlank()) {
            return storedCaptionText;
        }
        String storedMediaText = mediaTextFromJson(projectedMediaType, stored);
        String rawMessage = firstRaw(raw, "message", "Message", "text", "body", "content");
        String rawText = JsonSupport.textField(rawMessage);
        String rawMediaText = mediaTextFromJson(projectedMediaType, rawMessage);
        if (!storedMediaText.isBlank() && !isGenericMediaPlaceholder(storedMediaText)) {
            return storedMediaText;
        }
        return ContactPointUtil.firstNonBlank(rawText, rawMediaText, storedMediaText, rawMessage, stored);
    }

    private static String mediaTextFromJson(String mediaType, String raw) {
        String normalized = cleanMediaType(mediaType);
        boolean attachmentType = "image".equals(normalized) || "video".equals(normalized) || "document".equals(normalized);
        boolean hasAttachmentPayload = !firstJsonValue(raw, "caption", "url", "link", "mediaUrl", "fileName").isBlank();
        if (!attachmentType && !hasAttachmentPayload) {
            return "";
        }
        String caption = firstJsonValue(raw, "caption");
        String fileName = firstJsonValue(raw, "fileName", "name");
        String type = ContactPointUtil.firstNonBlank(attachmentType ? normalized : "",
                cleanMediaType(firstJsonValue(raw, "mediaType", "messageType", "type")));
        String label = type.isBlank() ? "附件" : type;
        return ContactPointUtil.firstNonBlank(caption, fileName, "[" + label + "]");
    }

    private static boolean isGenericMediaPlaceholder(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return "[image]".equals(text) || "[video]".equals(text) || "[document]".equals(text) || "[file]".equals(text)
                || "[附件]".equals(text);
    }

    private static String stripMediaPlaceholderPrefix(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)^\\s*\\[(image|video|document|file|附件)\\]\\s+(.+)$")
                .matcher(text);
        return matcher.matches() ? matcher.group(2).trim() : "";
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
        target.objectKey = ContactPointUtil.firstNonBlank(target.objectKey, source.objectKey);
        target.mimeType = ContactPointUtil.firstNonBlank(target.mimeType, source.mimeType);
        target.fileName = ContactPointUtil.firstNonBlank(target.fileName, source.fileName);
    }

    private Map<String, List<String>> contactGroups() throws IOException {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        readUnifiedContactGroups(config.contactGroupFile(), groups);
        readLegacyEmailContactGroups(config.emailContactGroupFile(), groups);
        return groups;
    }

    private Map<String, String> contactRemarks() throws IOException {
        Map<String, String> remarks = new LinkedHashMap<>();
        Path file = config.contactGroupFile();
        if (!Files.exists(file)) {
            return remarks;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                String primary = ContactPointUtil.normalizePointId(JsonSupport.string(object, "primaryPointId"));
                String remark = JsonSupport.string(object, "remark").trim();
                if (!primary.isBlank() && !remark.isBlank()) {
                    remarks.put(primary, remark);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return remarks;
    }

    private Map<String, List<String>> contactTags() throws IOException {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        Path file = config.contactGroupFile();
        if (!Files.exists(file)) {
            return tags;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                String primary = ContactPointUtil.normalizePointId(JsonSupport.string(object, "primaryPointId"));
                JsonElement array = object.get("tags");
                List<String> values = new ArrayList<>();
                if (array != null && array.isJsonArray()) {
                    for (JsonElement item : array.getAsJsonArray()) {
                        String tag = item.getAsString().trim();
                        if (!tag.isBlank()) {
                            values.add(tag);
                        }
                    }
                }
                values = cleanTags(values);
                if (!primary.isBlank() && !values.isEmpty()) {
                    tags.put(primary, values);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return tags;
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
                if (!points.isEmpty()) {
                    if (primary.isBlank()) {
                        primary = points.iterator().next();
                    }
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
        writeContactGroups(groups, Map.of());
    }

    private void writeContactGroups(Map<String, List<String>> groups, Map<String, String> remarks) throws IOException {
        writeContactGroups(groups, remarks, Map.of());
    }

    private void writeContactGroups(Map<String, List<String>> groups, Map<String, String> remarks,
                                    Map<String, List<String>> tags) throws IOException {
        Path file = config.contactGroupFile();
        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        StringBuilder output = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            LinkedHashSet<String> points = new LinkedHashSet<>();
            for (String point : entry.getValue()) {
                String normalized = ContactPointUtil.normalizePointId(point);
                if (!normalized.isBlank()) {
                    points.add(normalized);
                }
            }
            if (points.isEmpty()) {
                continue;
            }
            String primary = ContactPointUtil.normalizePointId(entry.getKey());
            if (primary.isBlank() || !points.contains(primary)) {
                primary = points.iterator().next();
            }
            GroupRecord record = new GroupRecord();
            record.primaryPointId = primary;
            record.points = new ArrayList<>(points);
            record.remark = ContactPointUtil.firstNonBlank(remarks.get(primary), "");
            record.tags = cleanTags(tags.getOrDefault(primary, List.of()));
            output.append(GSON.toJson(record)).append(System.lineSeparator());
        }
        Path temporary = Files.createTempFile(directory, ".contact-groups-", ".tmp");
        try {
            Files.writeString(temporary, output.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void requireFileContactStore() throws IOException {
        if (databaseBacked()) {
            throw new IOException("Phone contact binding is unavailable for database-backed contacts");
        }
    }

    private String normalizedPhonePoint(String phoneNumber) {
        String point = ContactPointUtil.normalizePointId("phone:" + (phoneNumber == null ? "" : phoneNumber));
        if (!isPhonePoint(point)) {
            throw new IllegalArgumentException("PHONE_NUMBER_INVALID");
        }
        return point;
    }

    private boolean contactExists(String contactPointId, Map<String, List<String>> groups) throws IOException {
        if (groups.containsKey(contactPointId)) {
            return true;
        }
        for (List<String> points : groups.values()) {
            if (points.stream().anyMatch(point -> contactPointId.equals(ContactPointUtil.normalizePointId(point)))) {
                return true;
            }
        }
        return messages().stream().anyMatch(message -> contactPointId.equals(
                ContactPointUtil.normalizePointId(message.contactPointId)));
    }

    private UnifiedContact contactProjection(String contactPointId) throws IOException {
        String normalized = ContactPointUtil.normalizePointId(contactPointId);
        return contacts().stream()
                .filter(contact -> normalized.equals(contact.id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("CONTACT_NOT_FOUND"));
    }

    private static boolean isPhonePoint(String pointId) {
        if (pointId == null || !pointId.startsWith("phone:")) {
            return false;
        }
        String digits = pointId.substring("phone:".length());
        return digits.matches("[0-9]{6,20}");
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

    private static List<String> cleanTags(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values == null) {
            return new ArrayList<>();
        }
        for (String value : values) {
            String tag = value == null ? "" : value.trim();
            if (!tag.isBlank()) {
                result.add(tag);
            }
        }
        return new ArrayList<>(result);
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

    private static String cleanMediaType(String value) {
        String type = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (type.contains("image")) return "image";
        if (type.contains("video")) return "video";
        if (type.contains("document") || type.contains("file")) return "document";
        return type;
    }

    private static String firstRaw(String raw, String... keys) {
        return firstJsonValue(raw, keys);
    }

    private static String firstJsonValue(String raw, String... keys) {
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
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String nested = element.getAsString().trim();
            if ((nested.startsWith("{") && nested.endsWith("}")) || (nested.startsWith("[") && nested.endsWith("]"))) {
                try {
                    return findRecursive(JsonParser.parseString(nested), key);
                } catch (RuntimeException ignored) {
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
        String remark;
        List<String> tags = new ArrayList<>();
        List<String> points = new ArrayList<>();
    }

    private static class ContactAccumulator {
        final String primaryPointId;
        final Map<String, ContactPoint> points = new LinkedHashMap<>();
        final Set<String> channels = new LinkedHashSet<>();
        String displayName = "";
        String lastText = "";
        String lastTime = "";
        String lastDirection = "";
        String lastChannel = "";
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
            lastDirection = ContactPointUtil.firstNonBlank(message.direction, lastDirection);
            lastChannel = ContactPointUtil.firstNonBlank(message.channel, lastChannel);
            messageCount++;
        }

        UnifiedContact toContact(List<String> groupPoints, String remark, List<String> tags) {
            for (String groupPoint : groupPoints) {
                ContactPoint point = ContactPointUtil.fromId(groupPoint, null);
                points.putIfAbsent(groupPoint, point);
                channels.add(point.channel);
            }
            UnifiedContact contact = new UnifiedContact();
            contact.id = primaryPointId;
            contact.remark = remark;
            contact.displayName = ContactPointUtil.firstNonBlank(remark, displayName, primaryPointId);
            contact.tags.addAll(cleanTags(tags));
            contact.lastText = lastText;
            contact.lastTime = lastTime;
            contact.lastDirection = lastDirection;
            contact.lastChannel = lastChannel;
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
