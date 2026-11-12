package com.crmforlogistics.messagecenter;

import java.io.IOException;

final class AuditStorageException extends IOException {
    private final String code;
    private final String stream;

    AuditStorageException(String code, String stream, Throwable cause) {
        super(code, cause);
        this.code = code;
        this.stream = stream;
    }

    String code() {
        return code;
    }

    String stream() {
        return stream;
    }
}
