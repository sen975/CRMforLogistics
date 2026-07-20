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
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class AuthAndAccessIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5-bookworm");

    private static Database database;
    private static Config databaseConfig;

    @BeforeAll
    static void openDatabase() throws Exception {
        Path password = Files.createTempFile("message-center-auth-it", ".txt");
        Files.writeString(password, POSTGRES.getPassword(), StandardCharsets.UTF_8);
        password.toFile().deleteOnExit();
        databaseConfig = new Config(Map.of(
                "DATABASE_URL", POSTGRES.getJdbcUrl(),
                "DATABASE_USER", POSTGRES.getUsername(),
                "DATABASE_PASSWORD_FILE", password.toString()));
        database = Database.open(databaseConfig);
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
        char[] invalidHashInput = "correct-password".toCharArray();
        assertFalse(hasher.verify(null, invalidHashInput));
        assertTrue(allZero(invalidHashInput));

        AuditService audit = new AuditService(database);
        JdbcAuthRepository auth = new JdbcAuthRepository(database);
        Clock now = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC);
        SessionService sessions = new SessionService(auth, hasher, audit, now, Duration.ofHours(8));
        AtomicInteger passwordReads = new AtomicInteger();
        assertTrue(sessions.bootstrapAdmin("owner", () -> {
            passwordReads.incrementAndGet();
            return "owner-password".toCharArray();
        }).created());
        assertFalse(sessions.bootstrapAdmin("ignored", () -> {
            throw new AssertionError("password must not be read when users already exist");
        }).created());
        assertFalse(sessions.bootstrapAdmin("", () -> {
            throw new AssertionError("password must not be read before the existing-user no-op");
        }).created());
        assertEquals(1, passwordReads.get());
        Config missingBootstrapSecret = new Config(Map.of(
                "DATABASE_URL", databaseConfig.databaseUrl(),
                "DATABASE_USER", databaseConfig.databaseUser(),
                "DATABASE_PASSWORD_FILE", databaseConfig.databasePasswordFile().toString(),
                "BOOTSTRAP_ADMIN_USERNAME", "ignored-cli-owner",
                "BOOTSTRAP_ADMIN_PASSWORD_FILE", "/path/that/does/not/exist"));
        assertFalse(App.bootstrapAdmin(missingBootstrapSecret).created());

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
        char[] invalidUsernamePassword = "correct-password".toCharArray();
        assertThrows(IllegalArgumentException.class, () -> sessions.login(null, invalidUsernamePassword));
        assertTrue(allZero(invalidUsernamePassword));
        assertThrows(SessionService.AuthenticationException.class,
                () -> sessions.login("locked", "correct-password".toCharArray()));

        IssuedSession issued = sessions.login("agent", "correct-password".toCharArray());
        assertEquals(agent, sessions.authenticate(issued.rawToken()).userId());
        assertFalse(storedTokenEqualsRaw(issued.rawToken()));
        sessions.logout(issued.rawToken());
        assertThrows(SessionService.AuthenticationException.class,
                () -> sessions.authenticate(issued.rawToken()));
        long successfulLogouts = auditActionResultCount("auth.logout", "success");
        assertThrows(SessionService.AuthenticationException.class,
                () -> sessions.logout(issued.rawToken()));
        assertEquals(successfulLogouts, auditActionResultCount("auth.logout", "success"));
        assertTrue(auditActionResultCount("auth.logout", "denied") >= 1);

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
        UUID sensitiveMessage = insertInbound(sensitiveConversation, true, "sensitive inbound");
        UUID sensitiveAttachment = createAttachment(sensitiveMessage);
        assertThrows(AccessControlService.AccessDeniedException.class,
                () -> access.requireSensitiveRead(admin, sensitiveConversation, "message", UUID.randomUUID()));
        assertThrows(AccessControlService.AccessDeniedException.class,
                () -> access.requireSensitiveRead(admin, sensitiveConversation, "attachment", UUID.randomUUID()));
        access.requireSensitiveRead(admin, sensitiveConversation, "message", sensitiveMessage);
        access.requireSensitiveRead(admin, sensitiveConversation, "attachment", sensitiveAttachment);
        access.assign(sensitiveConversation, admin, null, supervisor);

        insertInbound(assignedConversation, true, "new inbound");
        insertInbound(assignedConversation, false, "historical import");
        assertEquals(1, access.unreadCount(agent, assignedConversation));
        access.markRead(agent, assignedConversation);
        assertEquals(0, access.unreadCount(agent, assignedConversation));
        forceReadSequence(agent, assignedConversation, 999);
        assertEquals(999, access.markRead(agent, assignedConversation).lastReadSequence());
        access.grant(assignedConversation, admin, agent, "review");
        assertEquals(1, access.unreadCount(admin, assignedConversation));
        audit.record(agent, "test.redaction", "user", agent, Map.of(), Map.of(
                "config", Map.of("apiToken", "nested-secret", "items", List.of(
                        Map.of("databasePassword", "nested-password", "safe", "visible")))), "success");
        assertAuditRedacted();
        Map<String, Object> oversized = new LinkedHashMap<>();
        oversized.put("k".repeat(300), "must-not-appear");
        oversized.put("oversizedNumber", BigInteger.TEN.pow(10_000));
        for (int outer = 0; outer < 100; outer++) {
            Map<String, Object> branch = new LinkedHashMap<>();
            for (int inner = 0; inner < 100; inner++) branch.put("key-" + inner, "x".repeat(500));
            oversized.put("branch-" + outer, branch);
        }
        audit.record(agent, "test.bounded_redaction", "user", agent,
                Map.of(), oversized, "success");
        assertAuditSummaryBounded();
        assertTrue(auditActionCount("message.read_sensitive") >= 1);
        assertTrue(auditActionCount("conversation.assign") >= 1);

        installRejectingAuditTrigger();
        try {
            assertThrows(Exception.class, () -> sessions.login("admin", "correct-password".toCharArray()));
            assertEquals(0, activeSessionCount(admin));
        } finally {
            removeRejectingAuditTrigger();
        }

        IssuedSession atomicLogout = sessions.login("admin", "correct-password".toCharArray());
        installRejectingAuditTrigger();
        try {
            assertThrows(Exception.class, () -> sessions.logout(atomicLogout.rawToken()));
        } finally {
            removeRejectingAuditTrigger();
        }
        assertEquals(admin, sessions.authenticate(atomicLogout.rawToken()).userId());
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

    private static UUID insertInbound(UUID conversation, boolean countsAsUnread, String body) throws Exception {
        return database.transaction(connection -> {
            UUID account;
            long sequence;
            UUID messageId = UUID.randomUUID();
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
                        (id,conversation_id,channel_account_id,direction,message_kind,body_text,
                         occurred_at,ingest_sequence,counts_as_unread,current_status,current_status_at)
                    values (?,?,?,'inbound','text',?,now(),?,?,'delivered',now())
                    """)) {
                statement.setObject(1, messageId);
                statement.setObject(2, conversation);
                statement.setObject(3, account);
                statement.setString(4, body);
                statement.setLong(5, sequence);
                statement.setBoolean(6, countsAsUnread);
                statement.executeUpdate();
            }
            return messageId;
        });
    }

    private static UUID createAttachment(UUID messageId) throws Exception {
        return database.transaction(connection -> {
            UUID attachmentId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into attachments
                        (id,message_id,bucket,object_key,original_name,mime_type,size_bytes,sha256,media_kind,storage_status)
                    values (?,?,? ,?,?,?,?,'0000000000000000000000000000000000000000000000000000000000000000',
                            'document','ready')
                    """)) {
                statement.setObject(1, attachmentId);
                statement.setObject(2, messageId);
                statement.setString(3, "message-center-test");
                statement.setString(4, "attachments/" + attachmentId);
                statement.setString(5, "fixture.txt");
                statement.setString(6, "text/plain");
                statement.setLong(7, 1);
                statement.executeUpdate();
            }
            return attachmentId;
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

    private static long auditActionCount(String action) throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select count(*) from audit_logs where action=?")) {
                statement.setString(1, action);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        });
    }

    private static long auditActionResultCount(String action, String result) throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select count(*) from audit_logs where action=? and result=?")) {
                statement.setString(1, action);
                statement.setString(2, result);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        });
    }

    private static void assertAuditRedacted() throws Exception {
        database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select after_summary_jsonb::text from audit_logs
                    where action='test.redaction' order by occurred_at desc,id desc limit 1
                    """)) {
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    String summary = rows.getString(1);
                    assertFalse(summary.contains("nested-secret"));
                    assertFalse(summary.contains("nested-password"));
                    assertTrue(summary.contains("[REDACTED]"));
                    assertTrue(summary.contains("visible"));
                }
            }
            return null;
        });
    }

    private static void assertAuditSummaryBounded() throws Exception {
        database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select after_summary_jsonb::text from audit_logs
                    where action='test.bounded_redaction' order by occurred_at desc,id desc limit 1
                    """)) {
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    String summary = rows.getString(1);
                    assertTrue(summary.length() <= 30_000, "audit summary exceeded its global budget");
                    assertFalse(summary.contains("must-not-appear"));
                    assertTrue(summary.contains("\"oversizedNumber\": \"[TRUNCATED]\""));
                    assertTrue(summary.contains("[TRUNCATED_KEY]"));
                    assertTrue(summary.contains("[TRUNCATED]"));
                }
            }
            return null;
        });
    }

    private static void forceReadSequence(UUID userId, UUID conversationId, long sequence) throws Exception {
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    update conversation_read_states set last_read_sequence=?
                    where conversation_id=? and user_id=?
                    """)) {
                statement.setLong(1, sequence);
                statement.setObject(2, conversationId);
                statement.setObject(3, userId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static long activeSessionCount(UUID userId) throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select count(*) from user_sessions where user_id=? and revoked_at is null")) {
                statement.setObject(1, userId);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        });
    }

    private static void installRejectingAuditTrigger() throws Exception {
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    create or replace function reject_audit_insert() returns trigger as $$
                    begin raise exception 'audit rejected'; end;
                    $$ language plpgsql
                    """)) {
                statement.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    create trigger reject_audit before insert on audit_logs
                    for each row execute function reject_audit_insert()
                    """)) {
                statement.execute();
            }
            return null;
        });
    }

    private static void removeRejectingAuditTrigger() throws Exception {
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "drop trigger if exists reject_audit on audit_logs")) {
                statement.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "drop function if exists reject_audit_insert()")) {
                statement.execute();
            }
            return null;
        });
    }

    private static boolean allZero(char[] value) {
        for (char character : value) if (character != '\0') return false;
        return true;
    }
}
