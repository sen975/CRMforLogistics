package com.crmforlogistics.messagecenter.service.whatsapp.template;

import java.util.regex.Pattern;

final class TemplatePermissionErrorSanitizer {
    private static final int MAX_LENGTH = 500;
    private static final String FALLBACK = "Template permission synchronization failed";
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

    private TemplatePermissionErrorSanitizer() {
    }

    static String sanitize(String value) {
        if (value == null || value.isBlank()) return FALLBACK;
        String sanitized = SIGNED_URL.matcher(value).replaceAll("$1?[REDACTED]");
        sanitized = AUTHORIZATION.matcher(sanitized).replaceAll("$1[REDACTED]");
        sanitized = JSON_SECRET.matcher(sanitized).replaceAll("$1[REDACTED]$2");
        sanitized = KEY_VALUE_SECRET.matcher(sanitized).replaceAll("$1[REDACTED]");
        return sanitized.length() <= MAX_LENGTH ? sanitized : sanitized.substring(0, MAX_LENGTH);
    }
}
