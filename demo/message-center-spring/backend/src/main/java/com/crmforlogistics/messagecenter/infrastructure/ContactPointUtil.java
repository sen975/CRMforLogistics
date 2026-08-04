package com.crmforlogistics.messagecenter.infrastructure;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ContactPointUtil {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);

    private ContactPointUtil() {}

    public static String extractEmail(String value) {
        if (value == null) return "";
        Matcher matcher = EMAIL_PATTERN.matcher(value);
        return matcher.find() ? matcher.group().toLowerCase(Locale.ROOT) : value.trim().toLowerCase(Locale.ROOT);
    }

    public static String extractName(String value, String email) {
        if (value == null || value.isBlank() || email == null || email.isBlank()) return "";
        String result = value.replace("<" + email + ">", "").replace(email, "").trim();
        if (result.startsWith("\"") && result.endsWith("\"") && result.length() > 1) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result.isBlank() ? "" : result;
    }
}
