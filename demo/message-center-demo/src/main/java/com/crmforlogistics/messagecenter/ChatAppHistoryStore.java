package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

public class ChatAppHistoryStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final ConcurrentMap<Path, ReentrantLock> PROCESS_LOCKS = new ConcurrentHashMap<>();

    @FunctionalInterface
    interface AtomicCommitter {
        void move(Path source, Path target) throws IOException;
    }

    private final Path file;
    private final ReentrantLock processLock;
    private final AtomicCommitter atomicCommitter;

    public ChatAppHistoryStore(Config config) {
        this(config, (source, target) -> Files.move(source, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    ChatAppHistoryStore(Config config, AtomicCommitter atomicCommitter) {
        this.file = config.chatappDataFile().toAbsolutePath().normalize();
        this.processLock = PROCESS_LOCKS.computeIfAbsent(file, ignored -> new ReentrantLock());
        this.atomicCommitter = Objects.requireNonNull(atomicCommitter);
    }

    public enum WriteResult {
        INSERTED,
        UPDATED,
        SKIPPED
    }

    public Optional<Instant> latestTimestamp() throws Exception {
        processLock.lockInterruptibly();
        try {
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            Instant latest = Instant.EPOCH;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
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
        } finally {
            processLock.unlock();
        }
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
        Map<String, String> record = messageRecord(messageId, direction, from, to, text, status, timestamp, raw, extra);
        processLock.lockInterruptibly();
        try {
            Files.createDirectories(file.getParent());
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            try (FileChannel lockChannel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {
                List<String> lines = Files.exists(file)
                        ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                        : new ArrayList<>();
                Mutation mutation = mergeOrAppend(lines, record);
                if (mutation.result() == WriteResult.SKIPPED) {
                    return WriteResult.SKIPPED;
                }
                Path temp = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
                try {
                    byte[] bytes = serializeLines(lines);
                    try (FileChannel output = FileChannel.open(temp,
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        ByteBuffer buffer = ByteBuffer.wrap(bytes);
                        while (buffer.hasRemaining()) {
                            output.write(buffer);
                        }
                        output.force(true);
                    }
                    atomicCommitter.move(temp, file);
                } finally {
                    Files.deleteIfExists(temp);
                }
                return mutation.result();
            }
        } finally {
            processLock.unlock();
        }
    }

    private static Mutation mergeOrAppend(List<String> lines, Map<String, String> record) {
        String messageId = record.get("id");
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
                    return new Mutation(WriteResult.SKIPPED);
                }
                lines.set(i, GSON.toJson(merged));
                return new Mutation(WriteResult.UPDATED);
            } catch (RuntimeException ignored) {
            }
        }
        lines.add(GSON.toJson(record));
        return new Mutation(WriteResult.INSERTED);
    }

    private static byte[] serializeLines(List<String> lines) {
        StringBuilder content = new StringBuilder();
        for (String line : lines) {
            content.append(line).append(System.lineSeparator());
        }
        return content.toString().getBytes(StandardCharsets.UTF_8);
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

    private record Mutation(WriteResult result) {
    }
}
