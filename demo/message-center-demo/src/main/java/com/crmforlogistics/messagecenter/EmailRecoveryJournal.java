package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EmailRecoveryJournal {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long MAX_ENTRY_BYTES = 1_200_000L;
    private final Path root;

    EmailRecoveryJournal(Path emailDataDir) {
        this.root = emailDataDir.toAbsolutePath().normalize().resolve("attachment-recovery");
    }

    void prepare(EmailSendCommand command) throws IOException {
        write(command, "prepared", "");
    }

    void accepted(EmailSendCommand command, String smtpMessageId) throws IOException {
        write(command, "accepted", smtpMessageId == null ? "" : smtpMessageId);
    }

    void remove(String messageId) throws IOException {
        Files.deleteIfExists(pathFor(messageId));
    }

    List<RecoveryEntry> entries(int maxEntries) throws IOException {
        if (maxEntries <= 0 || !Files.exists(root)) return List.of();
        List<RecoveryEntry> result = new ArrayList<>();
        try (var entries = Files.list(root)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))) {
            for (Path path : entries.limit(maxEntries).toList()) {
                if (Files.size(path) > MAX_ENTRY_BYTES) {
                    throw new IOException("Email recovery entry exceeds its size limit: " + path.getFileName());
                }
                RecoveryEntry entry = parse(path);
                if (entry == null) throw new IOException("Invalid email recovery entry: " + path.getFileName());
                result.add(entry);
            }
        }
        return List.copyOf(result);
    }

    int reconcile(int maxEntries) throws IOException {
        return entries(maxEntries).size();
    }

    private RecoveryEntry parse(Path path) {
        try {
            JsonObject object = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            String messageId = JsonSupport.string(object, "messageId");
            String state = JsonSupport.string(object, "state");
            if (!("prepared".equals(state) || "accepted".equals(state))) return null;
            pathFor(messageId);
            List<RecoveryAttachment> attachments = new ArrayList<>();
            JsonArray array = object.has("attachments") && object.get("attachments").isJsonArray()
                    ? object.getAsJsonArray("attachments") : new JsonArray();
            for (var element : array) {
                if (!element.isJsonObject()) return null;
                JsonObject attachment = element.getAsJsonObject();
                long sizeBytes = attachment.has("sizeBytes") ? attachment.get("sizeBytes").getAsLong() : -1L;
                String temporaryPath = JsonSupport.string(attachment, "temporaryPath");
                String id = JsonSupport.string(attachment, "id");
                String fileName = JsonSupport.string(attachment, "fileName");
                String mimeType = JsonSupport.string(attachment, "mimeType");
                String sha256 = JsonSupport.string(attachment, "sha256");
                if (sizeBytes < 0 || temporaryPath.isBlank() || fileName.isBlank() || mimeType.isBlank()
                        || !id.matches("[0-9a-fA-F-]{36}") || !sha256.matches("[0-9a-f]{64}")) return null;
                attachments.add(new RecoveryAttachment(
                        id, fileName, mimeType, sizeBytes, sha256,
                        temporaryPath));
            }
            return new RecoveryEntry(messageId, state,
                    JsonSupport.string(object, "to"), JsonSupport.string(object, "subject"),
                    JsonSupport.string(object, "body"), JsonSupport.string(object, "smtpMessageId"),
                    List.copyOf(attachments));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void write(EmailSendCommand command, String state, String smtpMessageId) throws IOException {
        Files.createDirectories(root);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("messageId", command.messageId());
        record.put("state", state);
        record.put("to", command.to() == null ? "" : command.to());
        record.put("subject", command.subject() == null ? "" : command.subject());
        record.put("body", command.body() == null ? "" : command.body());
        record.put("smtpMessageId", smtpMessageId);
        record.put("attachments", command.attachments().stream().map(attachment -> Map.of(
                "id", attachment.id(),
                "fileName", attachment.fileName(),
                "mimeType", attachment.mimeType(),
                "sizeBytes", attachment.sizeBytes(),
                "sha256", attachment.sha256(),
                "temporaryPath", attachment.temporaryPath().toAbsolutePath().normalize().toString())).toList());
        record.put("updatedAt", Instant.now().toString());
        Path target = pathFor(command.messageId());
        Path temp = root.resolve(command.messageId() + ".tmp");
        Files.writeString(temp, GSON.toJson(record), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path pathFor(String messageId) {
        if (messageId == null || !messageId.matches("[A-Za-z0-9._-]{1,256}")) {
            throw new IllegalArgumentException("invalid recovery message id");
        }
        Path path = root.resolve(messageId + ".json").normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("invalid recovery message id");
        return path;
    }
}

record RecoveryEntry(String messageId, String state, String to, String subject, String body,
                     String smtpMessageId, List<RecoveryAttachment> attachments) {
    List<StagedAttachment> stagedAttachments() {
        return attachments.stream().map(attachment -> new StagedAttachment(
                attachment.id(), attachment.fileName(), attachment.mimeType(), attachment.sizeBytes(),
                attachment.sha256(), Path.of(attachment.temporaryPath()))).toList();
    }
}

record RecoveryAttachment(String id, String fileName, String mimeType, long sizeBytes,
                          String sha256, String temporaryPath) { }
