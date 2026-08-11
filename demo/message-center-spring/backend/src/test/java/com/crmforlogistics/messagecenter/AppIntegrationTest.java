package com.crmforlogistics.messagecenter;

import io.minio.MinioClient;
import com.crmforlogistics.messagecenter.entity.AuditLogEntity;
import com.crmforlogistics.messagecenter.entity.TemplateOperationEntity;
import com.crmforlogistics.messagecenter.mapper.AuditLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateOperationMapper;
import com.crmforlogistics.messagecenter.service.auth.BootstrapService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test"
        }
)
@Testcontainers
public class AppIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Disable MinIO for tests
        registry.add("minio.endpoint", () -> "http://localhost:9999");
        registry.add("minio.access-key", () -> "test");
        registry.add("minio.secret-key", () -> "test");
        registry.add("minio.bucket", () -> "test");
        registry.add("credential.master-key", () ->
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    /**
     * Provides a mock MinioClient so MinioStorage does not attempt real
     * network calls during context initialization.
     */
    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public MinioClient testMinioClient() {
            return Mockito.mock(MinioClient.class);
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    BootstrapService bootstrapService;

    @Autowired
    TemplateMapper templateMapper;

    @Autowired
    TemplateOperationMapper templateOperationMapper;

    @Autowired
    AuditLogMapper auditLogMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
        // Verify Spring context starts successfully
    }

    @Test
    void templateUpsertPersistsAuditMetadataAsJsonb() {
        UUID channelAccountId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO channel_accounts
                    (id, channel_type, name, account_identifier, account_identifier_normalized,
                     auth_status, encrypted_config)
                VALUES (?, 'chatapp', 'Test WhatsApp', ?, ?, 'active', '{}'::jsonb)
                """, channelAccountId, channelAccountId.toString(), channelAccountId.toString());

        templateMapper.upsert(
                channelAccountId, "welcome_001", "en_US", "welcome", "Hello {{name}}",
                "APPROVED", Instant.parse("2026-08-09T10:15:30Z"),
                "{\"auditStatus\":\"pass\"}",
                Instant.parse("2026-08-09T10:16:00Z"));
        templateMapper.upsert(
                channelAccountId, "welcome_001", "en_US", "welcome", "",
                "REJECTED", Instant.parse("2026-08-10T01:59:30Z"),
                "{\"auditStatus\":\"fail\",\"reason\":\"Variables do not match\"}",
                Instant.parse("2026-08-10T02:00:00Z"));

        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT body, status, metadata_jsonb::text AS metadata
                FROM message_templates
                WHERE channel_account_id = ? AND provider_template_id = ? AND language_code = ?
                """, channelAccountId, "welcome_001", "en_US");
        assertThat(stored.get("body")).isEqualTo("Hello {{name}}");
        assertThat(stored.get("status")).isEqualTo("REJECTED");
        assertThat((String) stored.get("metadata"))
                .contains("\"auditStatus\": \"fail\"")
                .contains("\"reason\": \"Variables do not match\"");
    }

    @Test
    void templateLifecyclePersistenceEnforcesIdempotencyAndSendEligibility() {
        UUID channelAccountId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO channel_accounts
                    (id, channel_type, name, account_identifier, account_identifier_normalized,
                     auth_status, encrypted_config)
                VALUES (?, 'chatapp', 'Lifecycle WhatsApp', ?, ?, 'active', '{}'::jsonb)
                """, channelAccountId, channelAccountId.toString(), channelAccountId.toString());

        assertThat(jdbcTemplate.queryForObject("select count(*) from template_operations", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from template_media_assets", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_logs", Integer.class))
                .isZero();

        TemplateOperationEntity operation = new TemplateOperationEntity();
        operation.setId(UUID.randomUUID());
        operation.setChannelAccountId(channelAccountId);
        operation.setIdempotencyKey("request-001");
        operation.setOperationType("CREATE");
        operation.setLanguageCode("en_US");
        operation.setRequestedSnapshotJsonb("{}");
        operation.setOperationStatus("PROCESSING");
        operation.setStartedAt(Instant.parse("2026-08-10T03:00:00Z"));
        assertThat(templateOperationMapper.insertIgnore(operation)).isEqualTo(1);

        TemplateOperationEntity duplicate = new TemplateOperationEntity();
        duplicate.setId(UUID.randomUUID());
        duplicate.setChannelAccountId(channelAccountId);
        duplicate.setIdempotencyKey("request-001");
        duplicate.setOperationType("CREATE");
        duplicate.setLanguageCode("en_US");
        duplicate.setRequestedSnapshotJsonb("{}");
        duplicate.setOperationStatus("PROCESSING");
        duplicate.setStartedAt(Instant.parse("2026-08-10T03:00:00Z"));
        assertThat(templateOperationMapper.insertIgnore(duplicate)).isZero();
        assertThat(templateOperationMapper.findByIdempotency(channelAccountId, "request-001"))
                .map(TemplateOperationEntity::getId)
                .contains(operation.getId());

        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.setId(UUID.randomUUID());
        auditLog.setAction("template.create");
        auditLog.setResourceType("whatsapp_template");
        auditLog.setResourceId(operation.getId());
        auditLog.setAfterSummaryJsonb("{\"templateCode\":\"welcome_001\"}");
        auditLog.setResult("success");
        auditLog.setTraceId("trace-001");
        auditLog.setOccurredAt(Instant.parse("2026-08-10T03:00:01Z"));
        assertThat(auditLogMapper.insert(auditLog)).isEqualTo(1);

        jdbcTemplate.update("""
                INSERT INTO message_templates
                    (id, channel_account_id, provider_template_id, language_code, name, body, status,
                     metadata_jsonb, components_jsonb, allow_send, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, 'welcome_001', 'en_US', 'welcome', 'Hello {{name}}',
                        'APPROVED', '{}'::jsonb, '[{"type":"BODY"}]'::jsonb, false, now(), now())
                """, channelAccountId);

        assertThat(templateMapper.findForSend(channelAccountId, "welcome_001", "en_US")).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_logs", Integer.class)).isEqualTo(1);
    }

    @Test
    void auditLogMapperExposesOnlyTheStructuredAppendContract() {
        assertThat(AuditLogMapper.class.getInterfaces())
                .as("audit logging must not expose MyBatis-Plus generic update/delete operations")
                .isEmpty();
    }

    @Test
    void protectedEndpointWithoutCredentialsReturns401() {
        ResponseEntity<String> response = rest.getForEntity("/api/contacts", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void authEndpointIsPublic() {
        ResponseEntity<String> response = rest.postForEntity("/api/auth/login",
                Map.of("username", "nonexistent", "password", "wrong"), String.class);
        // Should return 401 from Spring Security (bad credentials), not 403
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bootstrappedAdminCanLoginAndReceiveAnOpaqueSession() throws Exception {
        bootstrapService.bootstrap();

        ResponseEntity<String> response = rest.postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "admin"), String.class);

        assertThat(response.getStatusCode())
                .as("admin login response body: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"token\"");

        String token = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response.getBody()).get("token").asText();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> protectedResponse = rest.exchange(
                "/api/contacts", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(protectedResponse.getStatusCode())
                .as("protected response body: %s", protectedResponse.getBody())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownEndpointReturns404() {
        ResponseEntity<String> response = rest.getForEntity("/not-a-real-endpoint", String.class);
        assertThat(response.getStatusCode())
                .as("unknown endpoint response body: %s", response.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
