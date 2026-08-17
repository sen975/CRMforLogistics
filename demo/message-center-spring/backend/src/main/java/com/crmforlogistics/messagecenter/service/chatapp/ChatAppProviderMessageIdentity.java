package com.crmforlogistics.messagecenter.service.chatapp;

public final class ChatAppProviderMessageIdentity {
    private ChatAppProviderMessageIdentity() {
    }

    public static String canonical(String messageId, String uniqueMessageId) {
        String unique = value(uniqueMessageId);
        return unique.isBlank() ? value(messageId) : unique;
    }

    public static String canonicalForBroadcast(
            String messageId, String uniqueMessageId, String groupMessageId) {
        String unique = value(uniqueMessageId);
        if (!unique.isBlank()) return unique;
        String message = value(messageId);
        return message.equals(value(groupMessageId)) ? "" : message;
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
