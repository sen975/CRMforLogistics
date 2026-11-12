package com.crmforlogistics.messagecenter.mapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppMultiNumberSchemaContractTest {

    @Test
    void migrationAddsEnterpriseWabaIdentityAndPerNumberMetadata() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V52__whatsapp_scope_waba_and_phone_metadata.sql"));
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("alter table whatsapp_provider_scopes")
                .contains("add column waba_id")
                .contains("add column identity_status")
                .contains("add column encrypted_config")
                .contains("ck_whatsapp_provider_scope_waba")
                .contains("waba_id is null or btrim(waba_id) <> ''")
                .contains("ck_whatsapp_provider_scope_identity_status")
                .contains("identity_pending")
                .contains("identity_verified")
                .contains("create unique index ux_whatsapp_provider_scope_waba")
                .contains("alter table channel_accounts")
                .contains("add column onboarding_mode")
                .contains("add column phone_verification_status")
                .contains("add column provider_phone_status")
                .contains("ck_channel_accounts_onboarding_mode")
                .contains("ck_channel_accounts_phone_verification_status")
                .contains("ck_channel_accounts_provider_phone_status")
                .contains("business_app_coexistence")
                .contains("api_only");
    }

    @Test
    void reconciliationMigrationIsSafeForDatabasesThatAlreadyAppliedOlderVersions() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V55__whatsapp_runtime_schema_reconciliation.sql"));
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized).contains("add column if not exists encrypted_config")
                .contains("create table if not exists whatsapp_phone_onboarding_operations")
                .contains("uq_whatsapp_phone_onboarding_operation")
                .contains("ck_whatsapp_phone_onboarding_status");
    }

    @Test
    void existingIndexesKeepOneActiveWhatsappAccountPerOwnerAndOneRecordPerPhone() throws Exception {
        String base = Files.readString(Path.of(
                "src/main/resources/db/migration/V2__channel_conversation_message.sql")).toLowerCase();
        String owner = Files.readString(Path.of(
                "src/main/resources/db/migration/V47__user_channel_address_book_owner.sql")).toLowerCase();

        assertThat(base).contains("ux_channel_accounts_identifier")
                .contains("channel_type, account_identifier_normalized");
        assertThat(owner).contains("ux_channel_accounts_owner_unique_active")
                .contains("owner_user_id, channel_type");
    }

    @Test
    void entitySourcesExposeTheNewFields() throws Exception {
        String scope = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppProviderScopeEntity.java"));
        String account = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/entity/ChannelAccountEntity.java"));

        assertThat(scope).contains("private String wabaId;")
                .contains("private String identityStatus;");
        assertThat(account).contains("private String onboardingMode;")
                .contains("private String phoneVerificationStatus;")
                .contains("private String providerPhoneStatus;");
    }

    @Test
    void mapperSourcesExposeEnterpriseScopePhoneLookupAndAtomicReassignment() throws Exception {
        String scopeMapper = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppProviderScopeMapper.java"));
        String accountMapper = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java"));

        assertThat(scopeMapper).contains("findEnterpriseScope")
                .contains("waba_id")
                .contains("identity_status");
        assertThat(accountMapper).contains("findActiveByScope")
                .contains("findActiveByNormalizedIdentifier")
                .contains("reassignActiveWhatsAppAccount")
                .contains("not exists")
                .contains("onboarding_mode")
                .contains("phone_verification_status")
                .contains("provider_phone_status");
    }

    @Test
    void ownedAccountInsertPersistsWhatsappScopeAndPhoneMetadata() throws Exception {
        String accountMapper = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java"));

        assertThat(accountMapper).contains("#{entity.onboardingMode}")
                .contains("#{entity.phoneVerificationStatus}")
                .contains("#{entity.providerPhoneStatus}")
                .contains("#{entity.providerScopeId}");
    }
}
