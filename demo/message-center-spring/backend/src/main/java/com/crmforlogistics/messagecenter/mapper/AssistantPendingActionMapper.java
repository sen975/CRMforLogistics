package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.AssistantPendingActionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface AssistantPendingActionMapper {

    /**
     * {@code changesJson} 为 {@code null} 时写出的是 SQL NULL，表示「这个动作没有『改前』可言」
     * （如新建）。这与 {@code arguments} 的处理一致：既然列可空，就不要用 {@code '{}'} 把
     * 「没有」压成「有但是空的」—— 那两种状态在排障时问的是不同的问题。
     *
     * <p>用 {@code cast(... as jsonb)} 而不是 {@code #{x}::jsonb}：与
     * {@code AssistantActionAuditMapper} 同一写法，让「这里在做类型转换」不依赖占位符
     * 与 {@code ::} 相邻时的解析细节。
     */
    @Insert("insert into assistant_pending_actions "
            + "(id, user_id, conversation_id, tool_name, arguments, summary, changes, status, expires_at) "
            + "values (#{id}::uuid, #{userId}::uuid, #{conversationId}::uuid, #{toolName}, "
            + "cast(#{argumentsJson} as jsonb), #{summary}, cast(#{changesJson} as jsonb), "
            + "'PENDING', #{expiresAt})")
    int insert(AssistantPendingActionEntity entity);

    /**
     * 按 (id, user_id) 取一行。
     *
     * <p>{@code where user_id} 是越权防线：别人的待确认 id 必须查不到（上层按 404 处理，
     * 而不是 403 —— 不泄露「这条 id 存在但不属于你」）。
     *
     * <p>{@code arguments::text} / {@code changes::text} 而不是依赖类型处理器：jsonb 到 String
     * 的映射走 {@code getString} 虽然也能用，但显式转换让「这里要的是 JSON 文本」成为 SQL 的一部分，
     * 不依赖 JDBC 驱动的隐式行为。
     *
     * <p><b>读回来的是「没被 Java 侧改写过的 JSON」，不是「与写入时逐字相同」</b>：
     * jsonb 在存储时会规范化（键序重排、冒号后补空格）。这对确认路径没有影响 ——
     * {@code readArguments} 用 Jackson 解析后再校验；但任何按**字符串**比对 JSON 的地方
     * 都会在这里翻车。已由 {@code AssistantActionStoreSqlTest} 钉住这一点。
     */
    @Select("select id, user_id, conversation_id, tool_name, arguments::text as arguments_json, "
            + "summary, changes::text as changes_json, status, expires_at, created_at, decided_at "
            + "from assistant_pending_actions where id = #{id}::uuid and user_id = #{userId}::uuid")
    AssistantPendingActionEntity findById(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * 把一行从 {@code PENDING} 推进到终态。返回受影响行数。
     *
     * <p><b>条件与更新写在同一条 SQL 里</b>，而不是「先查状态再更新」：后者在并发双击确认时
     * 两个请求都会读到 PENDING，于是同一个动作被执行两次。这里 {@code and status = 'PENDING'}
     * 让第二个请求影响 0 行，由它去报「已经处理过了」。
     *
     * <p><b>过期也走这条路径</b>（{@code status='EXPIRED'}）：过期与否由服务层拿
     * {@code expires_at} 比对注入的 {@code Clock} 判定，但落库同样必须从 PENDING 出发，
     * 否则「确认」与「惰性置过期」两个请求会同时成功。
     *
     * <p>{@code decided_at} 用数据库的 {@code now()} 而不是应用传来的时刻：它是排障用的
     * 时间戳，与 {@code created_at} 同源才可比。
     */
    @Update("update assistant_pending_actions set status = #{status}, decided_at = now() "
            + "where id = #{id}::uuid and user_id = #{userId}::uuid and status = 'PENDING'")
    int markDecided(@Param("id") UUID id, @Param("userId") UUID userId, @Param("status") String status);
}
