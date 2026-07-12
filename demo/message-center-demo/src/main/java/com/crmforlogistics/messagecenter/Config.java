package com.crmforlogistics.messagecenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class Config {
    private final Map<String, String> values;

    public Config(Map<String, String> values) {
        this.values = values;
    }

    public static Config load(Path envPath) throws IOException {
        Map<String, String> loaded = new HashMap<>();
        if (Files.exists(envPath)) {
            for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                String[] parts = trimmed.split("=", 2);
                loaded.put(parts[0].trim(), stripQuotes(parts[1].trim()));
            }
        }
        System.getenv().forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                loaded.put(key, value);
            }
        });
        return new Config(loaded);
    }

    static Config forTests(Path dataDir, Path emailDataDir, Path chatappDataFile, Path templateFile) {
        Map<String, String> values = new HashMap<>();
        values.put("DATA_DIR", dataDir.toString());
        values.put("EMAIL_DATA_DIR", emailDataDir.toString());
        values.put("CHATAPP_DATA_FILE", chatappDataFile.toString());
        values.put("CHATAPP_TEMPLATE_FILE", templateFile.toString());
        values.put("CONTACT_GROUP_FILE", dataDir.resolve("contact-groups.jsonl").toString());
        values.put("EMAIL_CONTACT_GROUP_FILE", emailDataDir.resolve("contact-groups.jsonl").toString());
        return new Config(values);
    }

    public String value(String key, String defaultValue) {
        String value = values.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public Path dataDir() { return Path.of(value("DATA_DIR", "data")); }
    public Path contactGroupFile() { return Path.of(value("CONTACT_GROUP_FILE", dataDir().resolve("contact-groups.jsonl").toString())); }
    public Path emailDataDir() { return Path.of(value("EMAIL_DATA_DIR", "../email-send-receive-demo/data")); }
    public Path emailInboxFile() { return emailDataDir().resolve("inbox.jsonl"); }
    public Path emailContactGroupFile() { return Path.of(value("EMAIL_CONTACT_GROUP_FILE", emailDataDir().resolve("contact-groups.jsonl").toString())); }
    public Path chatappDataFile() { return Path.of(value("CHATAPP_DATA_FILE", "../chatapp-send-receive-demo/data/messages.jsonl")); }
    public Path chatappTemplateFile() { return Path.of(value("CHATAPP_TEMPLATE_FILE", "../chatapp-send-receive-demo/data/templates.json")); }
    public int webPort() { return Integer.parseInt(value("WEB_PORT", value("MESSAGE_CENTER_PORT", "8099"))); }
    public long mediaMaxBytes() { return Long.parseLong(value("MEDIA_MAX_BYTES", "20971520")); }

    private static String stripQuotes(String value) {
        if (value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value == null ? "" : value;
    }
}