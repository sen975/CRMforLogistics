package com.crmforlogistics.messagecenter.service.account;

import org.springframework.http.HttpStatus;

public class AccountException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public AccountException(String code, HttpStatus status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public AccountException(String code, HttpStatus status, Throwable cause) {
        super(code, cause);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
