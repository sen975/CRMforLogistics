package com.crmforlogistics.messagecenter.service.contact;

import org.springframework.http.HttpStatus;

public class ChannelAddressBookException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public ChannelAddressBookException(String code, HttpStatus status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
}
