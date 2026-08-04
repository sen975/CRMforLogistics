package com.crmforlogistics.messagecenter;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;

public final class MessageTime {
    private MessageTime() {}

    static String timestampString(String value) {
        Instant instant = parseInstant(value);
        return instant.equals(Instant.EPOCH) ? ContactPointUtil.firstNonBlank(value, Instant.EPOCH.toString()) : instant.toString();
    }

    public static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return Instant.EPOCH;
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (Exception ignored) {
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH);
            Date parsed = format.parse(trimmed);
            return parsed == null ? Instant.EPOCH : parsed.toInstant();
        } catch (Exception ignored) {
            return Instant.EPOCH;
        }
    }
}
