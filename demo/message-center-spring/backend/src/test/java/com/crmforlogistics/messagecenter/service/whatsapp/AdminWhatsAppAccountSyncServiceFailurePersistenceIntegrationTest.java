package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecentertest.whatsapp.AdminWhatsAppAccountSyncServiceFailurePersistenceTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Proves the "同步失败" projection survives the rollback that the reported failure triggers. The
 * Mockito-based {@link AdminWhatsAppAccountSyncServiceTest} cannot see this: it never runs inside a
 * Spring transaction, so it cannot tell a write that commits from one that is rolled back with the
 * caller's exception. Hence a real PostgreSQL, a real transaction manager and, in the sibling
 * {@link AdminWhatsAppAccountSyncServiceFailurePersistenceAutoCommitDisabledIntegrationTest}, a pool
 * that hands out connections with auto-commit switched off.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AdminWhatsAppAccountSyncServiceFailurePersistenceTestConfiguration.class)
@TestPropertySource(properties = "app.cust-space-id=cust-space-A")
@Testcontainers(disabledWithoutDocker = true)
class AdminWhatsAppAccountSyncServiceFailurePersistenceIntegrationTest {

    private static final String EXTERNAL_SCOPE_ID = "cust-space-A";

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

    @Autowired AdminWhatsAppAccountSyncService syncService;
    @Autowired WhatsAppOnboardingGateway gateway;
    @Autowired JdbcTemplate jdbc;

    private UUID scopeId;

    @BeforeEach
    void setUp() {
        Mockito.reset(gateway);
        jdbc.update("truncate table channel_accounts, whatsapp_provider_scopes cascade");
        scopeId = insertReadyEnterpriseApiScope(EXTERNAL_SCOPE_ID);
    }

    @Test
    void authorizationFailureLeavesFailedProjectionCommittedAfterRollback() {
        when(gateway.syncConfiguredPhoneNumbers(any(WhatsAppProviderScopeEntity.class)))
                .thenThrow(new WhatsAppAuthorizationException("WHATSAPP_CAMS_SCOPE_BLOCKED", HttpStatus.CONFLICT));

        assertThatThrownBy(() -> syncService.sync(null, scopeId))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_CAMS_SCOPE_BLOCKED");

        Map<String, Object> row = syncProjection();
        assertThat(row.get("last_sync_status")).isEqualTo("FAILED");
        assertThat(row.get("last_sync_error_code")).isEqualTo("WHATSAPP_CAMS_SCOPE_BLOCKED");
    }

    @Test
    void unexpectedFailureLeavesFailedProjectionCommittedWithGenericCode() {
        when(gateway.syncConfiguredPhoneNumbers(any(WhatsAppProviderScopeEntity.class)))
                .thenThrow(new IllegalStateException("CAMS exploded"));

        assertThatThrownBy(() -> syncService.sync(null, scopeId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CAMS exploded");

        Map<String, Object> row = syncProjection();
        assertThat(row.get("last_sync_status")).isEqualTo("FAILED");
        assertThat(row.get("last_sync_error_code")).isEqualTo("WHATSAPP_CAMS_SYNC_FAILED");
    }

    @Test
    void successfulSyncStillRecordsSuccessInTheCommittedTransaction() {
        when(gateway.syncConfiguredPhoneNumbers(any(WhatsAppProviderScopeEntity.class))).thenReturn(java.util.List.of());

        syncService.sync(null, scopeId);

        Map<String, Object> row = syncProjection();
        assertThat(row.get("last_sync_status")).isEqualTo("SUCCESS");
        assertThat(row.get("last_sync_error_code")).isNull();
    }

    private Map<String, Object> syncProjection() {
        return jdbc.queryForMap("select last_sync_status, last_sync_error_code, last_synced_at "
                + "from whatsapp_provider_scopes where id = ?", scopeId);
    }

    private UUID insertReadyEnterpriseApiScope(String externalScopeId) {
        UUID id = UUID.randomUUID();
        // display_name is NOT NULL with no default; the migration backfills it from external_scope_id.
        jdbc.update("insert into whatsapp_provider_scopes (id, provider, external_scope_id, scope_type, "
                + "owner_user_id, status, identity_status, display_name, encrypted_config) "
                + "values (?, 'ALIYUN_CAMS', ?, 'ENTERPRISE_API', null, 'READY', 'IDENTITY_PENDING', ?, "
                + "'{\"accessKeyId\":\"k\",\"accessKeySecret\":\"s\"}'::jsonb)",
                id, externalScopeId, externalScopeId);
        return id;
    }
}
