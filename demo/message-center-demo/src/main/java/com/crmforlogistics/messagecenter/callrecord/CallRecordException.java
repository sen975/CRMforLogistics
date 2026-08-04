package com.crmforlogistics.messagecenter.callrecord;

public final class CallRecordException extends Exception {
    private final String code;
    private final int httpStatus;
    private final boolean retryable;

    public CallRecordException(String code, int httpStatus, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public CallRecordException(String code, int httpStatus, String message,
                               boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }
}
