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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
 * The robustness half of the FAILED-projection proof: the pool is configured with
 * {@code auto-commit=false}, so a connection that is still enlisted in the rolled-back transaction
 * would silently drop the {@code UPDATE} when it is returned to the pool. Only a write issued after
 * the transaction has completed and released its connection can be observed here.
 *
 * <p>This is the assertion that separates the fix from a {@code TransactionSynchronization}
 * deferred to {@code afterCompletion}: that variant runs while the transaction's connection is
 * still bound to the thread, so its uncommitted {@code UPDATE} is rolled back on release and the
 * FAILED row never appears.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AdminWhatsAppAccountSyncServiceFailurePersistenceTestConfiguration.class)
@TestPropertySource(properties = {
        "app.cust-space-id=cust-space-A",
        "spring.datasource.hikari.auto-commit=false"
})
@Testcontainers(disabledWithoutDocker = true)
class AdminWhatsAppAccountSyncServiceFailurePersistenceAutoCommitDisabledIntegrationTest {

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
    @Autowired PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private UUID scopeId;

    @BeforeEach
    void setUp() {
        Mockito.reset(gateway);
        transactionTemplate = new TransactionTemplate(transactionManager);
        // With auto-commit off, a bare JdbcTemplate write is rolled back by the pool when its
        // connection is released, so the seed has to commit explicitly through a transaction.
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("truncate table channel_accounts, whatsapp_provider_scopes cascade");
            scopeId = insertReadyEnterpriseApiScope(EXTERNAL_SCOPE_ID);
        });
    }

    @Test
    void failedProjectionIsCommittedEvenWhenThePoolDisablesAutoCommit() {
        when(gateway.syncConfiguredPhoneNumbers(any(WhatsAppProviderScopeEntity.class)))
                .thenThrow(new WhatsAppAuthorizationException(
                        "WHATSAPP_CAMS_CREDENTIALS_REJECTED", HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> syncService.sync(null, scopeId))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_CAMS_CREDENTIALS_REJECTED");

        Map<String, Object> row = jdbc.queryForMap("select last_sync_status, last_sync_error_code, "
                + "last_synced_at from whatsapp_provider_scopes where id = ?", scopeId);
        assertThat(row.get("last_sync_status")).isEqualTo("FAILED");
        assertThat(row.get("last_sync_error_code")).isEqualTo("WHATSAPP_CAMS_CREDENTIALS_REJECTED");
    }

    @Test
    void unexpectedFailureIsCommittedTooEvenWhenThePoolDisablesAutoCommit() {
        when(gateway.syncConfiguredPhoneNumbers(any(WhatsAppProviderScopeEntity.class)))
                .thenThrow(new IllegalStateException("CAMS exploded"));

        assertThatThrownBy(() -> syncService.sync(null, scopeId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CAMS exploded");

        Map<String, Object> row = jdbc.queryForMap("select last_sync_status, last_sync_error_code "
                + "from whatsapp_provider_scopes where id = ?", scopeId);
        assertThat(row.get("last_sync_status")).isEqualTo("FAILED");
        assertThat(row.get("last_sync_error_code")).isEqualTo("WHATSAPP_CAMS_SYNC_FAILED");
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
