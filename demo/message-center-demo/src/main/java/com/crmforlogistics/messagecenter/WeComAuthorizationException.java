package com.crmforlogistics.messagecenter;

/** Structured failure for the delegated-authorization boundary. */
public final class WeComAuthorizationException extends Exception {
    private final String code;
    private final int httpStatus;

    public WeComAuthorizationException(String code, int httpStatus, String message) {
        this(code, httpStatus, message, null);
    }

    public WeComAuthorizationException(String code, int httpStatus, String message, Throwable cause) {
        super(sanitize(message), cause);
        if (code == null || code.isBlank() || code.length() > 96) {
            throw new IllegalArgumentException("Authorization error code is required");
        }
        if (httpStatus < 400 || httpStatus > 599) {
            throw new IllegalArgumentException("Authorization HTTP status must be an error status");
        }
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    private static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "企业微信授权失败";
        }
        return message.length() > 512 ? message.substring(0, 512) : message;
    }
}
