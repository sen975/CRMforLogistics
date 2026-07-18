package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class JdbcChannelAccountRepositoryIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.5-bookworm");

    private Database database;
    private CredentialCipher cipher;
    private ChannelAccountRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = Database.open(databaseConfig(POSTGRES));
        database.migrate();
        cipher = CredentialCipher.fromBase64Key(testKey());
        repository = new JdbcChannelAccountRepository(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void storesOnlyCiphertextAndFindsActiveAccount() throws Exception {
        String identifier = unique("CUST-SPACE");
        String encrypted = cipher.encrypt(Map.of(
                "accessKeyId", "test-access-key",
                "accessKeySecret", "plaintext-secret"));

        ChannelAccount created = repository.create(
                new ChannelAccountDraft("chatapp", "Primary ChatApp", identifier), encrypted);

        assertEquals("chatapp", created.channelType());
        assertEquals(identifier, created.accountIdentifier());
        assertEquals("unbound", created.authStatus());
        assertEquals("plaintext-secret",
                cipher.decrypt(created.encryptedConfig()).get("accessKeySecret"));
        assertFalse(storedEncryptedConfig(created.id()).contains("plaintext-secret"));
        assertTrue(repository.findActive(created.id()).isEmpty());

        ChannelAccount active = repository.update(created.id(),
                new ChannelAccountPatch("Primary ChatApp", identifier, "active"), encrypted);
        assertEquals("active", active.authStatus());
        assertEquals(created.id(), repository.findActive(created.id()).orElseThrow().id());
    }

    @Test
    void rejectsPlaintextJsonBeforeItCanReachEncryptedConfig() {
        String plaintextConfig = "{\"accessKeySecret\":\"must-not-be-stored\"}";

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> repository.create(new ChannelAccountDraft(
                        "chatapp", "Unsafe", unique("unsafe")), plaintextConfig));

        assertFalse(failure.getMessage().contains("must-not-be-stored"));
    }

    @Test
    void normalizesAccountIdentifierForScopedUniqueness() throws Exception {
        String identifier = unique("Case-Sensitive");
        String encrypted = cipher.encrypt(Map.of("secret", "value"));
        repository.create(new ChannelAccountDraft("chatapp", "First", "  " + identifier + "  "), encrypted);

        Exception duplicate = assertThrows(Exception.class, () -> repository.create(
                new ChannelAccountDraft("chatapp", "Duplicate", identifier.toLowerCase()), encrypted));
        assertSqlState("23505", duplicate);

        ChannelAccount differentChannel = repository.create(
                new ChannelAccountDraft("email", "Email", identifier.toLowerCase()), encrypted);
        assertEquals("email", differentChannel.channelType());
        assertTrue(repository.list().stream().anyMatch(account -> account.id().equals(differentChannel.id())));
    }

    @Test
    void keepsSyncCursorsIsolatedByAccount() throws Exception {
        String encrypted = cipher.encrypt(Map.of("secret", "value"));
        ChannelAccount first = repository.create(
                new ChannelAccountDraft("chatapp", "First", unique("first")), encrypted);
        ChannelAccount second = repository.create(
                new ChannelAccountDraft("chatapp", "Second", unique("second")), encrypted);
        Instant firstTimestamp = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant secondTimestamp = firstTimestamp.plusSeconds(30);

        repository.upsertCursor(first.id(), "cams_history", "default", "cursor-1", firstTimestamp);
        repository.upsertCursor(second.id(), "cams_history", "default", "cursor-2", firstTimestamp);
        SyncCursor updated = repository.upsertCursor(
                first.id(), "cams_history", "default", "cursor-1b", secondTimestamp);

        assertEquals("cursor-1b", updated.value());
        assertEquals(secondTimestamp, updated.timestamp());
        assertEquals("cursor-1b", storedCursor(first.id()));
        assertEquals("cursor-2", storedCursor(second.id()));
    }

    @Test
    void upsertsTemplatesWithinAccountAndLanguageScope() throws Exception {
        String encrypted = cipher.encrypt(Map.of("secret", "value"));
        ChannelAccount first = repository.create(
                new ChannelAccountDraft("chatapp", "First", unique("tpl-first")), encrypted);
        ChannelAccount second = repository.create(
                new ChannelAccountDraft("chatapp", "Second", unique("tpl-second")), encrypted);
        Instant providerTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        repository.upsertTemplates(first.id(), List.of(new MessageTemplate(
                "welcome", "zh_CN", "Welcome", "old body", "active", providerTime)));
        repository.upsertTemplates(first.id(), List.of(new MessageTemplate(
                "welcome", "zh_CN", "Welcome v2", "new body", "active", providerTime.plusSeconds(1))));
        repository.upsertTemplates(second.id(), List.of(new MessageTemplate(
                "welcome", "zh_CN", "Other account", "other body", "active", providerTime)));

        assertEquals(1L, templateCount(first.id(), "welcome", "zh_CN"));
        assertEquals("new body", templateBody(first.id(), "welcome", "zh_CN"));
        assertEquals("other body", templateBody(second.id(), "welcome", "zh_CN"));
    }

    @Test
    void validationDecryptsOnlyForCallbackAndClearsTemporaryMap() throws Exception {
        String encrypted = cipher.encrypt(Map.of("accessKeySecret", "callback-secret"));
        ChannelAccount account = repository.create(
                new ChannelAccountDraft("chatapp", "Validate", unique("validate")), encrypted);
        ChannelAccountService service = new ChannelAccountService(repository, cipher);
        AtomicReference<Map<String, String>> receivedSecrets = new AtomicReference<>();

        ValidationResult result = service.validate(account.id(), (candidate, secrets) -> {
            assertEquals(account.id(), candidate.id());
            assertEquals("callback-secret", secrets.get("accessKeySecret"));
            receivedSecrets.set(secrets);
            return new ValidationResult(true, "VALID", "Credentials are valid");
        });

        assertTrue(result.valid());
        assertNotNull(receivedSecrets.get());
        assertTrue(receivedSecrets.get().isEmpty());
        assertFalse(service.validate(UUID.randomUUID(), (candidate, secrets) -> {
            throw new AssertionError("validator must not run");
        }).valid());
    }

    private String storedEncryptedConfig(UUID accountId) throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select encrypted_config::text from channel_accounts where id = ?")) {
                statement.setObject(1, accountId);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getString(1);
                }
            }
        });
    }

    private String storedCursor(UUID accountId) throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select cursor_value from channel_sync_cursors
                    where channel_account_id = ? and cursor_type = 'cams_history' and scope_key = 'default'
                    """)) {
                statement.setObject(1, accountId);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getString(1);
                }
            }
        });
    }

    private long templateCount(UUID accountId, String providerId, String language) throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select count(*) from message_templates
                    where channel_account_id = ? and provider_template_id = ? and language_code = ?
                    """)) {
                statement.setObject(1, accountId);
                statement.setString(2, providerId);
                statement.setString(3, language);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getLong(1);
                }
            }
        });
    }

    private String templateBody(UUID accountId, String providerId, String language) throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select body from message_templates
                    where channel_account_id = ? and provider_template_id = ? and language_code = ?
                    """)) {
                statement.setObject(1, accountId);
                statement.setString(2, providerId);
                statement.setString(3, language);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getString(1);
                }
            }
        });
    }

    private static Config databaseConfig(PostgreSQLContainer<?> postgres) throws Exception {
        Path passwordFile = Files.createTempFile("message-center-account-db-password", ".txt");
        Files.writeString(passwordFile, postgres.getPassword(), StandardCharsets.UTF_8);
        passwordFile.toFile().deleteOnExit();
        return new Config(Map.of(
                "DATABASE_URL", postgres.getJdbcUrl(),
                "DATABASE_USER", postgres.getUsername(),
                "DATABASE_PASSWORD_FILE", passwordFile.toString()
        ));
    }

    private static String testKey() {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) (index + 1);
        }
        return Base64.getEncoder().encodeToString(key);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static void assertSqlState(String expected, Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.sql.SQLException sqlException
                    && expected.equals(sqlException.getSQLState())) {
                return;
            }
            current = current.getCause();
        }
        throw new AssertionError("Expected SQL state " + expected, failure);
    }
}
