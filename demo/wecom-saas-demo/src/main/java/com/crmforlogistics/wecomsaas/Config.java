package com.crmforlogistics.wecomsaas;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class Config {
    private static final int DEFAULT_WEB_PORT = 8098;
    private static final long DEFAULT_MAX_UPLOAD_BYTES = 10_485_760L;
    private static final String DEFAULT_ARCHIVE_JSONL = "../weworkapi_python_yuewei/runtime/wecom/archive/messages.jsonl";

    private final Map<String, String> values;

    private Config(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static Config load(Path envFile) throws IOException {
        Map<String, String> values = new HashMap<>();
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                int separator = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || separator <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, separator).trim();
                values.put(key, stripQuotes(trimmed.substring(separator + 1).trim()));
            }
        }
        return new Config(values);
    }

    public static Config forTests(Path dataDir) {
        Map<String, String> values = new HashMap<>();
        values.put("DATA_DIR", dataDir.toString());
        return new Config(values);
    }

    public int webPort() {
        return Integer.parseInt(value("WEB_PORT", Integer.toString(DEFAULT_WEB_PORT)));
    }

    public Path dataDir() {
        return Path.of(value("DATA_DIR", "data"));
    }

    public Path uploadDir() {
        return dataDir().resolve("uploads");
    }

    public long maxUploadBytes() {
        return Long.parseLong(value("MAX_UPLOAD_BYTES", Long.toString(DEFAULT_MAX_UPLOAD_BYTES)));
    }

    public Path wecomArchiveJsonl() {
        return Path.of(value("WECOM_ARCHIVE_JSONL", DEFAULT_ARCHIVE_JSONL));
    }

    private String value(String key, String fallback) {
        String value = values.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
