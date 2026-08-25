package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataGateway;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.crmforlogistics.messagecentertest.wecom.WeComChatDataTransactionTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WeComChatDataTransactionTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class WeComChatDataTransactionIntegrationTest {

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
        registry.add("app.wecom-enabled", () -> "true");
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Autowired WeComChatDataStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventHub events;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table wecom_chatdata_cursor, wecom_chatdata_messages, messages, "
                + "conversations, contact_identities, contacts, channel_accounts cascade");
        UUID accountId = UUID.randomUUID();
        jdbc.update("insert into channel_accounts (id, channel_type, name, account_identifier, "
                        + "account_identifier_normalized, auth_status, encrypted_config) "
                        + "values (?, 'wecom', 'WeCom', ?, ?, 'active', '{}'::jsonb)",
                accountId, accountId.toString(), accountId.toString());
        reset(events);
    }

    @Test
    void commitsReferenceProjectionCursorAndOneEventTogether() {
        store.publishPage(key(), "next", List.of(decrypted("message-1", 100L)));

        assertThat(count("wecom_chatdata_messages")).isEqualTo(1);
        assertThat(count("contacts")).isEqualTo(1);
        assertThat(count("contact_identities")).isEqualTo(1);
        assertThat(count("conversations")).isEqualTo(1);
        assertThat(count("messages")).isEqualTo(1);
        assertThat(count("wecom_chatdata_cursor")).isEqualTo(1);
        verify(events).publish("message-new", "{}");
    }

    @Test
    void projectionFailureRollsBackReferenceAndDoesNotAdvanceCursorOrPublish() {
        assertThatThrownBy(() -> store.publishPage(
                key(), "next", List.of(decrypted("message-rollback", Long.MAX_VALUE))))
                .isInstanceOf(WeComChatDataException.class);

        assertThat(count("wecom_chatdata_messages")).isZero();
        assertThat(count("contacts")).isZero();
        assertThat(count("contact_identities")).isZero();
        assertThat(count("conversations")).isZero();
        assertThat(count("messages")).isZero();
        assertThat(count("wecom_chatdata_cursor")).isZero();
        verify(events, never()).publish("message-new", "{}");
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static WeComChatDataStore.SyncKey key() {
        return new WeComChatDataStore.SyncKey("installation", 1, "program", "ability");
    }

    private static WeComChatDataStore.DecryptedMessage decrypted(String messageId, long sendTime) {
        return new WeComChatDataStore.DecryptedMessage(new WeComChatDataGateway.EncryptedMessage(
                messageId,
                new WeComChatDataGateway.Party(1, "employee"),
                List.of(new WeComChatDataGateway.Party(2, "external")),
                "", sendTime, 1, "encrypted-key", 1), "secret");
    }
}
