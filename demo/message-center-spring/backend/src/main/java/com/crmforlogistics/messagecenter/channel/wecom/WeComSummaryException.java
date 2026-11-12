package com.crmforlogistics.messagecenter.channel.wecom;

public class WeComSummaryException extends RuntimeException {
    private final String code;
    private final int httpStatus;

    public WeComSummaryException(String code, int httpStatus, String safeMessage) {
        super(safeMessage);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public WeComSummaryException(String code, int httpStatus, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
