package com.crmforlogistics.messagecenter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeComAuditRetentionMigrationTest {

    private final String sql = resource("db/migration/V21__wecom_audit_retention.sql")
            .toLowerCase();

    @Test
    void addsStableRetentionIndexesForBothWeComAuditStreams() {
        assertThat(sql).contains("on wecom_viewer_audit (occurred_at, id)");
        assertThat(sql).contains("on wecom_authorization_audit (occurred_at, id)");
    }

    @Test
    void definesBoundedPerStreamRetentionState() {
        assertThat(sql).contains("create table wecom_audit_retention_state");
        assertThat(sql).contains("stream varchar(32) primary key");
        assertThat(sql).contains("stream in ('viewer', 'authorization')");
        assertThat(sql).contains("status in ('running', 'success', 'skipped_locked', 'budget_remaining', 'failed')");
        assertThat(sql).contains("deleted_count >= 0");
    }

    private static String resource(String name) {
        try (var input = WeComAuditRetentionMigrationTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            assertThat(input).as(name).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
