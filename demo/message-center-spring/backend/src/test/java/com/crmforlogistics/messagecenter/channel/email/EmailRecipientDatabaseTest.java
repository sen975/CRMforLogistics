package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.EmailSubmissionMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = EmailSubmissionMapperTestConfiguration.class)
@Testcontainers
class EmailRecipientDatabaseTest {
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

    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelAccountMapper accounts;
    @Autowired ContactIdentityMapper identities;
    @Autowired ConversationMapper conversations;
    @Autowired MessageMapper messages;
    @Autowired EmailSubmissionMapper submissions;
    @Autowired PlatformTransactionManager transactions;

    private UUID owner;
    private UUID otherOwner;
    private UUID account;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table email_submissions, users cascade");
        owner = insertUser("owner");
        otherOwner = insertUser("other");
        account = insertAccount(owner);
    }

    @Test
    void onlyALiveEmailIdentityInTheSelectedAccountScopeIsReused() {
        UUID contact = insertContact(owner, "active");
        UUID identity = insertIdentity(contact, account, "email", "Person@Example.test");
        // 命中：地址已归一化、scope 是本邮箱账号、身份与联系人都还活着。
        // 归属（这个联系人是谁的）不再参与判定 —— 发送侧契约是「只要有个邮箱地址就能发」。
        assertThat(identities.findSendableEmailRecipient(account, "person@example.test"))
                .get().extracting(row -> row.getId()).isEqualTo(identity);
        // scope 指向别的邮箱账号 ⇒ 不复用（调用方会退化成新建一条属于本账号的孤立身份）。
        assertThat(identities.findSendableEmailRecipient(insertAccount(otherOwner), "person@example.test"))
                .isEmpty();

        // 三种失效形态都不得被复用：身份被删、联系人被删、联系人被合并。
        jdbc.update("update contact_identities set deleted_at=now() where id=?::uuid", identity);
        assertThat(identities.findSendableEmailRecipient(account, "person@example.test")).isEmpty();
        jdbc.update("update contact_identities set deleted_at=null where id=?::uuid", identity);
        jdbc.update("update contacts set deleted_at=now() where id=?::uuid", contact);
        assertThat(identities.findSendableEmailRecipient(account, "person@example.test")).isEmpty();
        jdbc.update("update contacts set deleted_at=null where id=?::uuid", contact);
        UUID mergedInto = insertContact(owner, "active");
        jdbc.update("update contacts set status='merged', merged_to_id=?::uuid where id=?::uuid", mergedInto, contact);
        assertThat(identities.findSendableEmailRecipient(account, "person@example.test")).isEmpty();
    }

    @Test
    void historicalLiteralEmailScopeIsStillReusable() {
        // 老版本 EmailSyncService 把 identity_scope 写成了字面量 'email'；
        // 库里 13 条 email 身份里 10 条是这个形态，不兼容就等于所有人发不出邮件。
        UUID contact = insertContact(owner, "active");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contact_identities(id, contact_id, channel_type, identity_scope, "
                        + "identity_value, normalized_value) values (?::uuid, ?::uuid, 'email', 'email', ?, ?)",
                id, contact, "legacy@example.test", "legacy@example.test");

        assertThat(identities.findSendableEmailRecipient(account, "legacy@example.test"))
                .get().extracting(row -> row.getId()).isEqualTo(id);
    }

    @Test
    void unknownForeignAndOrphanedRecipientsAreSentAsOrphanIdentities() throws Exception {
        UUID otherContact = insertContact(otherOwner, "active");
        UUID foreignAccount = insertAccount(otherOwner);
        insertIdentity(otherContact, account, "email", "foreign@example.test");
        insertIdentity(null, account, "email", "orphan@example.test");
        insertIdentity(insertContact(owner, "active"), account, "phone", "phone@example.test");
        insertIdentity(insertContact(owner, "active"), foreignAccount, "email", "scope@example.test");

        AtomicInteger smtpCalls = new AtomicInteger();
        EmailSendService service = service(smtpCalls);
        for (String recipient : List.of("unknown@example.test", "foreign@example.test",
                "orphan@example.test", "phone@example.test", "scope@example.test")) {
            assertThat(service.send(owner, recipient, "subject", "body").status()).isEqualTo("sent");
        }

        // 五个收件人全部真的出去了，一个都没被归属判定挡住。
        assertThat(smtpCalls).hasValue(5);
        assertThat(jdbc.queryForObject("select count(*) from email_submissions", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from conversations", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from messages", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from attachments", Integer.class)).isZero();

        // 原有 4 条身份照旧；unknown / phone(只有 phone 身份) / scope(在别人账号下) 各补一条孤立身份。
        assertThat(jdbc.queryForObject("select count(*) from contact_identities", Integer.class)).isEqualTo(7);
        // 关键：补身份**不等于**往通讯录里加人 —— 全程没有创建任何 contact。
        assertThat(jdbc.queryForObject("select count(*) from contact_identities where contact_id is null",
                Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from contacts", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from contact_identities where contact_id is not null",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void existingContactIsCommittedBeforeSmtpThenFinalizedWithoutCreatingAnIdentity() throws Exception {
        UUID contact = insertContact(owner, "active");
        UUID identity = insertIdentity(contact, account, "email", "person@example.test");
        AtomicInteger smtpCalls = new AtomicInteger();
        AppConfig config = smtpConfig();
        EmailSendService service = new EmailSendService(config, messages, conversations, accounts,
                identities, null, null, null, submissions,
                (session, host, port, user, password, mime) -> {
                    smtpCalls.incrementAndGet();
                    assertThat(jdbc.queryForObject("select status from email_submissions", String.class))
                            .isEqualTo("PENDING");
                    assertThat(jdbc.queryForObject("select recipient from email_submissions", String.class))
                            .isEqualTo("person@example.test");
                    assertThat(jdbc.queryForObject("select current_status from messages", String.class))
                            .isEqualTo("pending");
                    assertThat(jdbc.queryForObject("select body_text from messages", String.class))
                            .isEqualTo("body");
                    assertThat(jdbc.queryForObject("select subject from messages", String.class))
                            .isEqualTo("subject");
                    assertThat(jdbc.queryForObject("select provider_message_id from messages", String.class))
                            .isEqualTo(mime.getMessageID());
                    assertThat(jdbc.queryForObject("select counts_as_unread from messages", Boolean.class))
                            .isFalse();
                    assertThat(jdbc.queryForObject("select contact_identity_id from conversations", UUID.class))
                            .isEqualTo(identity);
                }, null, new org.springframework.transaction.support.TransactionTemplate(transactions));

        EmailSendService.SendResult result = service.send(owner, "Person@Example.test", "subject", "body");

        assertThat(result.status()).isEqualTo("sent");
        assertThat(result.to()).isEqualTo("person@example.test");
        assertThat(smtpCalls).hasValue(1);
        assertThat(jdbc.queryForObject("select status from email_submissions", String.class)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select current_status from messages", String.class)).isEqualTo("sent");
        assertThat(jdbc.queryForObject("select count(*) from contact_identities", Integer.class)).isEqualTo(1);
    }

    private EmailSendService service(AtomicInteger smtpCalls) {
        return new EmailSendService(smtpConfig(), messages, conversations, accounts,
                identities, null, null, null, submissions,
                (session, host, port, user, password, mime) -> smtpCalls.incrementAndGet(),
                null, new org.springframework.transaction.support.TransactionTemplate(transactions));
    }

    private AppConfig smtpConfig() {
        AppConfig config = mock(AppConfig.class);
        when(config.smtpHost()).thenReturn("smtp.example.test");
        when(config.smtpPort()).thenReturn("465");
        when(config.smtpUser()).thenReturn("owner@example.test");
        when(config.smtpPassword()).thenReturn("secret");
        when(config.mailFrom()).thenReturn("owner@example.test");
        return config;
    }

    private UUID insertUser(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users(id, username, username_normalized, password_hash, display_name) "
                + "values (?::uuid, ?, ?, 'x', ?)", id, name, name, name);
        return id;
    }

    private UUID insertAccount(UUID ownerId) {
        UUID id = UUID.randomUUID();
        String address = id + "@example.test";
        jdbc.update("insert into channel_accounts(id, owner_user_id, channel_type, name, account_identifier, "
                + "account_identifier_normalized, auth_status, encrypted_config) "
                + "values (?::uuid, ?::uuid, 'email', 'account', ?, ?, 'active', '{}'::jsonb)",
                id, ownerId, address, address);
        return id;
    }

    private UUID insertContact(UUID ownerId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contacts(id, owner_user_id, display_name, status) "
                + "values (?::uuid, ?::uuid, 'Person', ?)", id, ownerId, status);
        return id;
    }

    private UUID insertIdentity(UUID contactId, UUID accountId, String channel, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contact_identities(id, contact_id, channel_type, identity_scope, "
                + "identity_value, normalized_value) values (?::uuid, ?::uuid, ?, ?, ?, ?)",
                id, contactId, channel, accountId.toString(), email, email.toLowerCase(java.util.Locale.ROOT));
        return id;
    }
}
