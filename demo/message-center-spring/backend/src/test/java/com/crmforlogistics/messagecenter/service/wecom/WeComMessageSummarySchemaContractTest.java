package com.crmforlogistics.messagecenter.service.wecom;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComMessageSummarySchemaContractTest {
    @Test
    void migrationDefinesIdempotencyStatusAndDiagnosticColumns() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V30__wecom_message_summary_jobs.sql"));
        assertTrue(sql.contains("wecom_message_summary_jobs"));
        assertTrue(sql.contains("UNIQUE (installation_id, msgid)"));
        assertTrue(sql.contains("validation_stage"));
        assertTrue(sql.contains("raw_request_json"));
        assertTrue(sql.contains("raw_response_json"));
        String compact = sql.replaceAll("\\s+", "");
        assertTrue(compact.contains("statusIN('PENDING','SUBMITTED','RETRY_WAIT','COMPLETED','FAILED')"));
    }
}
