package com.crmforlogistics.messagecenter.mapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppOnboardingModeMigrationContractTest {

    @Test
    void migrationReplacesLegacyOnboardingModeChecksWithCanonicalValues() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V64__whatsapp_canonical_onboarding_modes.sql"));
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("update whatsapp_authorization_attempts")
                .contains("business_app_coexistence")
                .contains("employee_business_app")
                .contains("update channel_accounts")
                .contains("api_only")
                .contains("admin_api_waba")
                .contains("drop constraint if exists ck_whatsapp_authorization_attempt_mode")
                .contains("drop constraint if exists ck_channel_accounts_onboarding_mode")
                .contains("check (onboarding_mode in ('employee_business_app', 'admin_api_waba'))")
                .contains("check (onboarding_mode is null or onboarding_mode in");
    }

    @Test
    void followUpMigrationAlignsAuthorizationAttemptDefaultWithCanonicalValue() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V65__whatsapp_canonical_onboarding_mode_default.sql"));
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("alter table whatsapp_authorization_attempts")
                .contains("alter column onboarding_mode set default 'employee_business_app'");
    }
}
