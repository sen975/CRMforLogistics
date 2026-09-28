package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantConversationSummaryMapperSqlTest {

    @Test
    void keysetReadIsOwnerScopedAndExclusive() throws Exception {
        Method method = AssistantConversationMessageMapper.class.getMethod(
                "listAfter", java.util.UUID.class, java.util.UUID.class,
                java.time.Instant.class, java.util.UUID.class, int.class);
        String sql = normalized(method.getAnnotation(Select.class).value());

        assertThat(sql).contains("user_id = #{userId}::uuid")
                .contains("conversation_id = #{conversationId}::uuid")
                .contains("created_at > #{afterCreatedAt}")
                .contains("created_at = #{afterCreatedAt}")
                .contains("id > #{afterMessageId}::uuid")
                .contains("order by created_at asc, id asc")
                .contains("limit #{limit}");
    }

    @Test
    void olderMessageCountUsesOwnerAndConversationPredicatesWithExclusiveAnchor() throws Exception {
        Method method = AssistantConversationMessageMapper.class.getMethod(
                "countBefore", java.util.UUID.class, java.util.UUID.class,
                java.time.Instant.class, java.util.UUID.class);
        String sql = normalized(method.getAnnotation(Select.class).value());

        assertThat(sql).contains("select count(*)")
                .contains("user_id = #{userId}::uuid")
                .contains("conversation_id = #{conversationId}::uuid")
                .contains("created_at < #{beforeCreatedAt}")
                .contains("id < #{beforeMessageId}::uuid");
    }

    @Test
    void firstSummaryInsertIsIdempotentAndOwnerScoped() throws Exception {
        Method method = AssistantConversationSummaryMapper.class.getMethod(
                "insertIfAbsent", java.util.UUID.class, java.util.UUID.class,
                String.class, java.time.Instant.class, java.util.UUID.class, int.class);
        String sql = normalized(method.getAnnotation(org.apache.ibatis.annotations.Insert.class).value());

        assertThat(sql).contains("on conflict (user_id, conversation_id) do nothing")
                .contains("#{userId}::uuid")
                .contains("#{conversationId}::uuid");
    }

    @Test
    void summaryAdvanceIsFencedByOwnerAndExpectedVersion() throws Exception {
        Method method = AssistantConversationSummaryMapper.class.getMethod(
                "advanceIfVersion", java.util.UUID.class, java.util.UUID.class, long.class,
                String.class, java.time.Instant.class, java.util.UUID.class, int.class);
        String sql = normalized(method.getAnnotation(Update.class).value());

        assertThat(sql).contains("where user_id = #{userId}::uuid")
                .contains("conversation_id = #{conversationId}::uuid")
                .contains("and version = #{expectedVersion}")
                .contains("version = version + 1");
    }

    @Test
    void summaryMapperExposesOwnerScopedLookup() throws Exception {
        Method method = AssistantConversationSummaryMapper.class.getMethod(
                "find", java.util.UUID.class, java.util.UUID.class);
        String sql = normalized(method.getAnnotation(Select.class).value());

        assertThat(sql).contains("user_id = #{userId}::uuid")
                .contains("conversation_id = #{conversationId}::uuid")
                .contains("limit 1");
    }

    private static String normalized(String[] fragments) {
        return String.join(" ", fragments)
                .replace("&gt;", ">")
                .replace("&lt;", "<")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
