package com.crmforlogistics.emaildemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public record MailConfig(
        String smtpHost,
        int smtpPort,
        boolean smtpSsl,
        boolean smtpStarttls,
        String smtpUsername,
        String smtpPassword,
        String mailFrom,
        String mailFromName,
        String mailTo,
        String imapHost,
        int imapPort,
        boolean imapSsl,
        String imapUsername,
        String imapPassword,
        String inboxFolder,
        String sentFolder,
        int receiveLimit,
        int watchPollSeconds,
        Path dataDir,
        int webPort
) {
    public static MailConfig load() throws IOException {
        Map<String, String> values = new HashMap<>();
        loadDotEnv(values, Path.of(".env"));

        return new MailConfig(
                required(values, "SMTP_HOST"),
                integer(values, "SMTP_PORT", 465),
                bool(values, "SMTP_SSL", true),
                bool(values, "SMTP_STARTTLS", false),
                required(values, "SMTP_USERNAME"),
                required(values, "SMTP_PASSWORD"),
                optional(values, "MAIL_FROM", required(values, "SMTP_USERNAME")),
                optional(values, "MAIL_FROM_NAME", "CRM Logistics Mail Demo"),
                optional(values, "MAIL_TO", optional(values, "MAIL_FROM", required(values, "SMTP_USERNAME"))),
                required(values, "IMAP_HOST"),
                integer(values, "IMAP_PORT", 993),
                bool(values, "IMAP_SSL", true),
                required(values, "IMAP_USERNAME"),
                required(values, "IMAP_PASSWORD"),
                optional(values, "INBOX_FOLDER", "INBOX"),
                optional(values, "SENT_FOLDER", "Sent"),
                integer(values, "RECEIVE_LIMIT", 10),
                integer(values, "WATCH_POLL_SECONDS", 30),
                Path.of(optional(values, "DATA_DIR", "data")),
                integer(values, "WEB_PORT", 8088)
        );
    }

    private static void loadDotEnv(Map<String, String> values, Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int split = trimmed.indexOf('=');
            if (split <= 0) {
                continue;
            }
            String key = trimmed.substring(0, split).trim();
            String value = stripQuotes(trimmed.substring(split + 1).trim());
            values.put(key, value);
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = values.get(key);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少配置: " + key);
        }
        return value;
    }

    private static String optional(Map<String, String> values, String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = values.get(key);
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean bool(Map<String, String> values, String key, boolean fallback) {
        String value = optional(values, key, Boolean.toString(fallback));
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private static int integer(Map<String, String> values, String key, int fallback) {
        String value = optional(values, key, Integer.toString(fallback));
        return Integer.parseInt(value);
    }

    private static String stripQuotes(String value) {
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
