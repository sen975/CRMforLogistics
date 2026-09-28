package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.EmailSubmissionEntity;
import com.crmforlogistics.messagecentertest.mapper.EmailSubmissionMapperTestConfiguration;
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

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = EmailSubmissionMapperTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class EmailSubmissionMapperSqlTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center").withUsername("test").withPassword("test");

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
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
    }

    @Autowired EmailSubmissionMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private UUID owner;
    private UUID otherOwner;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table email_submissions, users cascade");
        owner = insertUser("owner");
        otherOwner = insertUser("other");
    }

    @Test
    void stateTransitionsAreCasAndUnknownRowsRemainOwnerScoped() {
        EmailSubmissionEntity submission = createSubmission(owner);
        assertThat(mapper.insert(submission)).isEqualTo(1);
        submission.setStatus("SMTP_SENT");
        submission.setProviderMessageId("<provider-id>");
        assertThat(mapper.update(submission)).isEqualTo(1);
        submission.setStatus("SENT");
        assertThat(mapper.update(submission)).isEqualTo(1);
        assertThat(mapper.update(submission)).isZero();

        EmailSubmissionEntity expiredWriter = createSubmission(owner);
        assertThat(mapper.insert(expiredWriter)).isEqualTo(1);
        jdbc.update("update email_submissions set lease_expires_at=now()-interval '1 second' where id=?::uuid",
                expiredWriter.getId());
        expiredWriter.setStatus("SMTP_SENT");
        assertThat(mapper.renewLease(expiredWriter.getId(), expiredWriter.getLeaseToken())).isZero();
        assertThat(mapper.update(expiredWriter)).isZero();

        EmailSubmissionEntity unknown = createSubmission(owner);
        unknown.setStatus("UNKNOWN");
        assertThat(mapper.insert(unknown)).isEqualTo(1);
        assertThat(mapper.listUnknownByOwner(owner, 10)).extracting(EmailSubmissionEntity::getId)
                .containsExactly(unknown.getId());
        assertThat(mapper.listUnknownByOwner(otherOwner, 10)).isEmpty();
    }

    @Test
    void abandonedSubmissionsAreRecoveredPerOwnerAndInBoundedBatches() {
        EmailSubmissionEntity pending = createSubmission(owner);
        EmailSubmissionEntity secondPending = createSubmission(owner);
        EmailSubmissionEntity smtpSent = createSubmission(owner);
        EmailSubmissionEntity otherOwnerPending = createSubmission(otherOwner);
        smtpSent.setStatus("SMTP_SENT");
        assertThat(mapper.insert(pending)).isEqualTo(1);
        assertThat(mapper.insert(secondPending)).isEqualTo(1);
        assertThat(mapper.insert(smtpSent)).isEqualTo(1);
        assertThat(mapper.insert(otherOwnerPending)).isEqualTo(1);
        jdbc.update("update email_submissions set updated_at=now()-interval '3 minutes'");
        assertThat(mapper.renewLease(pending.getId(), pending.getLeaseToken())).isEqualTo(1);

        assertThat(mapper.markStaleSubmissionsUnknown(owner, 1)).isZero();
        assertThat(mapper.listUnknownByOwner(owner, 10)).isEmpty();
        jdbc.update("update email_submissions set lease_expires_at=now()-interval '1 second' " +
                "where id in (?::uuid, ?::uuid, ?::uuid)", pending.getId(), secondPending.getId(), smtpSent.getId());

        assertThat(mapper.markStaleSubmissionsUnknown(owner, 1)).isEqualTo(1);
        assertThat(mapper.listUnknownByOwner(owner, 10)).hasSize(1);
        assertThat(statusOf(otherOwnerPending.getId())).isEqualTo("PENDING");

        assertThat(mapper.markStaleSubmissionsUnknown(owner, 2)).isEqualTo(2);
        assertThat(mapper.listUnknownByOwner(owner, 10))
                .extracting(EmailSubmissionEntity::getStatus).containsOnly("UNKNOWN");
        assertThat(statusOf(otherOwnerPending.getId())).isEqualTo("PENDING");
        assertThat(mapper.markStaleSubmissionsUnknown(owner, 100)).isZero();
    }

    @Test
    void concurrentRecoveryClaimsAnExpiredSubmissionOnlyOnce() throws Exception {
        EmailSubmissionEntity expired = createSubmission(owner);
        assertThat(mapper.insert(expired)).isEqualTo(1);
        jdbc.update("update email_submissions set lease_expires_at=now()-interval '1 second' where id=?::uuid",
                expired.getId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> recoverAfter(start));
            var second = executor.submit(() -> recoverAfter(start));
            start.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(statusOf(expired.getId())).isEqualTo("UNKNOWN");
        } finally {
            executor.shutdownNow();
        }
    }

    private int recoverAfter(CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("recovery race did not start");
            return mapper.markStaleSubmissionsUnknown(owner, 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("recovery race interrupted", e);
        }
    }

    private String statusOf(UUID submissionId) {
        return jdbc.queryForObject("select status from email_submissions where id=?::uuid",
                String.class, submissionId);
    }

    private UUID insertUser(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) " +
                "values (?::uuid, ?, ?, 'x', ?)", id, name, name, name);
        return id;
    }

    private EmailSubmissionEntity createSubmission(UUID ownerId) {
        EmailSubmissionEntity entity = new EmailSubmissionEntity();
        entity.setId(UUID.randomUUID());
        entity.setLeaseToken(UUID.randomUUID());
        entity.setOwnerUserId(ownerId);
        entity.setRecipient("person@example.test");
        entity.setSubject("subject");
        entity.setStatus("PENDING");
        return entity;
    }
}
