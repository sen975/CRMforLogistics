package com.crmforlogistics.messagecenter;

public final class WeComDailySummaryStartupException extends Exception {
    private final String code;
    private final int httpStatus;

    public WeComDailySummaryStartupException(String code, int httpStatus,
                                             String safeMessage, Throwable cause) {
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
