package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAppBroadcastMigrationContractTest {

    @Test
    void v15DefinesBoundedBroadcastPersistenceAndPermissionRole() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V15__chatapp_broadcasts.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();

            assertThat(sql).contains("create table chatapp_broadcasts");
            assertThat(sql).contains("create table chatapp_broadcast_recipients");
            assertThat(sql).contains("create table chatapp_broadcast_jobs");
            assertThat(sql).contains("unique (channel_account_id, client_request_id)");
            assertThat(sql).contains("unique (broadcast_id, contact_identity_id)");
            assertThat(sql).contains("recipient_count between 1 and 1000");
            assertThat(sql).contains("broadcast_sender");
            assertThat(sql).contains("submission_unknown");
            assertThat(sql).contains("status_unknown");
        }
    }
}
