package com.crmforlogistics.messagecenter.service.wecom;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataGateway;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataCursorMapper;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@SpringBootTest(classes = WeComChatDataTransactionIntegrationTest.TestConfig.class)
@Testcontainers
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
        MapperFactoryBean<WeComChatDataMessageMapper> chatDataMessageMapper(SqlSessionFactory factory) {
            return mapper(factory, WeComChatDataMessageMapper.class);
        }

        @Bean
        MapperFactoryBean<WeComChatDataCursorMapper> chatDataCursorMapper(SqlSessionFactory factory) {
            return mapper(factory, WeComChatDataCursorMapper.class);
        }

        @Bean
        MapperFactoryBean<ChannelAccountMapper> channelAccountMapper(SqlSessionFactory factory) {
            return mapper(factory, ChannelAccountMapper.class);
        }

        @Bean
        MapperFactoryBean<ContactIdentityMapper> contactIdentityMapper(SqlSessionFactory factory) {
            return mapper(factory, ContactIdentityMapper.class);
        }

        @Bean
        MapperFactoryBean<ContactMapper> contactMapper(SqlSessionFactory factory) {
            return mapper(factory, ContactMapper.class);
        }

        @Bean
        MapperFactoryBean<ConversationMapper> conversationMapper(SqlSessionFactory factory) {
            return mapper(factory, ConversationMapper.class);
        }

        @Bean
        MapperFactoryBean<MessageMapper> messageMapper(SqlSessionFactory factory) {
            return mapper(factory, MessageMapper.class);
        }

        @Bean
        WeComCredentialProtector credentialProtector() {
            String key = Base64.getEncoder().encodeToString(new byte[32]);
            return new WeComCredentialProtector(CredentialCipher.fromBase64Key(key));
        }

        @Bean
        EventHub eventHub() {
            return mock(EventHub.class);
        }

        @Bean
        WeComMessageProjector projector(ChannelAccountMapper accounts,
                                        ContactIdentityMapper identities,
                                        ContactMapper contacts,
                                        ConversationMapper conversations,
                                        MessageMapper messages) {
            return new WeComMessageProjector(accounts, identities, contacts, conversations, messages);
        }

        @Bean
        WeComChatDataStore store(WeComChatDataMessageMapper messages,
                                 WeComChatDataCursorMapper cursors,
                                 WeComCredentialProtector protector,
                                 WeComMessageProjector projector,
                                 EventHub events) {
            return new WeComChatDataStore(messages, cursors, protector, projector, events);
        }

        private static <T> MapperFactoryBean<T> mapper(SqlSessionFactory factory, Class<T> type) {
            MapperFactoryBean<T> mapper = new MapperFactoryBean<>(type);
            mapper.setSqlSessionFactory(factory);
            return mapper;
        }
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
