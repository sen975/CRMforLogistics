package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WeComUserNotificationMigrationContractTest {

    @Test
    void v67DefinesPendingNotificationTableWithAggregationUniqueness() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V67__wecom_user_notifications.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();

            assertThat(sql).contains("create table wecom_user_notifications");
            assertThat(sql).contains("agent_id");
            assertThat(sql).contains("send_after");
            assertThat(sql).contains("message_count integer not null default 1");
            assertThat(sql).contains("on wecom_user_notifications (conversation_id, recipient_user_id)");
            assertThat(sql).contains("where status = 'pending'");
            assertThat(sql).contains(
                    "check (status in ('pending', 'sending', 'sent', 'failed'))");
        }
    }
}
