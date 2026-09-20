package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecentertest.mapper.AdminWhatsAppWorkbenchMapperTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin sync is the only path that can bring a locally disabled WhatsApp number back into
 * service. A number disabled by the channel-settings unbind keeps its row, so CAMS still reports it
 * as ACTIVE+VERIFIED and the sync must re-activate that same row rather than leave it unusable
 * behind a green provider status.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AdminWhatsAppWorkbenchMapperTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ChannelAccountMapperAdminSyncIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("mybatis-plus.type-handlers-package",
                () -> "com.crmforlogistics.messagecenter.typehandler");
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Autowired ChannelAccountMapper channelAccountMapper;
    @Autowired JdbcTemplate jdbc;

    private UUID userId;
    private UUID scopeId;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table channel_accounts, whatsapp_provider_scopes, users cascade");
        userId = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', 'sales')", userId, "sales" + userId, "sales" + userId);
        scopeId = insertScope("cust-space-sync");
    }

    @Test
    void refreshAdminSyncedReactivatesADisabledAccountWithoutTouchingItsOwner() {
        UUID accountId = insertAccount("8613800000001", userId);

        int updated = channelAccountMapper.refreshAdminSynced(accountId, scopeId, "客服一号",
                "+86 138 0000 0001", "8613800000001", "VERIFIED", "ACTIVE", "{\"k\":\"v\"}",
                Instant.now());

        assertThat(updated).isEqualTo(1);
        assertAccount(accountId, "active", "success", "ACTIVE", "VERIFIED");
        assertThat(ownerOf(accountId)).isEqualTo(userId);
    }

    @Test
    void disabledAccountIsNotReactivatedByAnUnrelatedRefreshOfAnotherNumber() {
        UUID disabledId = insertAccount("8613800000002", null);
        UUID otherId = insertAccount("8613800000003", null);

        channelAccountMapper.refreshAdminSynced(otherId, scopeId, "客服二号",
                "+86 138 0000 0003", "8613800000003", "VERIFIED", "ACTIVE", "{}", Instant.now());

        assertAccount(disabledId, "disabled", "idle", null, null);
        assertAccount(otherId, "active", "success", "ACTIVE", "VERIFIED");
    }

    @Test
    void upsertAdminSyncedReactivatesTheExistingRowInsteadOfDuplicatingIt() {
        UUID accountId = insertAccount("8613800000004", userId);

        ChannelAccountEntity entity = new ChannelAccountEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("客服四号");
        entity.setAccountIdentifier("+86 138 0000 0004");
        entity.setAccountIdentifierNormalized("8613800000004");
        entity.setPhoneVerificationStatus("VERIFIED");
        entity.setProviderPhoneStatus("ACTIVE");
        entity.setEncryptedConfig("{}");
        channelAccountMapper.upsertAdminSynced(entity, scopeId, Instant.now());

        assertThat(accountCount("8613800000004")).isEqualTo(1);
        assertAccount(accountId, "active", "success", "ACTIVE", "VERIFIED");
        assertThat(ownerOf(accountId)).isEqualTo(userId);
    }

    private void assertAccount(UUID accountId, String authStatus, String syncStatus,
                               String providerStatus, String verificationStatus) {
        Map<String, Object> row = jdbc.queryForMap(
                "select auth_status, sync_status, provider_phone_status, phone_verification_status "
                        + "from channel_accounts where id = ?", accountId);
        assertThat(row.get("auth_status")).isEqualTo(authStatus);
        assertThat(row.get("sync_status")).isEqualTo(syncStatus);
        assertThat(row.get("provider_phone_status")).isEqualTo(providerStatus);
        assertThat(row.get("phone_verification_status")).isEqualTo(verificationStatus);
    }

    private UUID ownerOf(UUID accountId) {
        return jdbc.queryForObject("select owner_user_id from channel_accounts where id = ?",
                UUID.class, accountId);
    }

    private long accountCount(String normalizedIdentifier) {
        return jdbc.queryForObject("select count(*) from channel_accounts "
                        + "where channel_type = 'chatapp' and account_identifier_normalized = ?",
                Long.class, normalizedIdentifier);
    }

    private UUID insertScope(String externalScopeId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into whatsapp_provider_scopes (id, provider, external_scope_id, scope_type, "
                        + "owner_user_id, status, display_name, identity_status, encrypted_config, "
                        + "last_test_status, last_sync_status) "
                        + "values (?, 'ALIYUN_CAMS', ?, 'ENTERPRISE_API', null, 'READY', ?, "
                        + "'IDENTITY_PENDING', '{}'::jsonb, 'SUCCESS', 'SUCCESS')",
                id, externalScopeId, externalScopeId);
        return id;
    }

    /** The unbind fingerprint: auth_status 'disabled', sync_status 'idle', empty encrypted config. */
    private UUID insertAccount(String normalizedIdentifier, UUID ownerUserId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into channel_accounts (id, owner_user_id, channel_type, name, "
                        + "account_identifier, account_identifier_normalized, auth_status, sync_status, "
                        + "onboarding_mode, encrypted_config, provider_scope_id) "
                        + "values (?, ?, 'chatapp', ?, ?, ?, 'disabled', 'idle', 'ADMIN_API_WABA', "
                        + "'{}'::jsonb, ?)",
                id, ownerUserId, "chatapp-" + normalizedIdentifier, "+" + normalizedIdentifier,
                normalizedIdentifier, scopeId);
        return id;
    }
}
