package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WeComApiAuditMigrationTest {
    private final String sql = resource("db/migration/V22__wecom_api_audit.sql").toLowerCase();

    @Test
    void createsBoundedMetadataOnlyApiAuditStream() {
        assertThat(sql).contains("create table wecom_api_audit");
        assertThat(sql).contains("installation_id uuid").contains("actor_user_id uuid");
        assertThat(sql).contains("upstream_path").contains("trace_id").contains("occurred_at");
        assertThat(sql).contains("operation_id uuid");
        assertThat(sql).contains("result in ('accepted', 'success', 'failed', 'denied')");
        assertThat(sql).contains("on wecom_api_audit (occurred_at, id)");
        assertThat(sql).doesNotContain("request_body").doesNotContain("response_body")
                .doesNotContain("access_token").doesNotContain("permanent_code");
    }

    @Test
    void addsApiToExistingRetentionStateConstraint() {
        assertThat(sql).contains("stream in ('viewer', 'authorization', 'api')");
    }

    private static String resource(String name) {
        try (var input = WeComApiAuditMigrationTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            assertThat(input).as(name).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
