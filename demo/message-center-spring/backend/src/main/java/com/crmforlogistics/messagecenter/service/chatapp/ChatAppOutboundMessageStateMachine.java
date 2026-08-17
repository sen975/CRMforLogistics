package com.crmforlogistics.messagecenter.service.chatapp;

import java.util.Locale;
import java.util.Map;

/** Monotonic outbound status policy shared by sends, polling and broadcast reconciliation. */
public final class ChatAppOutboundMessageStateMachine {
    private static final Map<String, Integer> SUCCESS_RANK = Map.of(
            "pending", 0,
            "processing", 1,
            "submitted", 2,
            "sent", 3,
            "delivered", 4,
            "read", 5);

    private ChatAppOutboundMessageStateMachine() {}

    public static String advance(String current, String next) {
        String currentValue = normalize(current);
        String nextValue = normalize(next);
        if (nextValue.isBlank()) return currentValue;
        if (currentValue.isBlank()) return nextValue;
        if ("failed".equals(nextValue)) {
            return rank(currentValue) >= rank("delivered") ? currentValue : "failed";
        }
        if ("failed".equals(currentValue) && SUCCESS_RANK.containsKey(nextValue)) {
            return nextValue;
        }
        return rank(nextValue) >= rank(currentValue) ? nextValue : currentValue;
    }

    private static int rank(String status) {
        return SUCCESS_RANK.getOrDefault(status, -1);
    }

    private static String normalize(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }
}
