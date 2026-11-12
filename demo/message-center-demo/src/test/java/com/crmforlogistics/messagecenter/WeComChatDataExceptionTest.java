package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WeComChatDataExceptionTest {
    @Test
    void exposesOnlyStructuredSafeErrorContract() {
        WeComChatDataException exception = new WeComChatDataException(
                "WECOM_CHATDATA_PROGRAM_ERROR", 502, "企业微信专区程序调用失败",
                new IllegalStateException("access-token-secret-ciphertext"));

        assertEquals("WECOM_CHATDATA_PROGRAM_ERROR", exception.code());
        assertEquals(502, exception.httpStatus());
        assertEquals("企业微信专区程序调用失败", exception.getMessage());
        assertFalse(exception.getMessage().contains("secret"));
    }
}
