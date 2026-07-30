package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WeComChatDataStore {
    private static final int MAX_CURSOR_BYTES = 1_048_576;
    private final Config config;

    public WeComChatDataStore(Config config) {
        this.config = config;
    }

    public synchronized String cursor(SyncKey key) throws WeComChatDataException {
        try {
            JsonObject cursors = readCursors();
            String field = keyField(key);
            return cursors.has(field) && cursors.get(field).isJsonPrimitive()
                    ? cursors.get(field).getAsString() : "";
        } catch (Exception exception) {
            throw storeFailed(exception);
        }
    }

    public synchronized PublishResult publishPage(SyncKey key, String nextCursor,
                                                    List<DecryptedMessage> decrypted)
            throws WeComChatDataException {
        try {
            requireKey(key);
            if (nextCursor == null || nextCursor.length() > 128 || decrypted == null || decrypted.size() > 200) {
                throw new IllegalArgumentException("page invalid");
            }
            Map<String, Candidate> candidates = readCandidates();
            int stored = 0;
            int skipped = 0;
            for (DecryptedMessage item : decrypted) {
                Candidate candidate = project(item);
                if (candidate == null) {
                    skipped++;
                    continue;
                }
                candidates.put(candidate.identity(), candidate);
                stored++;
            }
            List<Candidate> ordered = new ArrayList<>(candidates.values());
            ordered.sort(Comparator.comparingLong(Candidate::sendTime)
                    .thenComparing(Candidate::msgid)
                    .thenComparing(Candidate::userId)
                    .thenComparing(Candidate::externalUserId));
            int from = Math.max(0, ordered.size() - config.wecomChatDataStoreMaxMessages());
            StringBuilder jsonl = new StringBuilder();
            for (int index = from; index < ordered.size(); index++) {
                jsonl.append(ordered.get(index).json()).append('\n');
            }
            byte[] messageBytes = jsonl.toString().getBytes(StandardCharsets.UTF_8);
            if (messageBytes.length > config.wecomChatDataStoreMaxBytes()) {
                throw new IllegalArgumentException("message snapshot too large");
            }
            publish(config.wecomDataFile(), messageBytes);
            JsonObject cursors = readCursors();
            cursors.addProperty(keyField(key), nextCursor);
            byte[] cursorBytes = (cursors + "\n").getBytes(StandardCharsets.UTF_8);
            if (cursorBytes.length > MAX_CURSOR_BYTES) throw new IllegalArgumentException("cursor snapshot too large");
            publish(config.wecomChatDataCursorFile(), cursorBytes);
            return new PublishResult(stored, skipped);
        } catch (WeComChatDataException exception) {
            throw exception;
        } catch (Exception exception) {
            throw storeFailed(exception);
        }
    }

    public synchronized List<StoredMessageReference> load(Instant fromInclusive, Instant toExclusive)
            throws WeComChatDataException {
        try {
            if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
                throw new IllegalArgumentException("time window invalid");
            }
            long from = fromInclusive.getEpochSecond();
            long to = toExclusive.getEpochSecond();
            List<StoredMessageReference> result = new ArrayList<>();
            for (Candidate candidate : readCandidates().values()) {
                if (candidate.sendTime() < from || candidate.sendTime() >= to) continue;
                result.add(new StoredMessageReference(candidate.msgid(), candidate.secretKey(),
                        candidate.externalUserId(), candidate.userId(), candidate.sendTime(),
                        candidate.msgType()));
            }
            result.sort(Comparator.comparingLong(StoredMessageReference::sendTime)
                    .thenComparing(StoredMessageReference::msgid)
                    .thenComparing(StoredMessageReference::userId)
                    .thenComparing(StoredMessageReference::externalUserId));
            return List.copyOf(result);
        } catch (Exception exception) {
            throw storeFailed(exception);
        }
    }

    private Map<String, Candidate> readCandidates() throws IOException {
        Path path = config.wecomDataFile();
        Map<String, Candidate> result = new LinkedHashMap<>();
        if (!Files.exists(path)) return result;
        if (Files.size(path) > config.wecomChatDataStoreMaxBytes()) throw new IOException("snapshot too large");
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            Candidate candidate = new Candidate(
                    required(json, "msgid", 256),
                    required(json, "secret_key", 512),
                    required(json, "external_userid", 128),
                    required(json, "userid", 128),
                    nonNegativeLong(json, "send_time"),
                    required(json, "msgtype", 32));
            result.put(candidate.identity(), candidate);
        }
        return result;
    }

    private JsonObject readCursors() throws IOException {
        Path path = config.wecomChatDataCursorFile();
        if (!Files.exists(path)) return new JsonObject();
        if (Files.size(path) > MAX_CURSOR_BYTES) throw new IOException("cursor snapshot too large");
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private Candidate project(DecryptedMessage decrypted) {
        if (decrypted == null || decrypted.message() == null || decrypted.secretKey() == null
                || decrypted.secretKey().isBlank() || decrypted.secretKey().length() > 512) return null;
        WeComChatDataGateway.EncryptedMessage message = decrypted.message();
        if (message.receivers() == null || message.receivers().size() != 1 || message.sender() == null) return null;
        WeComChatDataGateway.Party receiver = message.receivers().get(0);
        String userId;
        String externalUserId;
        if (message.sender().type() == 1 && receiver.type() == 2) {
            userId = message.sender().id();
            externalUserId = receiver.id();
        } else if (message.sender().type() == 2 && receiver.type() == 1) {
            externalUserId = message.sender().id();
            userId = receiver.id();
        } else {
            return null;
        }
        if (!bounded(message.msgid(), 256) || !bounded(userId, 128) || !bounded(externalUserId, 128)
                || message.sendTime() < 0) return null;
        return new Candidate(message.msgid(), decrypted.secretKey(), externalUserId, userId,
                message.sendTime(), Integer.toString(message.msgType()));
    }

    private static boolean bounded(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum;
    }

    private static void requireKey(SyncKey key) {
        if (key == null || !bounded(key.installationId(), 128) || key.version() < 1
                || !bounded(key.programId(), 128) || !bounded(key.abilityId(), 128)) {
            throw new IllegalArgumentException("sync key invalid");
        }
    }

    private static String keyField(SyncKey key) throws Exception {
        requireKey(key);
        String value = key.installationId() + "\n" + key.version() + "\n"
                + key.programId() + "\n" + key.abilityId();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String required(JsonObject object, String field, int maximum) throws IOException {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()) throw new IOException("field missing");
        String value = object.get(field).getAsString();
        if (!bounded(value, maximum)) throw new IOException("field invalid");
        return value;
    }

    private static long nonNegativeLong(JsonObject object, String field) throws IOException {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()) throw new IOException("field missing");
        long value = object.get(field).getAsLong();
        if (value < 0) throw new IOException("field invalid");
        return value;
    }

    private static void publish(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("target parent missing");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("atomic replace unsupported", exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static WeComChatDataException storeFailed(Throwable cause) {
        return new WeComChatDataException("WECOM_CHATDATA_STORE_FAILED", 500,
                "企业微信会话索引保存失败", cause);
    }

    public record SyncKey(String installationId, long version, String programId, String abilityId) {}
    public record DecryptedMessage(WeComChatDataGateway.EncryptedMessage message, String secretKey) {}
    public record PublishResult(int stored, int skipped) {}
    public record StoredMessageReference(String msgid, String secretKey, String externalUserId,
                                         String userId, long sendTime, String msgType) {}

    private record Candidate(String msgid, String secretKey, String externalUserId, String userId,
                             long sendTime, String msgType) {
        private String identity() {
            return msgid + "\n" + userId + "\n" + externalUserId;
        }

        private String json() {
            JsonObject object = new JsonObject();
            object.addProperty("msgid", msgid);
            object.addProperty("secret_key", secretKey);
            object.addProperty("external_userid", externalUserId);
            object.addProperty("userid", userId);
            object.addProperty("send_time", sendTime);
            object.addProperty("msgtype", msgType);
            return object.toString();
        }
    }
}
