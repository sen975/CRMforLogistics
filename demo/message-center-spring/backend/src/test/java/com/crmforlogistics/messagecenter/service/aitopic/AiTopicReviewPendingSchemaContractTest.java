package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AiTopicReviewPendingSchemaContractTest {
    @Test
    void migrationDefinesReviewPendingAndReviewMetadata() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V40__ai_topic_review_pending.sql"));
        assertThat(sql).contains("REVIEW_PENDING", "review_origin", "review_source_contact_id",
                "review_source_topic_id", "review_operation_id", "ix_ai_topics_review_owner");
        assertThat(sql).doesNotContain("DISCARDED");
    }

    @Test
    void entityExposesReviewMetadata() throws Exception {
        assertThat(AiTopicEntity.class.getMethod("getReviewOrigin")).isNotNull();
        assertThat(AiTopicEntity.class.getMethod("getReviewSourceContactId")).isNotNull();
        assertThat(AiTopicEntity.class.getMethod("getReviewSourceTopicId")).isNotNull();
        assertThat(AiTopicEntity.class.getMethod("getReviewOperationId")).isNotNull();
        assertThat(AiTopicModels.TopicReviewOrigin.valueOf("MERGE_SOURCE")).isEqualTo(AiTopicModels.TopicReviewOrigin.MERGE_SOURCE);
        assertThat(AiTopicModels.TopicReviewOrigin.valueOf("SPLIT_SOURCE")).isEqualTo(AiTopicModels.TopicReviewOrigin.SPLIT_SOURCE);
        assertThat(AiTopicModels.TopicReviewOrigin.valueOf("MANUAL_SELECTION")).isEqualTo(AiTopicModels.TopicReviewOrigin.MANUAL_SELECTION);
    }
}
