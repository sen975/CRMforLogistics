package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastJobEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastRecipientEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastReconciliationEvidenceEntity;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = ChatAppBroadcastPersistenceIntegrationTest.TestConfig.class)
@Testcontainers
class ChatAppBroadcastPersistenceIntegrationTest {

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
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            MybatisPlusAutoConfiguration.class
    })
    static class TestConfig {
        @Bean
        MapperFactoryBean<ChatAppBroadcastMapper> broadcastMapper(SqlSessionFactory factory) {
            MapperFactoryBean<ChatAppBroadcastMapper> bean =
                    new MapperFactoryBean<>(ChatAppBroadcastMapper.class);
            bean.setSqlSessionFactory(factory);
            return bean;
        }

        @Bean
        MapperFactoryBean<ChatAppBroadcastJobMapper> jobMapper(SqlSessionFactory factory) {
            MapperFactoryBean<ChatAppBroadcastJobMapper> bean =
                    new MapperFactoryBean<>(ChatAppBroadcastJobMapper.class);
            bean.setSqlSessionFactory(factory);
            return bean;
        }

        @Bean
        MapperFactoryBean<ChatAppBroadcastRecipientMapper> recipientMapper(SqlSessionFactory factory) {
            MapperFactoryBean<ChatAppBroadcastRecipientMapper> bean =
                    new MapperFactoryBean<>(ChatAppBroadcastRecipientMapper.class);
            bean.setSqlSessionFactory(factory);
            return bean;
        }

        @Bean
        MapperFactoryBean<ChatAppBroadcastReconciliationEvidenceMapper> evidenceMapper(
                SqlSessionFactory factory) {
            MapperFactoryBean<ChatAppBroadcastReconciliationEvidenceMapper> bean =
                    new MapperFactoryBean<>(ChatAppBroadcastReconciliationEvidenceMapper.class);
            bean.setSqlSessionFactory(factory);
            return bean;
        }

        @Bean
        MapperFactoryBean<ContactIdentityMapper> contactIdentityMapper(SqlSessionFactory factory) {
            MapperFactoryBean<ContactIdentityMapper> bean =
                    new MapperFactoryBean<>(ContactIdentityMapper.class);
            bean.setSqlSessionFactory(factory);
            return bean;
        }
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Autowired ChatAppBroadcastMapper broadcastMapper;
    @Autowired ChatAppBroadcastJobMapper jobMapper;
    @Autowired ChatAppBroadcastRecipientMapper recipientMapper;
    @Autowired ChatAppBroadcastReconciliationEvidenceMapper evidenceMapper;
    @Autowired ContactIdentityMapper contactIdentityMapper;
    @Autowired JdbcTemplate jdbc;

    private UUID accountId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table chatapp_broadcast_jobs, chatapp_broadcast_recipients, "
                + "chatapp_broadcasts, channel_accounts, user_roles, users cascade");
        actorId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', 'Actor')", actorId, actorId.toString(), actorId.toString());
        jdbc.update("insert into channel_accounts (id, channel_type, name, account_identifier, "
                        + "account_identifier_normalized, auth_status, encrypted_config) "
                        + "values (?, 'chatapp', 'ChatApp', '60111111111', '60111111111', "
                        + "'active', '{}'::jsonb)", accountId);
    }

    @Test
    void concurrentIdempotentInsertCreatesExactlyOneBroadcast() throws Exception {
        ChatAppBroadcastEntity first = broadcast("same-request");
        ChatAppBroadcastEntity second = broadcast("same-request");

        List<Integer> results = race(
                () -> broadcastMapper.insertIfAbsent(first),
                () -> broadcastMapper.insertIfAbsent(second));

        assertThat(results).containsExactlyInAnyOrder(0, 1);
        assertThat(jdbc.queryForObject("select count(*) from chatapp_broadcasts", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void accountAccessUsesContactVisibilityAndAdminRole() {
        UUID foreignUserId = UUID.randomUUID();
        UUID otherAccountId = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                        + "values (?, ?, ?, 'hash', 'Foreign')",
                foreignUserId, foreignUserId.toString(), foreignUserId.toString());
        jdbc.update("insert into channel_accounts (id, channel_type, name, account_identifier, "
                        + "account_identifier_normalized, auth_status, encrypted_config) "
                        + "values (?, 'chatapp', 'Other', '60122222222', '60122222222', "
                        + "'active', '{}'::jsonb)", otherAccountId);
        UUID contactId = UUID.randomUUID();
        jdbc.update("insert into contacts (id, display_name, created_by) values (?, 'Owned', ?)",
                contactId, actorId);
        jdbc.update("insert into contact_identities (id, contact_id, channel_type, identity_scope, "
                        + "identity_value, normalized_value) values (?, ?, 'chatapp', ?, "
                        + "'60133333333', '60133333333')",
                UUID.randomUUID(), contactId, accountId.toString());

        assertThat(contactIdentityMapper.canAccessChatAppAccount(accountId, actorId)).isTrue();
        assertThat(contactIdentityMapper.canAccessChatAppAccount(otherAccountId, actorId)).isFalse();

        jdbc.update("insert into user_roles (user_id, role_id) select ?, id from roles where code = 'admin'",
                foreignUserId);
        assertThat(contactIdentityMapper.canAccessChatAppAccount(otherAccountId, foreignUserId)).isTrue();
    }

    @Test
    void concurrentClaimNeverLeasesTheSameJobTwice() throws Exception {
        ChatAppBroadcastEntity broadcast = broadcast("claim-request");
        assertThat(broadcastMapper.insertIfAbsent(broadcast)).isEqualTo(1);
        UUID jobId = insertJob(broadcast.getId(), "RECONCILE", "PENDING", null);
        Instant now = Instant.parse("2026-08-14T08:00:00Z");

        List<List<ChatAppBroadcastJobEntity>> results = race(
                () -> jobMapper.claimDue("worker-a", now, now.plusSeconds(60), 1),
                () -> jobMapper.claimDue("worker-b", now, now.plusSeconds(60), 1));

        List<UUID> claimed = results.stream().flatMap(List::stream)
                .map(ChatAppBroadcastJobEntity::getId).toList();
        assertThat(claimed).containsExactly(jobId);
    }

    @Test
    void expiredReconcileLeaseIsClaimedOnceAndExpiredSubmissionIsRecoveredOnce() throws Exception {
        Instant now = Instant.parse("2026-08-14T08:00:00Z");
        ChatAppBroadcastEntity reconcileBroadcast = broadcast("expired-reconcile");
        ChatAppBroadcastEntity submitBroadcast = broadcast("expired-submit");
        assertThat(broadcastMapper.insertIfAbsent(reconcileBroadcast)).isEqualTo(1);
        assertThat(broadcastMapper.insertIfAbsent(submitBroadcast)).isEqualTo(1);
        UUID reconcileJobId = insertJob(
                reconcileBroadcast.getId(), "RECONCILE", "PROCESSING", now.minusSeconds(1));
        insertJob(submitBroadcast.getId(), "SUBMIT", "PROCESSING", now.minusSeconds(1));

        List<List<ChatAppBroadcastJobEntity>> claimed = race(
                () -> jobMapper.claimDue("worker-a", now, now.plusSeconds(60), 1),
                () -> jobMapper.claimDue("worker-b", now, now.plusSeconds(60), 1));
        assertThat(claimed.stream().flatMap(List::stream).map(ChatAppBroadcastJobEntity::getId))
                .containsExactly(reconcileJobId);

        List<List<UUID>> recovered = race(
                () -> jobMapper.recoverExpiredSubmissions(now),
                () -> jobMapper.recoverExpiredSubmissions(now));
        assertThat(recovered.stream().flatMap(List::stream))
                .containsExactly(submitBroadcast.getId());
        assertThat(jdbc.queryForObject(
                "select status from chatapp_broadcast_jobs where broadcast_id = ?",
                String.class, submitBroadcast.getId())).isEqualTo("DEAD");
    }

    @Test
    void recipientLinksOneMessageAndEvidenceRowsAreIdempotent() {
        ChatAppBroadcastEntity broadcast = broadcast("projection-request");
        broadcast.setTemplateBodySnapshot("订单 $(order) 已发货");
        assertThat(broadcastMapper.insertIfAbsent(broadcast)).isEqualTo(1);
        UUID broadcastId = broadcast.getId();
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-17T08:00:00Z");
        jdbc.update("insert into contacts (id, display_name, created_by) values (?, 'Recipient', ?)",
                contactId, actorId);
        jdbc.update("insert into contact_identities (id, contact_id, channel_type, identity_scope, "
                        + "identity_value, normalized_value) values (?, ?, 'chatapp', ?, "
                        + "'60111111111', '60111111111')",
                identityId, contactId, accountId.toString());
        jdbc.update("insert into conversations (id, channel_account_id, contact_identity_id, "
                        + "next_ingest_sequence) values (?, ?, ?, 1)",
                conversationId, accountId, identityId);
        jdbc.update("insert into messages (id, conversation_id, channel_account_id, client_request_id, "
                        + "direction, message_kind, body_text, occurred_at, ingest_sequence, counts_as_unread, "
                        + "current_status, current_status_at) values (?, ?, ?, ?, 'outbound', 'template', "
                        + "'订单 SO-1 已发货', ?, 1, false, 'processing', ?)",
                messageId, conversationId, accountId,
                "broadcast:" + broadcastId + ":recipient:" + recipientId,
                Timestamp.from(now), Timestamp.from(now));
        ChatAppBroadcastRecipientEntity recipient = new ChatAppBroadcastRecipientEntity();
        recipient.setId(recipientId);
        recipient.setBroadcastId(broadcastId);
        recipient.setContactId(contactId);
        recipient.setContactIdentityId(identityId);
        recipient.setRecipientNameSnapshot("Recipient");
        recipient.setRecipientNumberSnapshot("60111111111");
        recipient.setTemplateParamsJsonb("{\"order\":\"SO-1\"}");
        recipient.setStatus("PROCESSING");
        recipient.setCreatedAt(now);
        recipient.setUpdatedAt(now);
        recipient.setVersion(0L);
        recipientMapper.insert(recipient);
        UUID jobId = insertJob(broadcastId, "RECONCILE", "PROCESSING", now.plusSeconds(60));
        assertThat(recipientMapper.linkMessageIfAbsent(recipientId, messageId, Instant.now())).isEqualTo(1);

        ChatAppBroadcastReconciliationEvidenceEntity envelope =
                new ChatAppBroadcastReconciliationEvidenceEntity();
        envelope.setId(UUID.randomUUID());
        envelope.setBroadcastId(broadcastId);
        envelope.setJobId(jobId);
        envelope.setProviderRequestId("request-1");
        envelope.setPageNumber(1);
        envelope.setRowNumber(0);
        envelope.setDiagnosticCode("CHATAPP_PROVIDER_SUCCESS_FLAG_MISSING");
        envelope.setCreatedAt(now);
        ChatAppBroadcastReconciliationEvidenceEntity unmatched =
                new ChatAppBroadcastReconciliationEvidenceEntity();
        unmatched.setId(UUID.randomUUID());
        unmatched.setBroadcastId(broadcastId);
        unmatched.setJobId(jobId);
        unmatched.setProviderRequestId("request-1");
        unmatched.setPageNumber(1);
        unmatched.setRowNumber(1);
        unmatched.setUserNumber("60122222222");
        unmatched.setDiagnosticCode("CHATAPP_BROADCAST_RECIPIENT_UNMATCHED");
        unmatched.setCreatedAt(now);
        assertThat(evidenceMapper.upsert(envelope)).isEqualTo(1);
        envelope.setDiagnosticCode("CHATAPP_PROVIDER_SUCCESS_FLAG_CONFLICT");
        assertThat(evidenceMapper.upsert(envelope)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from chatapp_broadcast_reconciliation_evidence "
                        + "where job_id = ? and page_number = 1 and row_number = 0",
                Integer.class, jobId)).isEqualTo(1);
        assertThat(evidenceMapper.findLatest(broadcastId, 10).stream()
                .filter(item -> item.getRowNumber() == 0).findFirst().orElseThrow().getDiagnosticCode())
                .isEqualTo("CHATAPP_PROVIDER_SUCCESS_FLAG_CONFLICT");
        assertThat(evidenceMapper.upsert(unmatched)).isEqualTo(1);
        assertThat(evidenceMapper.countUnmatched(broadcastId)).isEqualTo(1);

        UUID secondIdentityId = UUID.randomUUID();
        jdbc.update("insert into contact_identities (id, contact_id, channel_type, identity_scope, "
                        + "identity_value, normalized_value) values (?, ?, 'chatapp', ?, "
                        + "'60133333333', '60133333333')",
                secondIdentityId, contactId, accountId.toString());
        ChatAppBroadcastRecipientEntity second = new ChatAppBroadcastRecipientEntity();
        second.setId(UUID.randomUUID());
        second.setBroadcastId(broadcastId);
        second.setContactId(contactId);
        second.setContactIdentityId(secondIdentityId);
        second.setRecipientNameSnapshot("Recipient 2");
        second.setRecipientNumberSnapshot("60133333333");
        second.setTemplateParamsJsonb("{\"order\":\"SO-2\"}");
        second.setStatus("PROCESSING");
        second.setCreatedAt(now.plusMillis(1));
        second.setUpdatedAt(now);
        second.setVersion(0L);
        recipientMapper.insert(second);
        assertThatThrownBy(() -> recipientMapper.linkMessageIfAbsent(
                second.getId(), messageId, Instant.now()))
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
    }

    private ChatAppBroadcastEntity broadcast(String requestId) {
        Instant now = Instant.parse("2026-08-14T07:59:00Z");
        ChatAppBroadcastEntity entity = new ChatAppBroadcastEntity();
        entity.setId(UUID.randomUUID());
        entity.setChannelAccountId(accountId);
        entity.setName("Broadcast");
        entity.setTemplateCode("shipping_notice");
        entity.setTemplateName("Shipping Notice");
        entity.setLanguageCode("zh_CN");
        entity.setRecipientCount(1);
        entity.setSuccessCount(0);
        entity.setFailedCount(0);
        entity.setProcessingCount(1);
        entity.setStatus("QUEUED");
        entity.setClientRequestId(requestId);
        entity.setRequestFingerprint("a".repeat(64));
        entity.setCreatedByUserId(actorId);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setVersion(0L);
        return entity;
    }

    private UUID insertJob(UUID broadcastId, String type, String status, Instant leaseExpiresAt) {
        UUID id = UUID.randomUUID();
        Instant due = Instant.parse("2026-08-14T07:59:00Z");
        boolean leased = leaseExpiresAt != null;
        jdbc.update("insert into chatapp_broadcast_jobs (id, broadcast_id, job_type, status, "
                        + "attempt_count, max_attempts, next_attempt_at, lease_id, lease_worker_id, "
                        + "lease_expires_at, created_at, updated_at) values (?, ?, ?, ?, 0, 10, ?, ?, ?, ?, ?, ?)",
                id, broadcastId, type, status, Timestamp.from(due),
                leased ? "old-lease" : null, leased ? "old-worker" : null,
                leased ? Timestamp.from(leaseExpiresAt) : null, Timestamp.from(due), Timestamp.from(due));
        return id;
    }

    private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<T>> tasks = List.of(
                    () -> { ready.countDown(); start.await(); return first.call(); },
                    () -> { ready.countDown(); start.await(); return second.call(); });
            var futures = new ArrayList<java.util.concurrent.Future<T>>();
            tasks.forEach(task -> futures.add(executor.submit(task)));
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get());
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
