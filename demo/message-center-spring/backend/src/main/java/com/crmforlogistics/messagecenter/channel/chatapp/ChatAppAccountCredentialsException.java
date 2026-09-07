package com.crmforlogistics.messagecenter.channel.chatapp;

import java.util.Objects;

/** Signals an account-scoped CAMS credential problem before any provider request is sent. */
public final class ChatAppAccountCredentialsException extends RuntimeException {
    private final String code;

    public ChatAppAccountCredentialsException(String code) {
        super(Objects.requireNonNull(code));
        this.code = code;
    }

    public ChatAppAccountCredentialsException(String code, Throwable cause) {
        super(Objects.requireNonNull(code), cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
