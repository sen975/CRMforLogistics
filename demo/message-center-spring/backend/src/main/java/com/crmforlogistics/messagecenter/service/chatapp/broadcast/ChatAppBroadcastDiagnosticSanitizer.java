package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import java.util.regex.Pattern;

final class ChatAppBroadcastDiagnosticSanitizer {
    private static final int MAX_LENGTH = 1000;
    private static final String SENSITIVE_KEY =
            "AccessKeyId|AccessKeySecret|Signature|SecurityToken|X-Acs-Security-Token|Authorization|Token|Password|Secret";
    private static final Pattern SIGNED_URL = Pattern.compile(
            "(?i)(https?://[^\\s?]+)\\?[^\\s;]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(Authorization\\s*[:=]\\s*)(?:Bearer|Basic|OSS)\\s+[^\\s,;]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\\\"(?:" + SENSITIVE_KEY + ")\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)(\\b(?:" + SENSITIVE_KEY + ")\\b\\s*[:=]\\s*)[^\\s,;}&]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_NUMBER = Pattern.compile(
            "(?<![A-Za-z0-9])\\+?\\d{7,15}(?![A-Za-z0-9])");

    private ChatAppBroadcastDiagnosticSanitizer() {
    }

    static String sanitize(String value) {
        if (value == null || value.isBlank()) return "";
        String sanitized = value.replace('\r', ' ').replace('\n', ' ').trim();
        sanitized = SIGNED_URL.matcher(sanitized).replaceAll("$1?[REDACTED]");
        sanitized = AUTHORIZATION.matcher(sanitized).replaceAll("$1[REDACTED]");
        sanitized = JSON_SECRET.matcher(sanitized).replaceAll("$1[REDACTED]$2");
        sanitized = KEY_VALUE_SECRET.matcher(sanitized).replaceAll("$1[REDACTED]");
        sanitized = PHONE_NUMBER.matcher(sanitized).replaceAll("[REDACTED]");
        return sanitized.length() <= MAX_LENGTH ? sanitized : sanitized.substring(0, MAX_LENGTH);
    }
}
