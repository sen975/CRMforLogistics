package com.crmforlogistics.messagecenter;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ChatAppIdentityBindingMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    @Test
    void v17BackfillsUniqueChatAppAccountAndRejectsFutureInvalidScope() {
        String schema = "chatapp_identity_v17";
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target("16").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        UUID accountId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        jdbc.update("insert into " + schema + ".channel_accounts "
                        + "(id, channel_type, name, account_identifier, account_identifier_normalized, "
                        + "auth_status, encrypted_config) values (?, 'chatapp', 'Migration test', ?, ?, "
                        + "'active', '{}'::jsonb)",
                accountId, accountId.toString(), accountId.toString());
        jdbc.update("insert into " + schema + ".contacts (id, display_name) values (?, 'Migration contact')",
                contactId);
        jdbc.update("insert into " + schema + ".contact_identities "
                        + "(id, contact_id, channel_type, identity_scope, identity_value, normalized_value) "
                        + "values (?, ?, 'chatapp', 'phone', '+8613800000000', '8613800000000')",
                identityId, contactId);
        jdbc.update("insert into " + schema + ".conversations "
                        + "(id, channel_account_id, contact_identity_id) values (?, ?, ?)",
                conversationId, accountId, identityId);

        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();

        assertThat(jdbc.queryForObject("select identity_scope from " + schema
                        + ".contact_identities where id = ?", String.class, identityId))
                .isEqualTo(accountId.toString());

        UUID invalidContactId = UUID.randomUUID();
        jdbc.update("insert into " + schema + ".contacts (id, display_name) values (?, 'Invalid scope contact')",
                invalidContactId);
        assertThatThrownBy(() -> jdbc.update("insert into " + schema + ".contact_identities "
                        + "(contact_id, channel_type, identity_scope, identity_value, normalized_value) "
                        + "values (?, 'chatapp', 'phone', '+8613800000001', '8613800000001')",
                invalidContactId)).isInstanceOf(DataIntegrityViolationException.class);

        UUID emailContactId = UUID.randomUUID();
        jdbc.update("insert into " + schema + ".contacts (id, display_name) values (?, 'Email contact')",
                emailContactId);
        assertThat(jdbc.update("insert into " + schema + ".contact_identities "
                        + "(contact_id, channel_type, identity_scope, identity_value, normalized_value) "
                        + "values (?, 'email', 'global', 'test@example.com', 'test@example.com')",
                emailContactId)).isEqualTo(1);
    }
}
