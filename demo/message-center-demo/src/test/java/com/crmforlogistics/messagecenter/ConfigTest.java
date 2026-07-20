package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void exposesDatabaseMinioAndBoundedWorkerSettings() {
        Config config = new Config(Map.ofEntries(
                Map.entry("DATABASE_URL", "jdbc:postgresql://localhost:5432/message_center"),
                Map.entry("DATABASE_USER", "message_center"),
                Map.entry("DATABASE_PASSWORD_FILE", "/run/secrets/postgres_password"),
                Map.entry("CREDENTIAL_MASTER_KEY_FILE", "/run/secrets/credential_master_key"),
                Map.entry("MINIO_ENDPOINT", "http://localhost:9000"),
                Map.entry("MINIO_ACCESS_KEY_FILE", "/run/secrets/minio_access_key"),
                Map.entry("MINIO_SECRET_KEY_FILE", "/run/secrets/minio_secret_key"),
                Map.entry("MINIO_BUCKET", "message-center"),
                Map.entry("BOOTSTRAP_ADMIN_USERNAME", "local-owner"),
                Map.entry("BOOTSTRAP_ADMIN_PASSWORD_FILE", "/run/secrets/bootstrap_admin_password"),
                Map.entry("WORKER_BATCH_SIZE", "25"),
                Map.entry("WORKER_MAX_ATTEMPTS", "6")
        ));

        assertEquals("jdbc:postgresql://localhost:5432/message_center", config.databaseUrl());
        assertEquals("message_center", config.databaseUser());
        assertEquals(Path.of("/run/secrets/postgres_password"), config.databasePasswordFile());
        assertEquals(Path.of("/run/secrets/credential_master_key"), config.credentialMasterKeyFile());
        assertEquals("http://localhost:9000", config.minioEndpoint());
        assertEquals(Path.of("/run/secrets/minio_access_key"), config.minioAccessKeyFile());
        assertEquals(Path.of("/run/secrets/minio_secret_key"), config.minioSecretKeyFile());
        assertEquals("message-center", config.minioBucket());
        assertEquals("local-owner", config.bootstrapAdminUsername());
        assertEquals(Path.of("/run/secrets/bootstrap_admin_password"), config.bootstrapAdminPasswordFile());
        assertEquals(25, config.workerBatchSize());
        assertEquals(6, config.workerMaxAttempts());
    }

    @Test
    void rejectsWorkerSettingsOutsideTheirBounds() {
        Config config = new Config(Map.of(
                "WORKER_BATCH_SIZE", "101",
                "WORKER_MAX_ATTEMPTS", "0"
        ));

        assertThrows(IllegalArgumentException.class, config::workerBatchSize);
        assertThrows(IllegalArgumentException.class, config::workerMaxAttempts);
    }

    @Test
    void readsSecretWithoutTrailingLineBreaks() throws Exception {
        Path secretFile = tempDir.resolve("service-secret");
        Files.writeString(secretFile, "value with spaces\r\n", StandardCharsets.UTF_8);

        assertEquals("value with spaces", new Config(Map.of()).readSecret(secretFile));
    }

    @Test
    void readsBootstrapPasswordAsBoundedClearableCharacters() throws Exception {
        Path secretFile = tempDir.resolve("bootstrap-password");
        Files.writeString(secretFile, "local-admin-password\r\n", StandardCharsets.UTF_8);

        char[] password = App.readSecretChars(secretFile);

        assertArrayEquals("local-admin-password".toCharArray(), password);
        Path oversized = tempDir.resolve("oversized-password");
        Files.writeString(oversized, "x".repeat(1025), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> App.readSecretChars(oversized));
    }
}
