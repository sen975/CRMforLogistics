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

    @Test
    void leaseQueryPrioritizesFreshPendingJobsOverRetryWaitJobs() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/WeComMessageSummaryJobMapper.java"));
        String compact = source.replaceAll("\\s+", "");
        int pending = compact.indexOf("CASEstatusWHEN'PENDING'THEN0");
        int submitted = compact.indexOf("WHEN'SUBMITTED'THEN1", pending);
        int retryWait = compact.indexOf("WHEN'RETRY_WAIT'THEN2ELSE3END", submitted);
        assertTrue(pending >= 0);
        assertTrue(submitted > pending);
        assertTrue(retryWait > submitted);
    }

    @Test
    void completionQueryAcceptsRetryWaitJobsThatHaveAnOfficialJobId() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/WeComMessageSummaryJobMapper.java"));
        String compact = source.replaceAll("\\s+", "");
        assertTrue(compact.contains("statusIN('SUBMITTED','RETRY_WAIT')"));
        assertTrue(compact.contains("wecom_job_idISNOTNULL"));
    }

    @Test
    void diagnosticMigrationPreservesAQueryableFailureReason() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V39__wecom_message_summary_error_diagnostic.sql"));
        assertTrue(sql.contains("wecom_message_summary_jobs"));
        assertTrue(sql.contains("last_error_diagnostic"));
    }

    @Test
    void retryAndFailureQueriesRetainExistingOfficialResponseWhenNoNewResponseExists() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/WeComMessageSummaryJobMapper.java"));
        assertTrue(source.contains("raw_response_json=CASE WHEN coalesce(#{rawResponseJson}, '') = ''"));
        assertTrue(source.contains("THEN raw_response_json ELSE #{rawResponseJson} END"));
        assertTrue(source.contains("last_error_diagnostic=#{errorDiagnostic}"));
    }
}
