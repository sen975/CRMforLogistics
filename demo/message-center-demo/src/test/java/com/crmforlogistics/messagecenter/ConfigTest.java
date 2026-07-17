package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void exposesDatabaseMinioAndBoundedWorkerSettings() {
        Config config = new Config(Map.of(
                "DATABASE_URL", "jdbc:postgresql://localhost:5432/message_center",
                "DATABASE_USER", "message_center",
                "DATABASE_PASSWORD_FILE", "/run/secrets/postgres_password",
                "CREDENTIAL_MASTER_KEY_FILE", "/run/secrets/credential_master_key",
                "MINIO_ENDPOINT", "http://localhost:9000",
                "MINIO_ACCESS_KEY_FILE", "/run/secrets/minio_access_key",
                "MINIO_SECRET_KEY_FILE", "/run/secrets/minio_secret_key",
                "MINIO_BUCKET", "message-center",
                "WORKER_BATCH_SIZE", "25",
                "WORKER_MAX_ATTEMPTS", "6"
        ));

        assertEquals("jdbc:postgresql://localhost:5432/message_center", config.databaseUrl());
        assertEquals("message_center", config.databaseUser());
        assertEquals(Path.of("/run/secrets/postgres_password"), config.databasePasswordFile());
        assertEquals(Path.of("/run/secrets/credential_master_key"), config.credentialMasterKeyFile());
        assertEquals("http://localhost:9000", config.minioEndpoint());
        assertEquals(Path.of("/run/secrets/minio_access_key"), config.minioAccessKeyFile());
        assertEquals(Path.of("/run/secrets/minio_secret_key"), config.minioSecretKeyFile());
        assertEquals("message-center", config.minioBucket());
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
}
