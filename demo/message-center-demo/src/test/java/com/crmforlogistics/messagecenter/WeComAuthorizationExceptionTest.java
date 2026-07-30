package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeComAuthorizationExceptionTest {
    @Test
    void exposesStructuredCodeAndStatusWithoutCauseDetails() {
        WeComAuthorizationException exception = new WeComAuthorizationException(
                "WECOM_INSTALLATION_NOT_FOUND", 403, "未找到授权安装记录");

        assertEquals("WECOM_INSTALLATION_NOT_FOUND", exception.code());
        assertEquals(403, exception.httpStatus());
        assertEquals("未找到授权安装记录", exception.getMessage());
        assertFalse(exception.getMessage().contains("secret"));
    }

    @Test
    void rejectsNonErrorStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> new WeComAuthorizationException("BAD", 200, "bad"));
    }
}
