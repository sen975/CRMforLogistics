package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantContactCandidateWindowMapperTest {
    @Test
    void lookupRequiresOwnerConversationAndUnexpiredWindow() throws Exception {
        String sql = String.join(" ", AssistantContactCandidateWindowMapper.class
                .getMethod("findFresh", UUID.class, UUID.class, Instant.class)
                .getAnnotation(Select.class).value());
        assertThat(sql).contains("user_id = #{userId}::uuid")
                .contains("conversation_id = #{conversationId}::uuid")
                .contains("expires_at > #{now}");
    }

    @Test
    void writesReplaceOneWindowPerOwnerAndDoNotAllowOlderResultsToOverwriteNewer() throws Exception {
        String sql = String.join(" ", AssistantContactCandidateWindowMapper.class
                .getMethod("upsert", UUID.class, UUID.class, String.class, Instant.class, Instant.class)
                .getAnnotation(Insert.class).value());
        assertThat(sql).contains("on conflict (user_id, conversation_id)")
                .contains("saved_at <= excluded.saved_at")
                .contains("#{referencesJson}::jsonb");
    }

    @Test
    void cleanupOnlyDeletesExpiredRows() throws Exception {
        String sql = String.join(" ", AssistantContactCandidateWindowMapper.class
                .getMethod("deleteExpired", Instant.class).getAnnotation(Delete.class).value());
        assertThat(sql).contains("where expires_at <= #{now}");
    }
}
