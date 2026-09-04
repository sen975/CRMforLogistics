package com.crmforlogistics.messagecenter.service.channel;

import org.springframework.http.HttpStatus;

public class ChannelAccountException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public ChannelAccountException(String code, HttpStatus status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
}
