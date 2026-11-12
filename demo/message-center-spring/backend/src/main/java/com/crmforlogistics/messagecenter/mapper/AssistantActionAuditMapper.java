package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.AssistantActionAuditEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * 助手审计流水。只有写入与两种读法 —— 这是一张追加写表，
 * 不提供更新与删除：能改的审计不叫审计。
 */
@Mapper
public interface AssistantActionAuditMapper {

    /**
     * {@code arguments_json} 为 {@code null} 时写出的是 SQL NULL，表示「这一轮没有参数」。
     * 刻意不写 {@code coalesce(..., '{}'::jsonb)}：那会把「没有参数」压成「参数是空对象」，
     * 而 {@code ask} / {@code reply} 两轮本来就该是前者。
     *
     * <p>写成 {@code cast(... as jsonb)} 而不是 {@code #{x}::jsonb}：两种写法在本仓库里都有先例，
     * 但 {@code cast} 让「这里在做类型转换」不依赖占位符与 {@code ::} 相邻时的解析细节。
     *
     * <p>{@code turn_index} 同样可以为 NULL（确认 / 取消 / 过期行）—— 理由见实体注释。
     */
    @Insert("insert into assistant_action_audit "
            + "(user_id, conversation_id, utterance, decision, tool_name, arguments, arguments_digest, "
            + "policy, outcome, error_code, model, latency_ms, turn_index) "
            + "values (#{userId}::uuid, #{conversationId}::uuid, #{utterance}, #{decision}, #{toolName}, "
            + "cast(#{argumentsJson} as jsonb), #{argumentsDigest}, #{policy}, #{outcome}, "
            + "#{errorCode}, #{model}, #{latencyMs}, #{turnIndex})")
    int insert(AssistantActionAuditEntity entity);

    @Select("select id, user_id, conversation_id, utterance, decision, tool_name, "
            + "arguments::text as arguments_json, arguments_digest, policy, outcome, error_code, "
            + "model, latency_ms, turn_index, created_at "
            + "from assistant_action_audit where user_id = #{userId}::uuid "
            + "order by created_at desc, id desc limit #{limit}")
    List<AssistantActionAuditEntity> listByUser(@Param("userId") UUID userId,
                                                @Param("limit") int limit);

    /**
     * 一段会话的完整轨迹，按轮次正序。这是 {@code turn_index} 存在的**唯一**理由：
     * 「模型依次看了什么、最后做了什么」只有按轮排才读得出来，
     * 而 {@code created_at} 排不了 —— 同一轮内的两行可能落在同一毫秒。
     *
     * <p>确认 / 取消 / 过期行（{@code turn_index} 为 NULL）排在最后：
     * 它们发生在轮次序列之外，放末尾比插在中间更少误导。
     */
    @Select("select id, user_id, conversation_id, utterance, decision, tool_name, "
            + "arguments::text as arguments_json, arguments_digest, policy, outcome, error_code, "
            + "model, latency_ms, turn_index, created_at "
            + "from assistant_action_audit where user_id = #{userId}::uuid and conversation_id = #{conversationId}::uuid "
            + "order by turn_index asc nulls last, created_at asc, id asc")
    List<AssistantActionAuditEntity> listByConversation(@Param("userId") UUID userId,
                                                        @Param("conversationId") UUID conversationId);
}
