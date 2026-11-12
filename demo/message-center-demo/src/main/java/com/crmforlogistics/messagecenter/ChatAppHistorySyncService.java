package com.crmforlogistics.messagecenter;

import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChatAppHistorySyncService implements AutoCloseable {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Duration LIST_REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MEDIA_CLOSE_TIMEOUT = Duration.ofSeconds(15);

    private final Config config;
    private final ChatAppHistoryStore historyStore;
    private final TemplateStore templateStore;
    private final MediaCacher mediaCacher;
    private final ChatAppTemplateSynchronizer templateSynchronizer;
    private final ChatAppMessageGateway.Factory gatewayFactory;
    private final ThreadPoolExecutor mediaExecutor;
    private final AtomicBoolean mediaClosed = new AtomicBoolean();
    private final Object mediaLifecycleLock = new Object();

    @FunctionalInterface
    interface CommitAction {
        void run() throws Exception;
    }

    @FunctionalInterface
    interface CommitGate {
        void commit(CommitAction action) throws Exception;
    }

    interface MediaCacher {
        void cache(UnifiedMessage message) throws Exception;
    }

    public ChatAppHistorySyncService(Config config) {
        this(config, ChatAppTemplateSynchronizer.create(config));
    }

    ChatAppHistorySyncService(Config config, ChatAppTemplateSynchronizer templateSynchronizer) {
        this(config, new ChatAppHistoryStore(config), new TemplateStore(config.chatappTemplateFile()),
                defaultMediaCacher(config), templateSynchronizer,
                () -> AliyunChatAppMessageGateway.open(config));
    }

    ChatAppHistorySyncService(Config config, ChatAppHistoryStore historyStore, TemplateStore templateStore) {
        this(config, historyStore, templateStore, defaultMediaCacher(config),
                ChatAppTemplateSynchronizer.create(config),
                () -> AliyunChatAppMessageGateway.open(config));
    }

    ChatAppHistorySyncService(Config config, ChatAppHistoryStore historyStore, TemplateStore templateStore, MediaCacher mediaCacher) {
        this(config, historyStore, templateStore, mediaCacher,
                ChatAppTemplateSynchronizer.create(config),
                () -> AliyunChatAppMessageGateway.open(config));
    }

    ChatAppHistorySyncService(Config config, ChatAppHistoryStore historyStore,
                              TemplateStore templateStore, MediaCacher mediaCacher,
                              ChatAppTemplateSynchronizer templateSynchronizer,
                              ChatAppMessageGateway.Factory gatewayFactory) {
        this(config, historyStore, templateStore, mediaCacher, templateSynchronizer,
                gatewayFactory, createMediaExecutor(config));
    }

    ChatAppHistorySyncService(Config config, ChatAppHistoryStore historyStore,
                              TemplateStore templateStore, MediaCacher mediaCacher,
                              ChatAppTemplateSynchronizer templateSynchronizer,
                              ChatAppMessageGateway.Factory gatewayFactory,
                              ThreadPoolExecutor mediaExecutor) {
        this.config = Objects.requireNonNull(config);
        this.historyStore = Objects.requireNonNull(historyStore);
        this.templateStore = Objects.requireNonNull(templateStore);
        this.mediaCacher = Objects.requireNonNull(mediaCacher);
        this.templateSynchronizer = Objects.requireNonNull(templateSynchronizer);
        this.gatewayFactory = Objects.requireNonNull(gatewayFactory);
        this.mediaExecutor = Objects.requireNonNull(mediaExecutor);
    }

    static MediaCacher defaultMediaCacher(Config config) {
        MediaGateway gateway = new MediaGateway(config);
        return gateway::fetch;
    }

    SyncResult syncMessages(CommitGate commitGate) throws Exception {
        Objects.requireNonNull(commitGate);
        long startedNanos = System.nanoTime();
        int pageSize = Integer.parseInt(config.value("SYNC_PAGE_SIZE", "20"));
        int maxPages = Integer.parseInt(config.value("SYNC_MAX_PAGES", "2"));
        long startTime = syncStartTime();
        long endTime = syncEndTime();
        SyncResult result = new SyncResult("chatapp");
        result.syncStartTime = startTime;
        result.syncEndTime = endTime;

        if (Boolean.parseBoolean(config.value("SYNC_TEMPLATES_BEFORE_MESSAGES", "false"))) {
            SyncResult templates = syncTemplates();
            result.templatesFetched = templates.fetched;
            result.templatesSaved = templates.saved;
            result.templatesSkipped = templates.skipped;
        }

        try (ChatAppMessageGateway gateway = openGateway()) {
            for (int pageIndex = 1; pageIndex <= maxPages; pageIndex++) {
                ChatAppMessageGateway.MessageRequest request = new ChatAppMessageGateway.MessageRequest(
                        startTime, endTime, pageIndex, pageSize, requiredConfig("CUST_SPACE_ID"),
                        config.value("CHATAPP_CHANNEL_TYPE", ""), config.value("CHATAPP_FROM", ""),
                        config.value("SYNC_USER_NUMBER", ""), config.value("SYNC_MESSAGE_STATUS", ""),
                        config.value("SYNC_CLIENT_ACCEPT_STATUS", ""));
                ChatAppMessageGateway.MessagePage page = gateway.listMessages(request, LIST_REQUEST_TIMEOUT);
                if (page == null) {
                    throw new IllegalStateException("ListChatappMessage returned empty page");
                }
                result.pages++;
                List<ListChatappMessageResponseBody.Data> rows = page.messages() == null
                        ? List.of() : page.messages();
                if (rows.isEmpty()) {
                    break;
                }
                result.fetched += rows.size();
                for (ListChatappMessageResponseBody.Data row : rows) {
                    ProjectedChatAppMessage message = project(row);
                    commitGate.commit(() -> appendAndPrecache(message, result));
                }
                if (rows.size() < pageSize) {
                    break;
                }
            }
        }
        result.durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        result.message = "synced " + result.saved + " new ChatApp messages, refreshed " + result.updated
                + ", pages " + result.pages + ", duration " + result.durationMillis + "ms"
                + ", queued " + result.mediaQueued + " media, cached " + result.mediaCached
                + " media, failed " + result.mediaFailed + " media";
        return result;
    }

    void appendAndPrecache(ProjectedChatAppMessage message, SyncResult result) throws Exception {
        ChatAppHistoryStore.WriteResult writeResult = historyStore.appendResult(message.id, message.direction, message.from, message.to, message.text,
                message.status, message.timestamp, message.raw, message.extra);
        switch (writeResult) {
            case INSERTED -> result.saved++;
            case UPDATED -> result.updated++;
            case SKIPPED -> result.skipped++;
        }
        UnifiedMessage unified = toUnifiedMessage(message);
        if (!shouldPrecacheMedia(writeResult) || !hasMediaReference(unified)) {
            return;
        }
        String mode = mediaPrecacheMode();
        if ("off".equals(mode)) {
            return;
        }
        if ("background".equals(mode)) {
            queueMediaCache(unified, result);
            return;
        }
        try {
            mediaCacher.cache(unified);
            result.mediaCached++;
        } catch (Exception ex) {
            result.mediaFailed++;
            result.recordMediaFailure(unified, ex);
        }
    }

    private boolean shouldPrecacheMedia(ChatAppHistoryStore.WriteResult writeResult) {
        return writeResult != ChatAppHistoryStore.WriteResult.SKIPPED
                || Boolean.parseBoolean(config.value("CHATAPP_MEDIA_PRECACHE_SKIPPED", "false"));
    }

    private String mediaPrecacheMode() {
        String mode = config.value("CHATAPP_MEDIA_PRECACHE_MODE", "").trim().toLowerCase();
        if (!mode.isBlank()) {
            return switch (mode) {
                case "inline", "background", "off" -> mode;
                default -> "background";
            };
        }
        return Boolean.parseBoolean(config.value("CHATAPP_MEDIA_PRECACHE_ON_SYNC", "true")) ? "background" : "off";
    }

    private void queueMediaCache(UnifiedMessage unified, SyncResult result) {
        synchronized (mediaLifecycleLock) {
            if (mediaClosed.get()) {
                recordMediaQueueFailure(unified, result, "media cache service is closed");
                return;
            }
            try {
                mediaExecutor.execute(() -> {
                    try {
                        mediaCacher.cache(unified);
                    } catch (Exception ex) {
                        System.err.println("ChatApp media precache failed for "
                                + ContactPointUtil.firstNonBlank(unified.sourceId, unified.id) + ": " + ex.getMessage());
                    }
                });
                result.mediaQueued++;
            } catch (RejectedExecutionException ex) {
                String reason = mediaClosed.get()
                        ? "media cache service is closed" : "media cache queue is full";
                recordMediaQueueFailure(unified, result, reason);
            }
        }
    }

    private static void recordMediaQueueFailure(UnifiedMessage unified, SyncResult result, String reason) {
        result.mediaFailed++;
        result.recordMediaFailure(unified, new IllegalStateException(reason));
    }

    private static ThreadPoolExecutor createMediaExecutor(Config config) {
        int threads = Math.max(1, Integer.parseInt(config.value("CHATAPP_MEDIA_PRECACHE_THREADS", "1")));
        int queueSize = Math.max(1, Integer.parseInt(config.value("CHATAPP_MEDIA_PRECACHE_QUEUE_SIZE", "100")));
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "chatapp-media-precache");
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize), factory, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static UnifiedMessage toUnifiedMessage(ProjectedChatAppMessage message) {
        UnifiedMessage unified = new UnifiedMessage();
        unified.sourceId = message.id;
        unified.id = "chatapp:" + ContactPointUtil.firstNonBlank(message.id, Integer.toHexString(Objects.hashCode(message.raw)));
        unified.channel = "chatapp";
        unified.direction = message.direction;
        unified.timestamp = message.timestamp;
        unified.from = message.from;
        unified.to = message.to;
        unified.text = message.text;
        unified.summary = message.text;
        unified.status = message.status;
        unified.raw = message.raw;
        String peer = "outbound".equals(message.direction) ? message.to : message.from;
        unified.contactPointId = ContactPointUtil.normalizePointId("chatapp:whatsapp:" + peer);
        unified.mediaType = extraValue(message, "mediaType");
        unified.mediaUrl = extraValue(message, "mediaUrl");
        unified.objectKey = extraValue(message, "objectKey");
        unified.mimeType = extraValue(message, "mimeType");
        unified.fileName = extraValue(message, "fileName");
        return unified;
    }

    private static String extraValue(ProjectedChatAppMessage message, String key) {
        return message.extra == null ? "" : ContactPointUtil.firstNonBlank(message.extra.get(key), "");
    }

    private static boolean hasMediaReference(UnifiedMessage message) {
        return !ContactPointUtil.firstNonBlank(message.mediaUrl, message.objectKey).isBlank();
    }

    public SyncResult syncTemplates() throws Exception {
        ChatAppTemplateSynchronizer.Outcome outcome = templateSynchronizer.sync();
        SyncResult result = new SyncResult("chatapp-templates");
        result.fetched = outcome.fetched();
        result.saved = outcome.changed();
        result.skipped = outcome.status() == ChatAppTemplateSynchronizer.Status.UNCHANGED
                ? outcome.fetched() : Math.max(0, outcome.fetched() - outcome.changed());
        result.pages = outcome.pages();
        result.durationMillis = outcome.durationMillis();
        result.message = "template sync " + outcome.status().name().toLowerCase(Locale.ROOT)
                + ", fetched " + outcome.fetched() + ", changed " + outcome.changed()
                + ", count " + outcome.count();
        return result;
    }

    private ProjectedChatAppMessage project(ListChatappMessageResponseBody.Data row) {
        String raw = GSON.toJson(row);
        String id = ContactPointUtil.firstNonBlank(row.getMessageId(), row.getUniqueMessageId(), "sync-" + UUID.randomUUID());
        String businessNumber = ContactPointUtil.firstNonBlank(row.getBusinessNumber(), config.value("CHATAPP_FROM", ""));
        String userNumber = row.getUserNumber();
        String direction = inferDirection(row.getMessageSource(), row.getEventAction(), row.getType());
        String text = historyDisplayText(row);
        Map<String, String> extra = mediaExtra(row, raw);
        ProjectedChatAppMessage message = new ProjectedChatAppMessage();
        message.id = id;
        message.direction = direction;
        message.from = "inbound".equals(direction) ? userNumber : businessNumber;
        message.to = "inbound".equals(direction) ? businessNumber : userNumber;
        message.text = text;
        message.status = ContactPointUtil.firstNonBlank(row.getMessageStatusName(), row.getMessageStatus(), "");
        message.timestamp = normalizeTimestamp(row.getSendTime());
        message.raw = raw;
        message.extra = extra;
        return message;
    }

    private String historyDisplayText(ListChatappMessageResponseBody.Data row) {
        String message = ContactPointUtil.firstNonBlank(row.getMessage(), row.getFailReason(), "");
        String type = ContactPointUtil.firstNonBlank(row.getMessageTypeName(), row.getMessageType(), "");
        if (type.equalsIgnoreCase("template") || type.equalsIgnoreCase("TEMPLATE")) {
            return templateStore.render(row.getTemplateCode(), row.getLanguageCode(), message, row.getTemplateName());
        }
        String text = JsonSupport.textField(message);
        if (!text.isBlank()) {
            return text;
        }
        String mediaText = mediaDisplayTextFromMessage(type, message);
        return ContactPointUtil.firstNonBlank(mediaText, message, type);
    }

    private static Map<String, String> mediaExtra(ListChatappMessageResponseBody.Data row, String raw) {
        Map<String, String> extra = new LinkedHashMap<>();
        String type = ContactPointUtil.firstNonBlank(row.getMessageTypeName(), row.getMessageType(), "");
        String message = row.getMessage();
        String link = firstJsonValue(message, "url", "link", "mediaUrl");
        String fileName = firstJsonValue(message, "fileName");
        String caption = firstJsonValue(message, "caption");
        String mimeType = firstJsonValue(message, "mimeType", "contentType");
        String objectKey = firstJsonValue(message, "objectKey", "ossObjectKey");
        if (!type.isBlank()) {
            extra.put("mediaType", type);
        }
        if (!link.isBlank()) {
            extra.put("mediaUrl", link);
        }
        if (!fileName.isBlank()) {
            extra.put("fileName", fileName);
        }
        if (!caption.isBlank()) {
            extra.put("caption", caption);
        }
        objectKey = ContactPointUtil.firstNonBlank(objectKey, firstJsonValue(raw, "objectKey", "ossObjectKey"));
        if (!objectKey.isBlank()) {
            extra.put("objectKey", objectKey);
        }
        if (!mimeType.isBlank()) {
            extra.put("mimeType", mimeType);
        }
        return extra;
    }

    private static String mediaDisplayTextFromMessage(String type, String message) {
        String normalized = type == null ? "" : type.toLowerCase();
        if (!normalized.contains("image") && !normalized.contains("video") && !normalized.contains("document")
                && !normalized.contains("file")) {
            return "";
        }
        String caption = firstJsonValue(message, "caption");
        String fileName = firstJsonValue(message, "fileName");
        String label = normalized.contains("image") ? "image" : normalized.contains("video") ? "video" : "file";
        String suffix = ContactPointUtil.firstNonBlank(caption, fileName, "");
        return suffix.isBlank() ? "[" + label + "]" : "[" + label + "] " + suffix;
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
                    return entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : entry.getValue().toString();
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

    private static String inferDirection(String messageSource, String eventAction, String type) {
        String marker = ContactPointUtil.firstNonBlank(messageSource, eventAction, type, "").toLowerCase();
        if (marker.contains("in") || marker.contains("up") || marker.contains("receive")
                || marker.contains("user") || marker.contains("customer") || "mo".equals(marker)) {
            return "inbound";
        }
        return "outbound";
    }

    private long syncStartTime() throws Exception {
        String configured = config.value("SYNC_START_TIME", "");
        if (!configured.isBlank()) {
            return Long.parseLong(configured);
        }
        String configuredText = config.value("SYNC_START_TIME_STR", "");
        if (!configuredText.isBlank()) {
            return parseTime(configuredText).toEpochMilli();
        }
        if (Boolean.parseBoolean(config.value("SYNC_INCREMENTAL", "true"))) {
            long overlapMinutes = Math.max(0, Long.parseLong(config.value("SYNC_OVERLAP_MINUTES", "30")));
            java.util.Optional<Instant> latest = historyStore.latestTimestamp();
            if (latest.isPresent()) {
                return latest.get().minus(Duration.ofMinutes(overlapMinutes)).toEpochMilli();
            }
        }
        long lookbackDays = Long.parseLong(config.value("SYNC_LOOKBACK_DAYS", "1"));
        return Instant.now().minus(Duration.ofDays(lookbackDays)).toEpochMilli();
    }

    private long syncEndTime() {
        String configured = config.value("SYNC_END_TIME", "");
        if (!configured.isBlank()) {
            return Long.parseLong(configured);
        }
        String configuredText = config.value("SYNC_END_TIME_STR", "");
        if (!configuredText.isBlank()) {
            return parseTime(configuredText).toEpochMilli();
        }
        return Instant.now().toEpochMilli();
    }

    private ChatAppMessageGateway openGateway() throws Exception {
        ChatAppMessageGateway gateway = gatewayFactory.open();
        if (gateway == null) {
            throw new IllegalStateException("gateway_factory_returned_null");
        }
        return gateway;
    }

    private static Instant parseTime(String value) {
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (RuntimeException ignored) {
        }
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        );
        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDateTime.parse(trimmed, formatter)
                        .atZone(ZoneId.systemDefault())
                        .toInstant();
            } catch (RuntimeException ignored) {
            }
        }
        return Instant.ofEpochMilli(Long.parseLong(trimmed));
    }

    private String requiredConfig(String key) {
        String value = config.value(key, "");
        if (value.isBlank()) {
            throw new IllegalStateException("缺少配置: " + key);
        }
        return value;
    }

    private static String normalizeTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now().toString();
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed).toString();
        } catch (RuntimeException ignored) {
        }
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        );
        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDateTime.parse(trimmed, formatter)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toString();
            } catch (RuntimeException ignored) {
            }
        }
        return Instant.now().toString();
    }

    @Override
    public void close() {
        synchronized (mediaLifecycleLock) {
            if (!mediaClosed.compareAndSet(false, true)) {
                return;
            }
            mediaExecutor.shutdownNow();
        }
        boolean interrupted = false;
        long deadline = System.nanoTime() + MEDIA_CLOSE_TIMEOUT.toNanos();
        while (!mediaExecutor.isTerminated()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                mediaExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static class ProjectedChatAppMessage {
        String id;
        String direction;
        String from;
        String to;
        String text;
        String status;
        String timestamp;
        String raw;
        Map<String, String> extra = new LinkedHashMap<>();
    }
}
