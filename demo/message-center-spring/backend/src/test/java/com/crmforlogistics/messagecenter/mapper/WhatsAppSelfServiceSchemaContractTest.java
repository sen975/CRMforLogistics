package com.crmforlogistics.messagecenter.mapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppSelfServiceSchemaContractTest {

    @Test
    void migrationSeparatesEnterpriseAndEmployeeBusinessAppScopes() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V59__whatsapp_self_service_scope_and_attempts.sql"));
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("add column if not exists scope_type")
                .contains("add column if not exists owner_user_id")
                .contains("enterprise_api")
                .contains("employee_business_app")
                .contains("ux_whatsapp_provider_scope_provider_waba")
                .contains("ck_whatsapp_provider_scope_owner");
    }

    @Test
    void migrationDefinesTheRecoverableAuthorizationAttemptStateMachine() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V59__whatsapp_self_service_scope_and_attempts.sql"));
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("add column if not exists account_name")
                .contains("add column if not exists account_remark")
                .contains("add column if not exists completed_phone_number_id")
                .contains("add column if not exists completed_account_id")
                .contains("add column if not exists failure_stage")
                .contains("meta_completed")
                .contains("provider_synced")
                .contains("completed")
                .contains("cancelled")
                .contains("expired")
                .contains("failed");
    }

    @Test
    void sourceContractsExposeScopePurposeAndLockedAttemptTransitions() throws Exception {
        String scope = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppProviderScopeEntity.java"));
        String scopeMapper = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppProviderScopeMapper.java"));
        String attemptMapper = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppAuthorizationAttemptMapper.java"));

        assertThat(scope).contains("private String scopeType;")
                .contains("private UUID ownerUserId;");
        assertThat(scopeMapper).contains("findEnterpriseApiScope")
                .contains("findByProviderAndWabaId");
        assertThat(attemptMapper).contains("findByIdForUpdate")
                .contains("advanceMetaCompleted")
                .contains("markProviderSynced")
                .contains("completed_account_id");
    }

    @Test
    void phoneSelectionCandidatesAreAttemptBoundAndExpire() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V60__whatsapp_authorization_phone_candidates.sql"));
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("create table whatsapp_authorization_phone_candidates")
                .contains("attempt_id uuid not null")
                .contains("token_hash varchar(128) not null")
                .contains("phone_number varchar(32) not null")
                .contains("expires_at timestamptz not null")
                .contains("unique (attempt_id, token_hash)");
    }

    @Test
    void channelAccountsPersistUserFacingRemark() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V61__whatsapp_channel_account_remark.sql"));
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");
        assertThat(normalized).contains("alter table channel_accounts")
                .contains("add column if not exists remark")
                .contains("varchar(500)");

        String entity = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/entity/ChannelAccountEntity.java"));
        String mapper = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java"));
        assertThat(entity).contains("private String remark;");
        assertThat(mapper).contains("remark").contains("#{entity.remark}");
    }
}
