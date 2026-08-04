package com.crmforlogistics.messagecenter;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ContactPointUtil {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);

    private ContactPointUtil() {}

    public static String normalizePointId(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isBlank()) return "";
        if (!trimmed.contains(":")) return "email:" + extractEmail(trimmed).toLowerCase(Locale.ROOT);
        String[] parts = trimmed.split(":", 3);
        if (parts.length == 2 && "email".equalsIgnoreCase(parts[0])) {
            return "email:" + extractEmail(parts[1]).toLowerCase(Locale.ROOT);
        }
        if (parts.length == 2 && "phone".equalsIgnoreCase(parts[0])) {
            String phone = normalizePhonePoint(parts[1]);
            return phone.isBlank() ? "" : "phone:" + phone;
        }
        if (parts.length == 3 && "chatapp".equalsIgnoreCase(parts[0])) {
            return "chatapp:" + parts[1].trim().toLowerCase(Locale.ROOT) + ":" + normalizePhone(parts[2]);
        }
        if (parts.length >= 2 && "wecom".equalsIgnoreCase(parts[0])) {
            return "wecom:" + trimmed.substring(trimmed.indexOf(':') + 1).trim().toLowerCase(Locale.ROOT);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    static ContactPoint fromId(String pointId, UnifiedMessage message) {
        String normalized = normalizePointId(pointId);
        if (normalized.startsWith("email:")) {
            String value = normalized.substring("email:".length());
            String label = message == null ? value : firstNonBlank(extractName(message.from, value), extractName(message.to, value), value);
            return new ContactPoint(normalized, "email", "email", value, label);
        }
        if (normalized.startsWith("chatapp:whatsapp:")) {
            String value = normalized.substring("chatapp:whatsapp:".length());
            return new ContactPoint(normalized, "chatapp", "whatsapp", value, value);
        }
        if (normalized.startsWith("wecom:")) {
            String value = normalized.substring("wecom:".length());
            return new ContactPoint(normalized, "wecom", "wecom", value, value);
        }
        if (normalized.startsWith("phone:")) {
            String value = normalized.substring("phone:".length());
            return new ContactPoint(normalized, "phone", "phone", value, value);
        }
        return new ContactPoint(normalized, "unknown", "unknown", normalized, normalized);
    }

    static String extractEmail(String value) {
        if (value == null) return "";
        Matcher matcher = EMAIL_PATTERN.matcher(value);
        return matcher.find() ? matcher.group().toLowerCase(Locale.ROOT) : value.trim().toLowerCase(Locale.ROOT);
    }

    static String extractName(String value, String email) {
        if (value == null || value.isBlank() || email == null || email.isBlank()) return "";
        String result = value.replace("<" + email + ">", "").replace(email, "").trim();
        if (result.startsWith("\"") && result.endsWith("\"") && result.length() > 1) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result.isBlank() ? "" : result;
    }

    static String normalizePhone(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    private static String normalizePhonePoint(String value) {
        if (value == null || !value.matches("[0-9\\s+()\\-]*")) {
            return "";
        }
        String digits = value.replaceAll("[^0-9]", "");
        return digits.matches("[0-9]{6,20}") ? digits : "";
    }

    static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }
}
