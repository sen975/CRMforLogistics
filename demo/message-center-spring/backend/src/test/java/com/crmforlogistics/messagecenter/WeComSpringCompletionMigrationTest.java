package com.crmforlogistics.messagecenter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeComSpringCompletionMigrationTest {

    private final String sql = resource("db/migration/V16__wecom_spring_completion.sql").toLowerCase();
    private final String chatdataSql = resource("db/migration/V12__wecom_chatdata.sql").toLowerCase();
    private final String authorizationAuditSql = resource("db/migration/V13__wecom_audit.sql").toLowerCase();

    @Test
    void definesOneToOneWeComBinding() {
        assertThat(sql).contains("create table wecom_user_bindings");
        assertThat(sql).contains("unique (user_id)");
        assertThat(sql).contains("unique (suite_id, auth_corp_id, wecom_user_id)");
        assertThat(sql).contains("provisioning_source");
        assertThat(sql).contains("auto_created").contains("bound_existing");
        assertThat(sql).contains("alter column permanent_code type text");
        assertThat(sql).contains("alter column secret_key type text");
    }

    @Test
    void extendsAuthorizationStateForReplayAndPendingAudit() {
        assertThat(sql).contains("last_authorization_event_id");
        assertThat(sql).contains("last_authorization_event_at");
        assertThat(sql).contains("event_id");
        assertThat(sql).contains("attempt");
        assertThat(sql).contains("target_status");
        assertThat(sql).contains("expected_version");
        assertThat(sql).contains("'pending'");
        assertThat(sql).contains("event_id is not null and attempt is not null and attempt >= 1");
    }

    @Test
    void includesCommittedPredecessorMigrationsRequiredByV16() {
        assertThat(chatdataSql).contains("create table wecom_chatdata_messages");
        assertThat(authorizationAuditSql).contains("create table wecom_authorization_audit");
    }

    private static String resource(String name) {
        try (var in = WeComSpringCompletionMigrationTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
