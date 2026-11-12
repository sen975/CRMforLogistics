package com.crmforlogistics.messagecenter.callrecord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallRecordExceptionTest {
    @Test
    void carriesStableHttpAndRetrySemantics() {
        IOExceptionCause cause = new IOExceptionCause();
        CallRecordException error = new CallRecordException(
                "FUNASR_TIMEOUT", 504, "FunASR timed out", true, cause);

        assertEquals("FUNASR_TIMEOUT", error.code());
        assertEquals(504, error.httpStatus());
        assertEquals("FunASR timed out", error.getMessage());
        assertTrue(error.retryable());
        assertSame(cause, error.getCause());
    }

    private static final class IOExceptionCause extends Exception {}
}
