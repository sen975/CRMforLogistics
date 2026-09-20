package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppWorkbenchService;
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

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Executes the two overview aggregates of the admin home page against a real PostgreSQL, so the
 * dashboard's numbers are produced by the same SQL the runtime uses. Task 6b's tests stubbed these
 * mapper interfaces, leaving the SQL itself unverified.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AdminWhatsAppWorkbenchMapperTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AdminWhatsAppWorkbenchMapperIntegrationTest {

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
    @Autowired TemplateChangeRequestMapper templateChangeRequestMapper;
    @Autowired WhatsAppProviderScopeMapper whatsAppProviderScopeMapper;
    @Autowired JdbcTemplate jdbc;

    private UUID userId;
    private UUID scopeA;
    private UUID scopeB;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table template_change_requests, message_templates, channel_accounts, "
                + "whatsapp_provider_scopes, users cascade");
        userId = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', 'admin')", userId, "admin" + userId, "admin" + userId);
        scopeA = insertScope("cust-space-A", "华东 CAMS");
        scopeB = insertScope("cust-space-B", "华南 CAMS");
    }

    @Test
    void countUsableAccountsByScopeCountsByProviderStatusAlone() {
        seedOneScopeWithAllRejectedVariants(scopeA);
        insertAccount(scopeB, "whatsapp", "ACTIVE", "VERIFIED", null);
        insertAccount(scopeB, "chatapp", "ACTIVE", "VERIFIED", null);
        insertAccount(scopeB, "whatsapp", "ACTIVE", "VERIFIED", null);

        List<ChannelAccountMapper.UsableAccountCountRow> rows =
                channelAccountMapper.countUsableAccountsByScope();

        assertThat(rows).hasSize(2);
        Map<UUID, Long> byScope = new HashMap<>();
        rows.forEach(row -> byScope.put(row.scopeId(), row.total()));
        assertThat(byScope).containsOnly(entry(scopeA, 3L), entry(scopeB, 3L));
    }

    @Test
    void countPendingApprovalsByScopeGroupsByTemplateScopeAndCountsOnlyPendingApproval() {
        UUID accountId = insertAccount(scopeA, "whatsapp", "ACTIVE", "VERIFIED", null);
        UUID templateA = insertTemplate(accountId, scopeA, "tmpl-a");
        UUID templateB = insertTemplate(accountId, scopeB, "tmpl-b");
        UUID templateNull = insertTemplate(accountId, null, "tmpl-null");

        insertChangeRequest(templateA, "PENDING_APPROVAL", accountId);
        insertChangeRequest(templateA, "PENDING_APPROVAL", accountId);
        insertChangeRequest(templateB, "PENDING_APPROVAL", accountId);
        insertChangeRequest(templateNull, "PENDING_APPROVAL", accountId);
        insertChangeRequest(templateA, "SUCCEEDED", accountId);
        insertChangeRequest(templateA, "REJECTED", accountId);
        insertChangeRequest(templateA, "EXECUTION_FAILED", accountId);

        List<TemplateChangeRequestMapper.PendingApprovalCountRow> rows =
                templateChangeRequestMapper.countPendingApprovalsByScope();

        assertThat(rows).hasSize(3);
        Map<UUID, Long> byScope = new HashMap<>();
        rows.forEach(row -> byScope.put(row.scopeId(), row.total()));
        assertThat(byScope).containsOnly(entry(scopeA, 2L), entry(scopeB, 1L), entry((UUID) null, 1L));
    }

    @Test
    void serviceTurnsTheAggregateRowsIntoTheDashboardNumbers() {
        UUID accountId = seedOneScopeWithAllRejectedVariants(scopeA);
        insertAccount(scopeB, "whatsapp", "ACTIVE", "VERIFIED", null);
        insertAccount(scopeB, "chatapp", "ACTIVE", "VERIFIED", null);
        insertAccount(scopeB, "whatsapp", "ACTIVE", "VERIFIED", null);
        UUID templateA = insertTemplate(accountId, scopeA, "tmpl-a");
        UUID templateB = insertTemplate(accountId, scopeB, "tmpl-b");
        UUID templateNull = insertTemplate(accountId, null, "tmpl-null");
        insertChangeRequest(templateA, "PENDING_APPROVAL", accountId);
        insertChangeRequest(templateA, "PENDING_APPROVAL", accountId);
        insertChangeRequest(templateB, "PENDING_APPROVAL", accountId);
        insertChangeRequest(templateNull, "PENDING_APPROVAL", accountId);
        insertChangeRequest(templateA, "SUCCEEDED", accountId);
        insertChangeRequest(templateA, "REJECTED", accountId);
        insertChangeRequest(templateA, "EXECUTION_FAILED", accountId);

        AdminWhatsAppWorkbenchService service = new AdminWhatsAppWorkbenchService(
                whatsAppProviderScopeMapper, channelAccountMapper, templateChangeRequestMapper);
        AdminWhatsAppWorkbenchService.OverviewView view = service.overview();

        assertThat(view.scopes()).hasSize(2);
        Map<UUID, AdminWhatsAppWorkbenchService.ScopeOverview> byScope = new HashMap<>();
        view.scopes().forEach(scope -> byScope.put(scope.scopeId(), scope));
        assertThat(byScope).containsOnlyKeys(scopeA, scopeB);
        assertThat(byScope.get(scopeA).usableAccountCount()).isEqualTo(3L);
        assertThat(byScope.get(scopeA).pendingApprovalCount()).isEqualTo(2L);
        assertThat(byScope.get(scopeB).usableAccountCount()).isEqualTo(3L);
        assertThat(byScope.get(scopeB).pendingApprovalCount()).isEqualTo(1L);

        long summed = view.scopes().stream()
                .mapToLong(AdminWhatsAppWorkbenchService.ScopeOverview::pendingApprovalCount).sum();
        assertThat(view.totalPendingApprovalCount()).isEqualTo(3L);
        assertThat(view.totalPendingApprovalCount()).isEqualTo(summed);
        // The null-scope request belongs to no scope, so no projection can report it.
        assertThat(view.scopes())
                .extracting(AdminWhatsAppWorkbenchService.ScopeOverview::pendingApprovalCount)
                .containsExactlyInAnyOrder(2L, 1L);
    }

    /**
     * Three genuinely usable rows plus four that must each be rejected by exactly one predicate.
     * Returns the first usable account, so a caller can hang templates off the same scope without
     * adding a usable row of its own.
     */
    private UUID seedOneScopeWithAllRejectedVariants(UUID scopeId) {
        UUID usableAccountId = insertAccount(scopeId, "whatsapp", "ACTIVE", "VERIFIED", null);
        insertAccount(scopeId, "chatapp", "ACTIVE", "VERIFIED", null);
        // phone_verification_status = null: sendability follows the provider status alone
        insertAccount(scopeId, "whatsapp", "ACTIVE", null, null);
        // provider_phone_status = 'UNKNOWN'
        insertAccount(scopeId, "whatsapp", "UNKNOWN", "VERIFIED", null);
        // provider_phone_status = 'PENDING'
        insertAccount(scopeId, "whatsapp", "PENDING", "VERIFIED", null);
        // soft-deleted
        insertAccount(scopeId, "whatsapp", "ACTIVE", "VERIFIED", OffsetDateTime.now());
        // channel_type outside ('chatapp', 'whatsapp')
        insertAccount(scopeId, "email", "ACTIVE", "VERIFIED", null);
        return usableAccountId;
    }

    private UUID insertScope(String externalScopeId, String displayName) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into whatsapp_provider_scopes (id, provider, external_scope_id, scope_type, "
                + "owner_user_id, status, display_name, identity_status, encrypted_config, "
                + "last_test_status, last_sync_status) "
                + "values (?, 'ALIYUN_CAMS', ?, 'ENTERPRISE_API', null, 'READY', ?, 'IDENTITY_PENDING', "
                + "'{}'::jsonb, 'SUCCESS', 'SUCCESS')",
                id, externalScopeId, displayName);
        return id;
    }

    private UUID insertAccount(UUID scopeId, String channelType, String providerPhoneStatus,
                               String phoneVerificationStatus, OffsetDateTime deletedAt) {
        UUID id = UUID.randomUUID();
        String suffix = id.toString();
        jdbc.update("insert into channel_accounts (id, channel_type, name, account_identifier, "
                + "account_identifier_normalized, encrypted_config, provider_scope_id, "
                + "provider_phone_status, phone_verification_status, deleted_at) "
                + "values (?, ?, ?, ?, ?, '{}'::jsonb, ?, ?, ?, ?)",
                id, channelType, channelType + "-" + suffix, suffix, suffix,
                scopeId, providerPhoneStatus, phoneVerificationStatus, deletedAt);
        return id;
    }

    private UUID insertTemplate(UUID channelAccountId, UUID scopeId, String providerTemplateId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into message_templates (id, channel_account_id, provider_template_id, "
                + "language_code, name, body, status, provider_scope_id) "
                + "values (?, ?, ?, 'zh_CN', ?, 'body', 'APPROVED', ?)",
                id, channelAccountId, providerTemplateId, providerTemplateId, scopeId);
        return id;
    }

    private UUID insertChangeRequest(UUID templateId, String status, UUID requestedViaAccountId) {
        UUID id = UUID.randomUUID();
        // ck_template_change_review_reason: a REJECTED request must carry a reason.
        String reviewReason = "REJECTED".equals(status) ? "not wanted" : null;
        jdbc.update("insert into template_change_requests (id, template_id, change_type, "
                + "requested_payload_jsonb, base_version, requested_by_user_id, "
                + "requested_via_account_id, status, idempotency_key, review_reason) "
                + "values (?, ?, 'MODIFY', '{}'::jsonb, 0, ?, ?, ?, ?, ?)",
                id, templateId, userId, requestedViaAccountId, status, "idem-" + id, reviewReason);
        return id;
    }
}
