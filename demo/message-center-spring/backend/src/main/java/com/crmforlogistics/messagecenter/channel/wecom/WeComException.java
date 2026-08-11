package com.crmforlogistics.messagecenter.channel.wecom;

public class WeComException extends RuntimeException {
    private final String code;
    private final int httpStatus;
    private final Integer upstreamErrcode;
    private final String upstreamPath;
    private final Integer upstreamHttpStatus;
    private final String upstreamHint;

    public WeComException(String code, int httpStatus, String message) {
        this(code, httpStatus, message, null, null, null, null, null);
    }

    public WeComException(String code, int httpStatus, String message, Throwable cause) {
        this(code, httpStatus, message, null, null, null, null, cause);
    }

    public WeComException(String code, int httpStatus, String message,
                          Integer upstreamErrcode, String upstreamPath,
                          Integer upstreamHttpStatus, String upstreamHint) {
        this(code, httpStatus, message, upstreamErrcode, upstreamPath,
                upstreamHttpStatus, upstreamHint, null);
    }

    public WeComException(String code, int httpStatus, String message,
                          Integer upstreamErrcode, String upstreamPath,
                          Integer upstreamHttpStatus, String upstreamHint,
                          Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.upstreamErrcode = upstreamErrcode;
        this.upstreamPath = upstreamPath;
        this.upstreamHttpStatus = upstreamHttpStatus;
        this.upstreamHint = upstreamHint;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }
    public Integer upstreamErrcode() { return upstreamErrcode; }
    public String upstreamPath() { return upstreamPath; }
    public Integer upstreamHttpStatus() { return upstreamHttpStatus; }
    public String upstreamHint() { return upstreamHint; }
}
