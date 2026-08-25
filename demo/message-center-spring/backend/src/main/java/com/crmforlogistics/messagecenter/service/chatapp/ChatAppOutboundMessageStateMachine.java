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
            "failed", 4,
            "delivered", 5,
            "read", 6);

    private ChatAppOutboundMessageStateMachine() {}

    public static String advance(String current, String next) {
        String currentValue = normalize(current);
        String nextValue = normalize(next);
        if (nextValue.isBlank()) return currentValue;
        if (currentValue.isBlank()) return nextValue;
        if ("read".equals(currentValue)) return currentValue;
        return rank(nextValue) >= rank(currentValue) ? nextValue : currentValue;
    }

    private static int rank(String status) {
        return SUCCESS_RANK.getOrDefault(status, -1);
    }

    private static String normalize(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }
}
