package com.crmforlogistics.messagecenter.channel.wecom;

public class WeComChatDataException extends RuntimeException {
    private final String code;
    private final int httpStatus;
    private final Integer upstreamErrcode;
    private final String upstreamPath;
    private final Integer upstreamHttpStatus;
    private final String upstreamHint;

    public WeComChatDataException(String code, int httpStatus, String safeMessage) {
        this(code, httpStatus, safeMessage, null, null, null, null, null);
    }

    public WeComChatDataException(String code, int httpStatus, String safeMessage, Throwable cause) {
        this(code, httpStatus, safeMessage, null, null, null, null, cause);
    }

    public WeComChatDataException(String code, int httpStatus, String safeMessage,
                                  Integer upstreamErrcode, Throwable cause) {
        this(code, httpStatus, safeMessage, upstreamErrcode, null, null, null, cause);
    }

    public WeComChatDataException(String code, int httpStatus, String safeMessage,
                                  Integer upstreamErrcode, String upstreamPath,
                                  Integer upstreamHttpStatus, Throwable cause) {
        this(code, httpStatus, safeMessage, upstreamErrcode, upstreamPath,
                upstreamHttpStatus, null, cause);
    }

    public WeComChatDataException(String code, int httpStatus, String safeMessage,
                                  Integer upstreamErrcode, String upstreamPath,
                                  Integer upstreamHttpStatus, String upstreamHint, Throwable cause) {
        super(safeMessage, cause);
        if (upstreamHint != null && !upstreamHint.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Upstream hint is invalid");
        }
        this.code = code;
        this.httpStatus = httpStatus;
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
}
