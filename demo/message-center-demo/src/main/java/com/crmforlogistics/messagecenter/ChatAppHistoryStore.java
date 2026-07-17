package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class ChatAppHistoryStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Config config;

    public ChatAppHistoryStore(Config config) {
        this.config = config;
    }

    public enum WriteResult {
        INSERTED,
        UPDATED,
        SKIPPED
    }

    public Optional<Instant> latestTimestamp() throws Exception {
        if (!Files.exists(config.chatappDataFile())) {
            return Optional.empty();
        }
        Instant latest = Instant.EPOCH;
        for (String line : Files.readAllLines(config.chatappDataFile(), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                Instant timestamp = MessageTime.parseInstant(JsonSupport.string(object, "timestamp"));
                if (timestamp.isAfter(latest)) {
                    latest = timestamp;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return latest.equals(Instant.EPOCH) ? Optional.empty() : Optional.of(latest);
    }

    public boolean append(String id, String direction, String from, String to, String text, String status,
                          String raw, Map<String, String> extra) throws Exception {
        return append(id, direction, from, to, text, status, Instant.now().toString(), raw, extra);
    }

    public boolean append(String id, String direction, String from, String to, String text, String status,
                          String timestamp, String raw, Map<String, String> extra) throws Exception {
        return appendResult(id, direction, from, to, text, status, timestamp, raw, extra) != WriteResult.SKIPPED;
    }

    public WriteResult appendResult(String id, String direction, String from, String to, String text, String status,
                                    String timestamp, String raw, Map<String, String> extra) throws Exception {
        String messageId = ContactPointUtil.firstNonBlank(id, "sync-" + UUID.randomUUID());
        if (config.chatappDataFile().getParent() != null) {
            Files.createDirectories(config.chatappDataFile().getParent());
        }
        Map<String, String> record = messageRecord(messageId, direction, from, to, text, status, timestamp, raw, extra);
        if (Files.exists(config.chatappDataFile())) {
            List<String> lines = Files.readAllLines(config.chatappDataFile(), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                    if (!messageId.equals(JsonSupport.string(object, "id"))) {
                        continue;
                    }
                    Map<String, String> existing = toStringMap(object);
                    Map<String, String> merged = mergeRecord(existing, record);
                    if (existing.equals(merged)) {
                        return WriteResult.SKIPPED;
                    }
                    lines.set(i, GSON.toJson(merged));
                    Files.write(config.chatappDataFile(), lines, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    return WriteResult.UPDATED;
                } catch (RuntimeException ignored) {
                }
            }
        }
        Files.writeString(config.chatappDataFile(), GSON.toJson(record) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return WriteResult.INSERTED;
    }

    private static Map<String, String> messageRecord(String messageId, String direction, String from, String to,
                                                     String text, String status, String timestamp, String raw,
                                                     Map<String, String> extra) {
        Map<String, String> record = new LinkedHashMap<>();
        record.put("id", messageId);
        record.put("direction", normalizeDirection(direction));
        record.put("timestamp", ContactPointUtil.firstNonBlank(MessageTime.timestampString(timestamp), Instant.now().toString()));
        record.put("from", from == null ? "" : from);
        record.put("to", to == null ? "" : to);
        record.put("text", text == null ? "" : text);
        if (status != null && !status.isBlank()) {
            record.put("status", cleanStatus(status));
            record.put("statusTimestamp", record.get("timestamp"));
        }
        if (extra != null) {
            record.putAll(extra);
        }
        record.put("raw", raw == null ? "" : raw);
        return record;
    }

    private static Map<String, String> mergeRecord(Map<String, String> existing, Map<String, String> incoming) {
        Map<String, String> merged = new LinkedHashMap<>(existing);
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            String value = entry.getValue();
            if (("timestamp".equals(entry.getKey()) || "statusTimestamp".equals(entry.getKey()))
                    && merged.containsKey(entry.getKey()) && !merged.get(entry.getKey()).isBlank()) {
                continue;
            }
            if (value != null && (!value.isBlank() || !merged.containsKey(entry.getKey()))) {
                merged.put(entry.getKey(), value);
            }
        }
        return merged;
    }

    private static Map<String, String> toStringMap(JsonObject object) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : object.entrySet()) {
            result.put(entry.getKey(), entry.getValue().isJsonPrimitive()
                    ? entry.getValue().getAsString()
                    : entry.getValue().toString());
        }
        return result;
    }

    private static String normalizeDirection(String direction) {
        String value = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        if ("in".equals(value) || "inbound".equals(value)) {
            return "inbound";
        }
        if ("status".equals(value)) {
            return "status";
        }
        return "outbound";
    }

    private static String cleanStatus(String status) {
        String value = status == null ? "" : status.trim();
        if (value.regionMatches(true, 0, "Status:", 0, "Status:".length())) {
            value = value.substring("Status:".length()).trim();
        }
        if (value.startsWith("状态:")) {
            value = value.substring("状态:".length()).trim();
        }
        return value;
    }
}
