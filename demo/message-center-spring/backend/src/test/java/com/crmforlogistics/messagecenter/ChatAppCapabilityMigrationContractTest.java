package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAppCapabilityMigrationContractTest {

    @Test
    void capabilityEvidenceSchemaCannotStoreSecretsOrVerificationCodes() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V51__chatapp_capability_probe.sql"));
        String normalized = sql.toLowerCase();

        assertThat(normalized)
                .contains("create table chatapp_capability_results")
                .contains("provider_request_id")
                .contains("tested_phone_last4")
                .doesNotContain("access_key")
                .doesNotContain("verify_code")
                .doesNotContain("verification_code")
                .doesNotContain("token varchar");
    }
}
