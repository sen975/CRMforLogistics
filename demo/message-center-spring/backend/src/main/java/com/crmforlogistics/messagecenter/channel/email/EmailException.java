package com.crmforlogistics.messagecenter.channel.email;

public class EmailException extends RuntimeException {
    private final String code;

    public EmailException(String code, String message) {
        super(message);
        this.code = code;
    }

    public EmailException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
