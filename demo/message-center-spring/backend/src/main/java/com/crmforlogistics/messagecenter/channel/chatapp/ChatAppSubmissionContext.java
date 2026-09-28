package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentials;

import java.util.Objects;
import java.util.UUID;

/** Immutable account and credential snapshot captured before provider I/O. */
public record ChatAppSubmissionContext(
        UUID channelAccountId,
        String accountIdentifier,
        Long accountVersion,
        ChatAppAccountCredentials credentials) {
    public ChatAppSubmissionContext {
        Objects.requireNonNull(channelAccountId, "channelAccountId");
        if (accountIdentifier == null || accountIdentifier.isBlank()) {
            throw new IllegalArgumentException("CHATAPP_CHANNEL_ACCOUNT_IDENTIFIER_REQUIRED");
        }
        accountIdentifier = accountIdentifier.trim();
    }
}
