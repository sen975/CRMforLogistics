package com.crmforlogistics.messagecenter.service.chatapp;

import java.util.Locale;

public final class ChatAppMessageStatusNormalizer {
    private ChatAppMessageStatusNormalizer() {
    }

    public static String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()
                || normalized.equals("unread")
                || normalized.equals("not read")
                || normalized.equals("not_read")
                || normalized.equals("un-read")) {
            return "";
        }
        if (normalized.contains("deliver")) return "delivered";
        if (normalized.contains("read")) return "read";
        if (normalized.contains("sent")) return "sent";
        if (normalized.contains("submit") || normalized.contains("accept")) return "submitted";
        // CAMS ListChatappMessage uses the display value "Success" for a
        // provider-accepted outbound message. It does not prove delivery.
        if (normalized.equals("success") || normalized.equals("successful")
                || normalized.equals("succeeded") || normalized.equals("ok")) {
            return "submitted";
        }
        if (normalized.contains("fail") || normalized.contains("reject")) return "failed";
        return "";
    }
}
