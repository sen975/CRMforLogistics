package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.WeComUserNotificationEntity;
import com.crmforlogistics.messagecentertest.mapper.WeComUserNotificationPersistenceTestConfiguration;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WeComUserNotificationPersistenceTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class WeComUserNotificationPersistenceIntegrationTest {

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

    @Autowired WeComUserNotificationMapper notificationMapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table wecom_user_notifications");
    }

    @Test
    void upsertMergesIntoThePendingRowWithoutExtendingTheWindow() {
        UUID conversationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Instant firstSendAfter = Instant.parse("2026-09-10T10:01:30Z");
        Instant firstMessageAt = Instant.parse("2026-09-10T10:00:00Z");
        UUID firstId = insertPending(conversationId, recipientId, "zhangsan",
                firstSendAfter, firstMessageAt, "第一条");

        // A second inbound message in the same window carries later timestamps and a
        // different bound id; the conflict target must keep the first row's window.
        WeComUserNotificationEntity second = entity(UUID.randomUUID(), conversationId, recipientId,
                "lisi", Instant.parse("2026-09-10T10:03:00Z"),
                Instant.parse("2026-09-10T10:00:40Z"), "第二条");
        assertThat(notificationMapper.upsertPending(second)).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "select id, message_count, recipient_wecom_user_id, first_message_at, send_after, "
                        + "status from wecom_user_notifications "
                        + "where conversation_id = ? and recipient_user_id = ?",
                conversationId, recipientId);
        assertThat(row.get("id")).isEqualTo(firstId);
        assertThat(row.get("message_count")).isEqualTo(2);
        assertThat(row.get("recipient_wecom_user_id")).isEqualTo("zhangsan");
        assertThat(((Timestamp) row.get("first_message_at")).toInstant()).isEqualTo(firstMessageAt);
        assertThat(((Timestamp) row.get("send_after")).toInstant()).isEqualTo(firstSendAfter);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "select count(*) from wecom_user_notifications", Integer.class)).isEqualTo(1);
    }

    @Test
    void dueScanReturnsOnlyPendingRowsWhoseSendAfterHasPassedOrderedBySendAfter() {
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        UUID conversationId = UUID.randomUUID();
        UUID oldest = insertPending(conversationId, UUID.randomUUID(), "u",
                now.minusSeconds(10), now.minusSeconds(30), "oldest");
        UUID recent = insertPending(conversationId, UUID.randomUUID(), "u",
                now.minusSeconds(5), now.minusSeconds(20), "recent");
        insertPending(conversationId, UUID.randomUUID(), "u", now.plusSeconds(5), now, "future");
        UUID sent = insertPending(conversationId, UUID.randomUUID(), "u",
                now.minusSeconds(20), now.minusSeconds(60), "sent");
        jdbc.update("update wecom_user_notifications set status = 'SENT' where id = ?", sent);

        List<WeComUserNotificationEntity> due = notificationMapper.listDue(now, 10);

        assertThat(due).extracting(WeComUserNotificationEntity::getId)
                .containsExactly(oldest, recent);
        assertThat(notificationMapper.listDue(now, 1))
                .extracting(WeComUserNotificationEntity::getId)
                .containsExactly(oldest);
    }

    @Test
    void claimAndTerminalTransitionsOnlyApplyFromTheExpectedStatus() {
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        UUID claimed = insertPending(UUID.randomUUID(), UUID.randomUUID(), "u", now, now, "x");
        UUID pending = insertPending(UUID.randomUUID(), UUID.randomUUID(), "u", now, now, "y");

        assertThat(notificationMapper.claim(claimed)).isEqualTo(1);
        assertThat(statusOf(claimed)).isEqualTo("SENDING");
        assertThat(notificationMapper.claim(claimed)).isZero();
        assertThat(notificationMapper.markSent(pending, now)).isZero();
        assertThat(notificationMapper.markSent(claimed, now)).isEqualTo(1);
        assertThat(statusOf(claimed)).isEqualTo("SENT");
    }

    @Test
    void concurrentClaimOfOnePendingRowHasExactlyOneWinner() throws Exception {
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        UUID id = insertPending(UUID.randomUUID(), UUID.randomUUID(), "u", now, now, "race");

        List<Integer> results = race(
                () -> notificationMapper.claim(id),
                () -> notificationMapper.claim(id));

        assertThat(results).containsExactlyInAnyOrder(0, 1);
        assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("SENDING");
    }

    @Test
    void recoverStuckReturnsOnlyStaleSendingRowsToPending() {
        Instant wallNow = Instant.now();
        UUID stale = insertPending(UUID.randomUUID(), UUID.randomUUID(), "u",
                wallNow, wallNow, "stale");
        UUID fresh = insertPending(UUID.randomUUID(), UUID.randomUUID(), "u",
                wallNow, wallNow, "fresh");
        assertThat(notificationMapper.claim(stale)).isEqualTo(1);
        assertThat(notificationMapper.claim(fresh)).isEqualTo(1);
        jdbc.update("update wecom_user_notifications set updated_at = ? where id = ?",
                Timestamp.from(wallNow.minusSeconds(120)), stale);
        jdbc.update("update wecom_user_notifications set updated_at = ? where id = ?",
                Timestamp.from(wallNow.minusSeconds(5)), fresh);

        List<UUID> recovered = notificationMapper.recoverStuck(wallNow.minusSeconds(60));

        assertThat(recovered).containsExactly(stale);
        assertThat(statusOf(stale)).isEqualTo("PENDING");
        assertThat(statusOf(fresh)).isEqualTo("SENDING");
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject(
                "select status from wecom_user_notifications where id = ?", String.class, id);
    }

    private UUID insertPending(UUID conversationId, UUID recipientUserId, String wecomUserId,
                               Instant sendAfter, Instant firstMessageAt, String preview) {
        UUID id = UUID.randomUUID();
        assertThat(notificationMapper.upsertPending(entity(id, conversationId, recipientUserId,
                wecomUserId, sendAfter, firstMessageAt, preview))).isEqualTo(1);
        return id;
    }

    private static WeComUserNotificationEntity entity(UUID id, UUID conversationId,
                                                      UUID recipientUserId, String wecomUserId,
                                                      Instant sendAfter, Instant firstMessageAt,
                                                      String preview) {
        WeComUserNotificationEntity row = new WeComUserNotificationEntity();
        row.setId(id);
        row.setConversationId(conversationId);
        row.setChannelAccountId(UUID.randomUUID());
        row.setRecipientUserId(recipientUserId);
        row.setRecipientWecomUserId(wecomUserId);
        row.setAuthCorpId("corp-1");
        row.setAgentId("1000002");
        row.setChannelType("chatapp");
        row.setContactLabel("张三");
        row.setMessageCount(1);
        row.setLastPreview(preview);
        row.setFirstMessageAt(firstMessageAt);
        row.setSendAfter(sendAfter);
        return row;
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
