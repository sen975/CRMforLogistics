package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

final class EmailRecoveryJournal {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Path root;

    EmailRecoveryJournal(Path emailDataDir) {
        this.root = emailDataDir.resolve("attachment-recovery");
    }

    void prepare(String messageId, String to, String subject, String body) throws IOException {
        write(messageId, "prepared", to, subject, body);
    }

    void accepted(String messageId, String to, String subject, String body) throws IOException {
        write(messageId, "accepted", to, subject, body);
    }

    void remove(String messageId) throws IOException {
        Files.deleteIfExists(root.resolve(messageId + ".json"));
    }

    int reconcile(int maxEntries) throws IOException {
        if (maxEntries <= 0 || !Files.exists(root)) return 0;
        int count = 0;
        try (var entries = Files.list(root).filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))) {
            for (Path path : entries.limit(maxEntries).toList()) {
                count++;
                // Recovery decisions are made by the caller after inspecting state.
            }
        }
        return count;
    }

    private void write(String messageId, String state, String to, String subject, String body) throws IOException {
        Files.createDirectories(root);
        Map<String, String> record = new LinkedHashMap<>();
        record.put("messageId", messageId);
        record.put("state", state);
        record.put("to", to == null ? "" : to);
        record.put("subject", subject == null ? "" : subject);
        record.put("body", body == null ? "" : body);
        record.put("updatedAt", Instant.now().toString());
        Path target = root.resolve(messageId + ".json");
        Path temp = root.resolve(messageId + ".tmp");
        Files.writeString(temp, GSON.toJson(record), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
