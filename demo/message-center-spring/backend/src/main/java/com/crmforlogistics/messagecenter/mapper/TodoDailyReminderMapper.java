package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 当天汇总提醒的发送台账。既当幂等键（{@code (user_id, reminder_date)} 唯一），也当重试计数。
 *
 * <p>幂等判定刻意压在 SQL 里而不是调用方：调度器每 60 秒跑一次同一批候选，只要"抢不到行"就
 * 不发，重复发送在数据库这一层就被挡掉，调用方无需自行记忆发送状态 —— 重启、多线程调度、
 * 人工重放都不会产生第二条汇总。
 */
@Mapper
public interface TodoDailyReminderMapper {

    /**
     * 抢占某用户某天的汇总发送权，返回非空表示本次 tick 拿到发送权。
     *
     * <p>首次插入即抢到；行已存在时只有落在 {@code FAILED} 且未耗尽重试次数才允许重发。
     * {@code SENT}、{@code PENDING}、{@code ABANDONED}，以及重试次数用尽的 {@code FAILED}
     * 都不会返回行 —— 于是"已经发成功"和"不再重试"这两种终态天然收敛。
     */
    @Select("insert into todo_daily_reminders (user_id, reminder_date, status, task_count, attempt_count, claim_token, claimed_at) "
            + "values (#{userId}::uuid, #{date}, 'PENDING', #{taskCount}, 1, #{claimToken}, now()) "
            + "on conflict (user_id, reminder_date) do update "
            + "set status = 'PENDING', task_count = #{taskCount}, "
            + "attempt_count = todo_daily_reminders.attempt_count + 1, claim_token = #{claimToken}, "
            + "claimed_at = now(), updated_at = now() "
            + "where (todo_daily_reminders.status = 'FAILED' "
            + "and todo_daily_reminders.attempt_count < #{maxAttempts}) "
            + "or (todo_daily_reminders.status = 'PENDING' and todo_daily_reminders.claimed_at < now() - interval '1 second' * #{leaseSeconds}) "
            + "returning claim_token")
    String claim(@Param("userId") UUID userId,
                  @Param("date") LocalDate date,
                  @Param("taskCount") int taskCount,
                  @Param("maxAttempts") int maxAttempts,
                  @Param("claimToken") String claimToken,
                  @Param("leaseSeconds") int leaseSeconds);

    @Update("update todo_daily_reminders set status = 'SENT', message_id = #{messageId}, "
            + "claim_token = null, claimed_at = null, last_error = null, updated_at = now() "
            + "where user_id = #{userId}::uuid and reminder_date = #{date} and claim_token = #{claimToken}")
    int markSent(@Param("userId") UUID userId,
                 @Param("date") LocalDate date,
                 @Param("claimToken") String claimToken,
                 @Param("messageId") String messageId);

    /**
     * 记录一次失败，重试次数用尽时直接落到 {@code ABANDONED}。
     * 否则一条注定失败的提醒（例如企微侧长期拒绝该账号）会每个 tick 重试到当天结束。
     */
    @Update("update todo_daily_reminders set "
            + "status = case when attempt_count >= #{maxAttempts} then 'ABANDONED' else 'FAILED' end, "
            + "claim_token = null, claimed_at = null, last_error = #{error}, updated_at = now() "
            + "where user_id = #{userId}::uuid and reminder_date = #{date} and claim_token = #{claimToken}")
    int markFailed(@Param("userId") UUID userId,
                   @Param("date") LocalDate date,
                   @Param("claimToken") String claimToken,
                   @Param("error") String error,
                   @Param("maxAttempts") int maxAttempts);
}
