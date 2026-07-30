package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeComDailySummaryRuntimeTest {
    @Test
    void disabledSummaryDoesNotRequireDatabaseOrAuthorizationServices() throws Exception {
        Config config = new Config(Map.of(
                "WECOM_DAILY_SUMMARY_ENABLED", "false",
                "DATABASE_PASSWORD_FILE", "/definitely/missing/database-password"));

        try (WeComDailySummaryRuntime runtime = WeComDailySummaryRuntime.open(config, null, null)) {
            assertFalse(runtime.enabled());
        }
    }

    @Test
    void enabledSummaryFailsWithStructuredStartupErrorWhenDependenciesAreUnavailable() {
        Config config = new Config(Map.of(
                "WECOM_DAILY_SUMMARY_ENABLED", "true",
                "WECOM_CHATDATA_PROGRAM_ID", "program-1",
                "WECOM_SUITE_ID", "dk-suite",
                "WECOM_LOGIN_AUTH_CORP_ID", "ww-corp",
                "DATABASE_PASSWORD_FILE", "/definitely/missing/database-password"));

        WeComDailySummaryStartupException exception = assertThrows(
                WeComDailySummaryStartupException.class,
                () -> WeComDailySummaryRuntime.open(config, null, null));

        assertEquals("WECOM_DAILY_SUMMARY_STARTUP_FAILED", exception.code());
        assertEquals(503, exception.httpStatus());
    }
}
