package com.crmforlogistics.messagecenter;

/** Structured failure for the delegated-authorization boundary. */
public final class WeComAuthorizationException extends Exception {
    private final String code;
    private final int httpStatus;
    private final Integer upstreamErrcode;
    private final String upstreamPath;
    private final Integer upstreamHttpStatus;
    private final String upstreamHint;

    public WeComAuthorizationException(String code, int httpStatus, String message) {
        this(code, httpStatus, message, null, null, null);
    }

    public WeComAuthorizationException(String code, int httpStatus, String message, Throwable cause) {
        this(code, httpStatus, message, null, null, cause);
    }

    public WeComAuthorizationException(String code, int httpStatus, String message,
                                       Integer upstreamErrcode, String upstreamPath, Throwable cause) {
        this(code, httpStatus, message, upstreamErrcode, upstreamPath, null, cause);
    }

    public WeComAuthorizationException(String code, int httpStatus, String message,
                                       Integer upstreamErrcode, String upstreamPath,
                                       Integer upstreamHttpStatus, Throwable cause) {
        this(code, httpStatus, message, upstreamErrcode, upstreamPath, upstreamHttpStatus, null, cause);
    }

    public WeComAuthorizationException(String code, int httpStatus, String message,
                                       Integer upstreamErrcode, String upstreamPath,
                                       Integer upstreamHttpStatus, String upstreamHint, Throwable cause) {
        super(sanitize(message), cause);
        if (code == null || code.isBlank() || code.length() > 96) {
            throw new IllegalArgumentException("Authorization error code is required");
        }
        if (httpStatus < 400 || httpStatus > 599) {
            throw new IllegalArgumentException("Authorization HTTP status must be an error status");
        }
        this.code = code;
        this.httpStatus = httpStatus;
        if (upstreamErrcode != null && (upstreamErrcode < 0 || upstreamErrcode > 2_147_483_647)) {
            throw new IllegalArgumentException("Upstream error code is invalid");
        }
        if (upstreamPath != null && (upstreamPath.isBlank() || upstreamPath.length() > 256
                || !upstreamPath.startsWith("/"))) {
            throw new IllegalArgumentException("Upstream path is invalid");
        }
        if (upstreamHttpStatus != null && (upstreamHttpStatus < 100 || upstreamHttpStatus > 599)) {
            throw new IllegalArgumentException("Upstream HTTP status is invalid");
        }
        if (upstreamHint != null && !upstreamHint.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Upstream hint is invalid");
        }
        this.upstreamErrcode = upstreamErrcode;
        this.upstreamPath = upstreamPath;
        this.upstreamHttpStatus = upstreamHttpStatus;
        this.upstreamHint = upstreamHint;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public Integer upstreamErrcode() {
        return upstreamErrcode;
    }

    public String upstreamPath() {
        return upstreamPath;
    }

    public Integer upstreamHttpStatus() {
        return upstreamHttpStatus;
    }

    public String upstreamHint() {
        return upstreamHint;
    }

    private static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "企业微信授权失败";
        }
        return message.length() > 512 ? message.substring(0, 512) : message;
    }
}
