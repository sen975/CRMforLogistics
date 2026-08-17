package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import org.springframework.http.HttpStatus;

import java.util.Objects;

public class ChatAppBroadcastException extends RuntimeException {
    private final HttpStatus status;
    private final boolean resultUnknown;
    private final boolean retryable;
    private final String providerCode;
    private final String providerRequestId;
    private final String safeMessage;

    public ChatAppBroadcastException(String code, HttpStatus status) {
        this(code, status, false, false, "", "", code, null);
    }

    public ChatAppBroadcastException(
            String code, HttpStatus status, boolean resultUnknown, boolean retryable, Throwable cause) {
        this(code, status, resultUnknown, retryable, "", "", code, cause);
    }

    public ChatAppBroadcastException(
            String code, HttpStatus status, boolean resultUnknown, boolean retryable,
            String providerCode, String providerRequestId, String safeMessage, Throwable cause) {
        super(code, cause);
        this.status = Objects.requireNonNull(status);
        this.resultUnknown = resultUnknown;
        this.retryable = retryable;
        this.providerCode = bounded(providerCode, 100);
        this.providerRequestId = bounded(providerRequestId, 255);
        this.safeMessage = bounded(safeMessage, 1000);
    }

    public HttpStatus status() {
        return status;
    }

    public boolean resultUnknown() {
        return resultUnknown;
    }

    public boolean retryable() {
        return retryable;
    }

    public String providerCode() {
        return providerCode;
    }

    public String providerRequestId() {
        return providerRequestId;
    }

    public String safeMessage() {
        return safeMessage;
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) return "";
        String safe = value.replace('\r', ' ').replace('\n', ' ').trim();
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }
}
