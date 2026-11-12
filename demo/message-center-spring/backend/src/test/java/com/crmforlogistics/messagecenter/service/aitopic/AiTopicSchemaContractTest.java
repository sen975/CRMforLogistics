package com.crmforlogistics.messagecenter.service.aitopic;

import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.StringResource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AiTopicSchemaContractTest {
    @Test
    void migrationDefinesBoundedTopicSourcesAndIdempotentJobs() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V26__ai_topics.sql"));
        assertThat(sql).contains("CREATE TABLE ai_topics");
        assertThat(sql).contains("CREATE TABLE ai_topic_items");
        assertThat(sql).contains("CREATE TABLE ai_topic_generation_jobs");
        assertThat(sql).contains("CREATE TABLE ai_topic_versions");
        assertThat(sql).contains("channel_type IN ('chatapp', 'email', 'phone')");
        assertThat(sql).contains("CHECK ((message_id IS NOT NULL) <> (call_record_id IS NOT NULL))");
        assertThat(sql).contains("UNIQUE (contact_id, input_fingerprint)");
        assertThat(sql).contains("created_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL");
    }

    @Test
    void followUpMigrationAddsGenerationJobActorColumnIdempotently() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V27__ai_topic_job_actor.sql"));
        assertThat(sql).contains("ALTER TABLE ai_topic_generation_jobs");
        assertThat(sql).contains("ADD COLUMN IF NOT EXISTS created_by_user_id uuid");
        assertThat(sql).contains("REFERENCES users(id) ON DELETE SET NULL");
    }

    @Test
    void lifecycleMigrationAddsDiscardedTopicsAndIdempotentOperationJobs() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V28__ai_topic_lifecycle_and_operation_jobs.sql"));
        assertThat(sql).contains("'DISCARDED'");
        assertThat(sql).contains("'DISCARDED', 'RESTORED'");
        assertThat(sql).contains("CREATE TABLE ai_topic_operation_jobs");
        assertThat(sql).contains("UNIQUE (created_by_user_id, idempotency_key)");
        assertThat(sql).contains("operation_kind IN ('EDIT', 'MERGE', 'DISCARD', 'RESTORE')");
        assertThat(sql).contains("status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')");
    }

    @Test
    void appliedTopicMigrationRemainsImmutable() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V26__ai_topics.sql"));
        assertThat(ChecksumCalculator.calculate(new StringResource(sql))).isEqualTo(-107120564);
    }

    @Test
    void productionConfigBindsGenericProviderEnvironmentVariables() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yaml).contains("ai-topic:")
                .contains("base-url: ${AI_BASE_URL:}")
                .contains("api-key: ${AI_API_KEY:}");
    }
}
