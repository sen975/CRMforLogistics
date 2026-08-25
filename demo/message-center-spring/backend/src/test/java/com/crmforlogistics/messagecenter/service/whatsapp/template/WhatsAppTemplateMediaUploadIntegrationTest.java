package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService.UploadResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.MediaAssetStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.UploadedMedia;
import com.crmforlogistics.messagecentertest.whatsapp.WhatsAppTemplateMediaUploadTestConfiguration;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = {com.crmforlogistics.messagecenter.App.class, WhatsAppTemplateMediaUploadTestConfiguration.class},
        properties = "spring.profiles.active=test")
@Testcontainers
class WhatsAppTemplateMediaUploadIntegrationTest {
    private static final String ONE_BYTE_SHA256 =
            "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a";

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
        registry.add("minio.endpoint", () -> "http://localhost:9999");
        registry.add("minio.access-key", () -> "test");
        registry.add("minio.secret-key", () -> "test");
        registry.add("minio.bucket", () -> "test");
        registry.add("credential.master-key", () ->
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired private WhatsAppTemplateMediaUploadService mediaUploadService;
    @Autowired private WhatsAppTemplateMediaUploadStore mediaUploadStore;
    @Autowired private WhatsAppTemplateGateway gateway;
    @Autowired private JdbcTemplate jdbc;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private UUID accountId;
    private UUID actorUserId;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                        + "values (?, ?, ?, 'hash', 'Media Test')",
                actorUserId, "media-" + actorUserId, "media-" + actorUserId);
        jdbc.update("insert into channel_accounts "
                        + "(id, channel_type, name, account_identifier, account_identifier_normalized, "
                        + "auth_status, encrypted_config) values (?, 'whatsapp', 'Media Test', ?, ?, 'active', '{}'::jsonb)",
                accountId, accountId.toString(), accountId.toString());
        Mockito.reset(gateway);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void concurrentSameRequestCallsGatewayOnceOutsideTransaction() throws Exception {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(gateway.upload(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet();
            providerEntered.countDown();
            assertThat(releaseProvider.await(5, TimeUnit.SECONDS)).isTrue();
            return new UploadedMedia("templates/a.png", "https://provider.invalid/a.png",
                    HeaderFormat.IMAGE, "image/png", 1, ONE_BYTE_SHA256);
        });

        Future<UploadResult> first = executor.submit(() -> upload("same-request"));
        assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
        UploadResult duplicate = upload("same-request");
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.asset().assetStatus()).isEqualTo(MediaAssetStatus.PROCESSING);
        releaseProvider.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).asset().assetStatus()).isEqualTo(MediaAssetStatus.UPLOADED);
        assertThat(calls).hasValue(1);

        assertThat(jdbc.queryForObject("select count(*) from template_media_assets "
                + "where channel_account_id = ? and client_request_id = 'same-request'", Integer.class, accountId))
                .isEqualTo(1);
        UUID assetId = jdbc.queryForObject("select id from template_media_assets "
                + "where channel_account_id = ? and client_request_id = 'same-request'", UUID.class, accountId);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where resource_id = ? "
                + "and action = 'WHATSAPP_TEMPLATE_MEDIA_UPLOAD'", Integer.class, assetId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select result from audit_logs where resource_id = ?", String.class, assetId))
                .isEqualTo("success");
        assertThat(jdbc.queryForObject("select after_summary_jsonb::text from audit_logs where resource_id = ?",
                String.class, assetId))
                .contains("\"status\": \"UPLOADED\"").contains("\"format\": \"IMAGE\"");
    }

    @Test
    void staleProcessingConvergesToUnknownWithoutCallingGateway() {
        String requestId = "stale-request";
        UUID assetId = UUID.randomUUID();
        Instant startedAt = Instant.now().minusSeconds(91);
        jdbc.update("insert into template_media_assets "
                        + "(id, channel_account_id, client_request_id, media_format, content_type, size_bytes, sha256, "
                        + "asset_status, created_by_user_id, trace_id, started_at, created_at, updated_at) "
                        + "values (?, ?, ?, 'IMAGE', 'image/png', 1, ?, 'PROCESSING', ?, 'trace-stale', ?, ?, ?)",
                assetId, accountId, requestId, ONE_BYTE_SHA256, actorUserId,
                Timestamp.from(startedAt), Timestamp.from(startedAt), Timestamp.from(startedAt));
        clearInvocations(gateway);

        var result = mediaUploadService.find(accountId, requestId);

        assertThat(result.assetStatus()).isEqualTo(MediaAssetStatus.SUBMISSION_UNKNOWN);
        assertThat(result.errorCode()).isEqualTo("TEMPLATE_MEDIA_SUBMISSION_UNKNOWN");
        verify(gateway, never()).upload(any(), any(), any(), any(), any());
        assertThat(jdbc.queryForObject("select result from audit_logs where resource_id = ?", String.class, assetId))
                .isEqualTo("unknown");
    }

    @Test
    void auditResultConstraintKeepsLegacyValuesAndRejectsDomainStateDuplicates() {
        assertThat(insertAuditResult("denied")).isEqualTo(1);
        assertThat(insertAuditResult("unknown")).isEqualTo(1);
        assertThatThrownBy(() -> insertAuditResult("SUCCEEDED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAuditResult("FAILED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAuditResult("SUBMISSION_UNKNOWN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void markUploadedRejectsNonProcessingAssetWithoutAudit() {
        UUID assetId = insertTerminalAsset("terminal-uploaded", "FAILED");
        UploadedMedia uploaded = new UploadedMedia("templates/a.png", "https://provider.invalid/a.png",
                HeaderFormat.IMAGE, "image/png", 1, ONE_BYTE_SHA256);

        assertThatThrownBy(() -> mediaUploadStore.markUploaded(assetId, uploaded, Instant.now()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(mediaAuditCount(assetId)).isZero();
    }

    @Test
    void markFailedRejectsNonProcessingAssetWithoutAudit() {
        UUID assetId = insertTerminalAsset("terminal-failed", "SUBMISSION_UNKNOWN");
        WhatsAppTemplateException failure = new WhatsAppTemplateException(
                "PROVIDER_PERMISSION_DENIED", org.springframework.http.HttpStatus.FORBIDDEN,
                "denied", java.util.Map.of(), null, false);

        assertThatThrownBy(() -> mediaUploadStore.markFailed(assetId, failure, Instant.now()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(mediaAuditCount(assetId)).isZero();
    }

    @Test
    void markUnknownRejectsNonProcessingAssetWithoutAudit() {
        UUID assetId = insertTerminalAsset("terminal-unknown", "FAILED");
        WhatsAppTemplateException timeout = new WhatsAppTemplateException(
                "TEMPLATE_PROVIDER_TIMEOUT", org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,
                "timeout", java.util.Map.of(), null, true);

        assertThatThrownBy(() -> mediaUploadStore.markUnknown(assetId, timeout, Instant.now()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(mediaAuditCount(assetId)).isZero();
    }

    private UploadResult upload(String clientRequestId) {
        return mediaUploadService.upload(accountId, HeaderFormat.IMAGE,
                new ByteArrayInputStream(new byte[]{1}), 1, "a.png", "image/png",
                clientRequestId, actorUserId, "trace-" + clientRequestId);
    }

    private int insertAuditResult(String result) {
        return jdbc.update("insert into audit_logs "
                        + "(id, actor_user_id, action, resource_type, resource_id, before_summary_jsonb, "
                        + "after_summary_jsonb, result, trace_id) values (?, ?, 'TEST_AUDIT_RESULT', "
                        + "'TEMPLATE_MEDIA_ASSET', ?, '{}'::jsonb, '{}'::jsonb, ?, 'trace-audit-result')",
                UUID.randomUUID(), actorUserId, UUID.randomUUID(), result);
    }

    private UUID insertTerminalAsset(String requestId, String status) {
        UUID assetId = UUID.randomUUID();
        jdbc.update("insert into template_media_assets "
                        + "(id, channel_account_id, client_request_id, media_format, content_type, size_bytes, sha256, "
                        + "asset_status, created_by_user_id, trace_id, started_at, created_at, updated_at) "
                        + "values (?, ?, ?, 'IMAGE', 'image/png', 1, ?, ?, ?, 'trace-terminal', now(), now(), now())",
                assetId, accountId, requestId, ONE_BYTE_SHA256, status, actorUserId);
        return assetId;
    }

    private int mediaAuditCount(UUID assetId) {
        return jdbc.queryForObject("select count(*) from audit_logs where resource_id = ? "
                + "and action = 'WHATSAPP_TEMPLATE_MEDIA_UPLOAD'", Integer.class, assetId);
    }
}
