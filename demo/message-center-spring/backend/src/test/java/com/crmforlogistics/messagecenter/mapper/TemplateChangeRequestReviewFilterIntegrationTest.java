package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.TemplateChangeRequestEntity;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes the admin review queue's filter SQL against a real PostgreSQL. The queue used to be filtered in
 * the browser over the current page, so the pager reported the unfiltered total; the filters now live in the
 * page query and the count query together, and only a real database can prove they agree.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AdminWhatsAppWorkbenchMapperTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class TemplateChangeRequestReviewFilterIntegrationTest {

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

    @Autowired TemplateChangeRequestMapper changeRequestMapper;
    @Autowired JdbcTemplate jdbc;

    private static final Instant BASE_TIME = Instant.parse("2026-09-01T00:00:00Z");

    private UUID accountId;
    private UUID pendingShipment;
    private UUID rejectedShipment;
    private UUID succeededShipmentEn;
    private UUID failedBill;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table template_change_requests, message_templates, channel_accounts, "
                + "whatsapp_provider_scopes, users cascade");
        UUID requesterA = insertUser("李四");
        UUID requesterB = insertUser("王五");
        UUID scopeId = insertScope("cust-space-review", "华东 CAMS");
        accountId = insertAccount(scopeId);
        UUID shipmentTemplate = insertTemplate(accountId, scopeId, "发货提醒", "tmpl-ship");
        UUID shipmentEnTemplate = insertTemplate(accountId, scopeId, "Shipment Notice", "tmpl-ship-en");
        UUID billTemplate = insertTemplate(accountId, scopeId, "账单提醒", "tmpl-bill");

        // Explicit created_at values keep "created_at desc, id desc" deterministic.
        pendingShipment = insertChangeRequest(shipmentTemplate, "PENDING_APPROVAL", requesterA, BASE_TIME.plusSeconds(1));
        rejectedShipment = insertChangeRequest(shipmentTemplate, "REJECTED", requesterA, BASE_TIME.plusSeconds(2));
        succeededShipmentEn = insertChangeRequest(shipmentEnTemplate, "SUCCEEDED", requesterB, BASE_TIME.plusSeconds(3));
        failedBill = insertChangeRequest(billTemplate, "EXECUTION_FAILED", requesterB, BASE_TIME.plusSeconds(4));
    }

    @Test
    void nullAndEmptyFiltersReturnTheWholeQueueInCreationOrder() {
        assertThat(changeRequestMapper.countForReview(null, null)).isEqualTo(4);
        assertThat(changeRequestMapper.countForReview("", ""))
                .as("an empty filter is not a filter")
                .isEqualTo(4);

        assertThat(ids(changeRequestMapper.listForReview(0, 20, null, null)))
                .containsExactly(failedBill, succeededShipmentEn, rejectedShipment, pendingShipment);
        assertThat(ids(changeRequestMapper.listForReview(0, 20, "", "")))
                .containsExactly(failedBill, succeededShipmentEn, rejectedShipment, pendingShipment);
    }

    @Test
    void statusFilterNarrowsTheRowsAndTheCountTogether() {
        assertThat(ids(changeRequestMapper.listForReview(0, 20, "PENDING_APPROVAL", null)))
                .containsExactly(pendingShipment);
        assertThat(changeRequestMapper.countForReview("PENDING_APPROVAL", null)).isEqualTo(1);

        assertThat(ids(changeRequestMapper.listForReview(0, 20, "REJECTED", null)))
                .containsExactly(rejectedShipment);
        assertThat(changeRequestMapper.countForReview("REJECTED", null)).isEqualTo(1);

        assertThat(ids(changeRequestMapper.listForReview(0, 20, "STALE", null))).isEmpty();
        assertThat(changeRequestMapper.countForReview("STALE", null)).isZero();
    }

    @Test
    void searchMatchesTemplateNameProviderTemplateIdAndRequesterNameCaseInsensitively() {
        assertThat(ids(changeRequestMapper.listForReview(0, 20, null, "发货")))
                .containsExactly(rejectedShipment, pendingShipment);
        assertThat(changeRequestMapper.countForReview(null, "发货")).isEqualTo(2);

        assertThat(ids(changeRequestMapper.listForReview(0, 20, null, "tmpl-ship-en")))
                .containsExactly(succeededShipmentEn);
        assertThat(changeRequestMapper.countForReview(null, "tmpl-ship-en")).isEqualTo(1);

        assertThat(ids(changeRequestMapper.listForReview(0, 20, null, "李四")))
                .containsExactly(rejectedShipment, pendingShipment);
        assertThat(changeRequestMapper.countForReview(null, "李四")).isEqualTo(2);

        assertThat(ids(changeRequestMapper.listForReview(0, 20, null, "SHIPMENT")))
                .containsExactly(succeededShipmentEn);
        assertThat(changeRequestMapper.countForReview(null, "SHIPMENT")).isEqualTo(1);
    }

    @Test
    void statusAndSearchCombineInBothQueries() {
        assertThat(ids(changeRequestMapper.listForReview(0, 20, "SUCCEEDED", "王五")))
                .containsExactly(succeededShipmentEn);
        assertThat(changeRequestMapper.countForReview("SUCCEEDED", "王五")).isEqualTo(1);

        assertThat(ids(changeRequestMapper.listForReview(0, 20, "SUCCEEDED", "发货"))).isEmpty();
        assertThat(changeRequestMapper.countForReview("SUCCEEDED", "发货"))
                .as("the count must describe the same filtered set as the page query")
                .isZero();
    }

    @Test
    void filteredCountDrivesPaging() {
        assertThat(changeRequestMapper.countForReview(null, "发货")).isEqualTo(2);
        assertThat(ids(changeRequestMapper.listForReview(0, 1, null, "发货"))).containsExactly(rejectedShipment);
        assertThat(ids(changeRequestMapper.listForReview(1, 1, null, "发货"))).containsExactly(pendingShipment);
    }

    @Test
    void searchValueIsBoundAndCannotAlterTheStatement() {
        String hostile = "'; drop table template_change_requests; --";

        assertThat(changeRequestMapper.listForReview(0, 20, null, hostile)).isEmpty();
        assertThat(changeRequestMapper.countForReview(null, hostile)).isZero();
        assertThat(changeRequestMapper.countForReview(null, null))
                .as("the table is still there")
                .isEqualTo(4);
    }

    private static List<UUID> ids(List<TemplateChangeRequestEntity> rows) {
        return rows.stream().map(TemplateChangeRequestEntity::getId).toList();
    }

    private UUID insertUser(String displayName) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', ?)", id, "user" + id, "user" + id, displayName);
        return id;
    }

    private UUID insertScope(String externalScopeId, String displayName) {
        UUID id = UUID.randomUUID();
        // whatsapp_provider_scopes.display_name is NOT NULL without a default.
        jdbc.update("insert into whatsapp_provider_scopes (id, provider, external_scope_id, scope_type, "
                + "owner_user_id, status, display_name, identity_status, encrypted_config, "
                + "last_test_status, last_sync_status) "
                + "values (?, 'ALIYUN_CAMS', ?, 'ENTERPRISE_API', null, 'READY', ?, 'IDENTITY_PENDING', "
                + "'{}'::jsonb, 'SUCCESS', 'SUCCESS')",
                id, externalScopeId, displayName);
        return id;
    }

    private UUID insertAccount(UUID scopeId) {
        UUID id = UUID.randomUUID();
        String suffix = id.toString();
        jdbc.update("insert into channel_accounts (id, channel_type, name, account_identifier, "
                + "account_identifier_normalized, encrypted_config, provider_scope_id, "
                + "provider_phone_status, phone_verification_status) "
                + "values (?, 'whatsapp', ?, ?, ?, '{}'::jsonb, ?, 'ACTIVE', 'VERIFIED')",
                id, "whatsapp-" + suffix, suffix, suffix, scopeId);
        return id;
    }

    private UUID insertTemplate(UUID channelAccountId, UUID scopeId, String name, String providerTemplateId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into message_templates (id, channel_account_id, provider_template_id, "
                + "language_code, name, body, status, provider_scope_id) "
                + "values (?, ?, ?, 'zh_CN', ?, 'body', 'APPROVED', ?)",
                id, channelAccountId, providerTemplateId, name, scopeId);
        return id;
    }

    private UUID insertChangeRequest(UUID templateId, String status, UUID requestedByUserId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        // ck_template_change_review_reason: a REJECTED request must carry a reason.
        String reviewReason = "REJECTED".equals(status) ? "not wanted" : null;
        jdbc.update("insert into template_change_requests (id, template_id, change_type, "
                + "requested_payload_jsonb, base_version, requested_by_user_id, "
                + "requested_via_account_id, status, idempotency_key, review_reason, created_at) "
                + "values (?, ?, 'MODIFY', '{}'::jsonb, 0, ?, ?, ?, ?, ?, ?)",
                id, templateId, requestedByUserId, accountId, status, "idem-" + id, reviewReason,
                java.sql.Timestamp.from(createdAt));
        return id;
    }
}
