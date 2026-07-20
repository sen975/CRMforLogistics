package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class AuthAndAccessIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5-bookworm");

    private static Database database;

    @BeforeAll
    static void openDatabase() throws Exception {
        Path password = Files.createTempFile("message-center-auth-it", ".txt");
        Files.writeString(password, POSTGRES.getPassword(), StandardCharsets.UTF_8);
        password.toFile().deleteOnExit();
        database = Database.open(new Config(Map.of(
                "DATABASE_URL", POSTGRES.getJdbcUrl(),
                "DATABASE_USER", POSTGRES.getUsername(),
                "DATABASE_PASSWORD_FILE", password.toString())));
        database.migrate();
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void sessionsRolesGrantsAndReadCursorsAreUserScoped() throws Exception {
        PasswordHasher hasher = PasswordHasher.argon2id();
        char[] hashInput = "correct-password".toCharArray();
        String encoded = hasher.hash(hashInput);
        assertTrue(encoded.startsWith("$argon2id$v=19$m=65536,t=3,p=1$"));
        assertTrue(allZero(hashInput));
        char[] verifyInput = "correct-password".toCharArray();
        assertTrue(hasher.verify(encoded, verifyInput));
        assertTrue(allZero(verifyInput));

        AuditService audit = new AuditService(database);
        JdbcAuthRepository auth = new JdbcAuthRepository(database);
        Clock now = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC);
        SessionService sessions = new SessionService(auth, hasher, audit, now, Duration.ofHours(8));
        assertTrue(sessions.bootstrapAdmin("owner", "owner-password".toCharArray()).created());
        assertFalse(sessions.bootstrapAdmin("ignored", "ignored-password".toCharArray()).created());

        UUID agent = auth.createUser(new UserDraft("agent", "Agent", "active", "agent"),
                hash(hasher, "correct-password"));
        UUID supervisor = auth.createUser(new UserDraft("supervisor", "Supervisor", "active", "supervisor"),
                hash(hasher, "correct-password"));
        UUID admin = auth.createUser(new UserDraft("admin", "Admin", "active", "admin"),
                hash(hasher, "correct-password"));
        auth.createUser(new UserDraft("locked", "Locked", "locked", "agent"),
                hash(hasher, "correct-password"));

        SessionService.AuthenticationException wrong = assertThrows(SessionService.AuthenticationException.class,
                () -> sessions.login("agent", "wrong-password".toCharArray()));
        assertEquals("INVALID_CREDENTIALS", wrong.code());
        assertThrows(SessionService.AuthenticationException.class,
                () -> sessions.login("locked", "correct-password".toCharArray()));

        IssuedSession issued = sessions.login("agent", "correct-password".toCharArray());
        assertEquals(agent, sessions.authenticate(issued.rawToken()).userId());
        assertFalse(storedTokenEqualsRaw(issued.rawToken()));
        sessions.logout(issued.rawToken());
        assertThrows(SessionService.AuthenticationException.class,
                () -> sessions.authenticate(issued.rawToken()));

        IssuedSession expiring = sessions.login("agent", "correct-password".toCharArray());
        SessionService future = new SessionService(auth, hasher, audit,
                Clock.fixed(now.instant().plus(Duration.ofHours(9)), ZoneOffset.UTC), Duration.ofHours(8));
        assertThrows(SessionService.AuthenticationException.class,
                () -> future.authenticate(expiring.rawToken()));

        UUID team = createTeam(supervisor);
        UUID assignedConversation = createConversation(agent, null);
        UUID teamConversation = createConversation(null, team);
        UUID sensitiveConversation = createConversation(supervisor, null);
        AccessControlService access = new AccessControlService(database, audit);
        assertTrue(access.canRead(agent, assignedConversation));
        assertFalse(access.canRead(agent, teamConversation));
        assertTrue(access.canRead(supervisor, teamConversation));
        assertFalse(access.canRead(admin, sensitiveConversation));
        access.grant(sensitiveConversation, admin, supervisor, "incident review");
        assertTrue(access.canRead(admin, sensitiveConversation));

        insertInbound(assignedConversation, true, "new inbound");
        insertInbound(assignedConversation, false, "historical import");
        assertEquals(1, access.unreadCount(agent, assignedConversation));
        access.markRead(agent, assignedConversation);
        assertEquals(0, access.unreadCount(agent, assignedConversation));
        access.grant(assignedConversation, admin, agent, "review");
        assertEquals(1, access.unreadCount(admin, assignedConversation));
        assertTrue(auditCount() >= 6);
    }

    private static String hash(PasswordHasher hasher, String value) {
        return hasher.hash(value.toCharArray());
    }

    private static UUID createTeam(UUID supervisor) throws Exception {
        UUID id = UUID.randomUUID();
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into teams (id,name,supervisor_id) values (?,?,?)")) {
                statement.setObject(1, id);
                statement.setString(2, "Support");
                statement.setObject(3, supervisor);
                statement.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private static UUID createConversation(UUID assignedUser, UUID assignedTeam) throws Exception {
        UUID account = UUID.randomUUID();
        UUID contact = UUID.randomUUID();
        UUID identity = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into contacts (id,display_name) values (?,?)")) {
                statement.setObject(1, contact);
                statement.setString(2, "Fixture");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into contact_identities
                        (id,contact_id,channel_type,identity_scope,identity_value,normalized_value)
                    values (?,?,'whatsapp','global',?,?)
                    """)) {
                statement.setObject(1, identity);
                statement.setObject(2, contact);
                statement.setString(3, identity.toString());
                statement.setString(4, identity.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into channel_accounts
                        (id,channel_type,name,account_identifier,account_identifier_normalized,encrypted_config)
                    values (?,'chatapp','Fixture',?,?,'{}'::jsonb)
                    """)) {
                statement.setObject(1, account);
                statement.setString(2, account.toString());
                statement.setString(3, account.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into conversations
                        (id,channel_account_id,contact_identity_id,assigned_user_id,assigned_team_id)
                    values (?,?,?,?,?)
                    """)) {
                statement.setObject(1, conversation);
                statement.setObject(2, account);
                statement.setObject(3, identity);
                statement.setObject(4, assignedUser);
                statement.setObject(5, assignedTeam);
                statement.executeUpdate();
            }
            return null;
        });
        return conversation;
    }

    private static void insertInbound(UUID conversation, boolean countsAsUnread, String body) throws Exception {
        database.transaction(connection -> {
            UUID account;
            long sequence;
            try (PreparedStatement statement = connection.prepareStatement("""
                    update conversations set next_ingest_sequence=next_ingest_sequence+1
                    where id=? returning channel_account_id,next_ingest_sequence
                    """)) {
                statement.setObject(1, conversation);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    account = rows.getObject(1, UUID.class);
                    sequence = rows.getLong(2);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into messages
                        (conversation_id,channel_account_id,direction,message_kind,body_text,
                         occurred_at,ingest_sequence,counts_as_unread,current_status,current_status_at)
                    values (?,?,'inbound','text',?,now(),?,?,'delivered',now())
                    """)) {
                statement.setObject(1, conversation);
                statement.setObject(2, account);
                statement.setString(3, body);
                statement.setLong(4, sequence);
                statement.setBoolean(5, countsAsUnread);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static boolean storedTokenEqualsRaw(String rawToken) throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select exists(select 1 from user_sessions where encode(token_hash,'base64')=?)")) {
                statement.setString(1, rawToken);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getBoolean(1);
                }
            }
        });
    }

    private static long auditCount() throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("select count(*) from audit_logs");
                 var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        });
    }

    private static boolean allZero(char[] value) {
        for (char character : value) if (character != '\0') return false;
        return true;
    }
}
