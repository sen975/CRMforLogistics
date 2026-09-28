package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface TodoItemMapper {
    @Select("select id, user_id, due_date, title, due_time, note, completed, created_at, updated_at from todo_items where user_id = #{userId}::uuid order by due_date, due_time nulls last, created_at")
    List<TodoItemEntity> list(UUID userId);

    @Insert("insert into todo_items (id, user_id, due_date, title, due_time, note, completed) values (#{id}::uuid, #{userId}::uuid, #{dueDate}, #{title}, #{dueTime}, #{note}, #{completed})")
    int insert(TodoItemEntity item);

    @Update("update todo_items set completed = #{completed}, updated_at = now() where id = #{id}::uuid and user_id = #{userId}::uuid")
    int setCompleted(@Param("id") UUID id, @Param("userId") UUID userId, @Param("completed") boolean completed);

    @Delete("delete from todo_items where id = #{id}::uuid and user_id = #{userId}::uuid")
    int delete(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * 当天汇总提醒的候选集：当天未完成、且账号已绑定企业微信的待办。
     *
     * <p>绑定条件写进 SQL 而不是发送时兜底 —— 未绑定的用户本就不该进入候选，否则每个 tick
     * 都要撞一次"绑定不存在"，用日志噪声换一个本可提前排除的分支。
     */
    @Select("select t.id, t.user_id, t.due_date, t.title, t.due_time, t.note, t.completed, "
            + "t.lead_reminder_sent_at, t.created_at, t.updated_at "
            + "from todo_items t "
            + "join wecom_user_bindings b on b.user_id = t.user_id "
            + "where t.due_date = #{date} and t.completed = false "
            + "order by t.user_id, t.due_time nulls last, t.created_at")
    List<TodoItemEntity> listDailyReminderCandidates(@Param("date") LocalDate date);

    /**
     * 「开始前 N 小时」的候选集：窗口为 {@code (now, deadline]}，即已经进入提醒窗口但还没开始的待办。
     *
     * <p>上界 {@code now} 不能省：服务停机后恢复时，一对早已开始的待办不应被补成"即将开始"。
     * 比较放在 {@code LocalDateTime} 上而不引入时区，是因为 {@code due_date + due_time} 本身就是
     * 不带时区的"本地日历时间"，调用方已按业务时区算好窗口边界，这里再做一次时区换算只会
     * 多一处可能不一致的地方。
     */
    @Select("select t.id, t.user_id, t.due_date, t.title, t.due_time, t.note, t.completed, "
            + "t.lead_reminder_sent_at, t.created_at, t.updated_at "
            + "from todo_items t "
            + "join wecom_user_bindings b on b.user_id = t.user_id "
            + "where t.due_date = #{date} and t.due_time is not null and t.completed = false "
            + "and t.lead_reminder_sent_at is null "
            + "and (t.lead_reminder_claimed_at is null or t.lead_reminder_claimed_at < now() - interval '1 second' * #{leaseSeconds}) "
            + "and (t.due_date + t.due_time) > #{now} "
            + "and (t.due_date + t.due_time) <= #{deadline} "
            + "order by t.due_date, t.due_time, t.created_at")
    List<TodoItemEntity> listLeadReminderCandidates(@Param("date") LocalDate date,
                                                    @Param("now") LocalDateTime now,
                                                    @Param("deadline") LocalDateTime deadline,
                                                    @Param("leaseSeconds") int leaseSeconds);

    /**
     * 抢下一条待办的提前提醒发送权。返回 1 才发送；返回 0 说明已被别人标记或待办已完成/已删。
     * 先占位后发送，失败时用 {@link #releaseLeadReminder} 退回，避免把"发送失败"固化成"已提醒"。
     */
    @Update("update todo_items set lead_reminder_claimed_at = now(), lead_reminder_claim_token = #{claimToken}, updated_at = now() "
            + "where id = #{id}::uuid and completed = false and lead_reminder_sent_at is null "
            + "and (lead_reminder_claimed_at is null or lead_reminder_claimed_at < now() - interval '1 second' * #{leaseSeconds})")
    int claimLeadReminder(@Param("id") UUID id, @Param("claimToken") String claimToken,
                          @Param("leaseSeconds") int leaseSeconds);

    @Update("update todo_items set lead_reminder_claimed_at = null, lead_reminder_claim_token = null, updated_at = now() "
            + "where id = #{id}::uuid and lead_reminder_claim_token = #{claimToken}")
    int releaseLeadReminder(@Param("id") UUID id, @Param("claimToken") String claimToken);

    @Update("update todo_items set lead_reminder_sent_at = now(), lead_reminder_claimed_at = null, "
            + "lead_reminder_claim_token = null, updated_at = now() "
            + "where id = #{id}::uuid and completed = false and lead_reminder_claim_token = #{claimToken}")
    int markLeadReminderSent(@Param("id") UUID id, @Param("claimToken") String claimToken);

    /**
     * 单条读取，且必须属于该用户。助手在改动后要回读一次结果（模型需要知道改成了什么），
     * 而 {@code where user_id} 写进 SQL 是越权防线的第二道 —— 第一道在候选清单比对。
     */
    @Select("select id, user_id, due_date, title, due_time, note, completed, created_at, updated_at "
            + "from todo_items where id = #{id}::uuid and user_id = #{userId}::uuid")
    TodoItemEntity findById(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * 助手改动待办：只更新显式给出的字段，返回受影响行数。
     *
     * <p><b>语义是「只设置、不置空」</b>：{@code null} 表示「不修改这个字段」。
     * 这不是偷懒 —— {@code due_date} 本身是 {@code NOT NULL}，而 {@code title} 有
     * {@code length(trim(title)) > 0} 的 CHECK，"清空"在这两列上没有合法落点。
     * 与其让不同列有不同规则，不如统一不提供置空语义；调用方想清空备注又没有表达方式时，
     * 应当在工具层明确报参数错误，而不是静默忽略 —— 静默忽略会变成"模型说改了、其实没改"。
     *
     * <p>{@code where id = ? and user_id = ?}：改动别人的待办必须影响 0 行。
     */
    @Update("<script>update todo_items "
            + "<set>"
            + "<if test='title != null'>title = #{title},</if>"
            + "<if test='date != null'>due_date = #{date},</if>"
            + "<if test='time != null'>due_time = #{time},</if>"
            + "<if test='note != null'>note = #{note},</if>"
            + "updated_at = now()"
            + "</set> "
            + "where id = #{id}::uuid and user_id = #{userId}::uuid</script>")
    int update(@Param("id") UUID id, @Param("userId") UUID userId,
               @Param("title") String title, @Param("date") LocalDate date,
               @Param("time") LocalTime time, @Param("note") String note);

    /**
     * 助手用的候选集：该用户未完成的待办，有界。
     *
     * <p>不复用 {@link #list(UUID)} —— 它既不过滤 {@code completed} 也没有上限，会把用户全部
     * 历史待办拉进内存再截断。这里的排序刻意与 {@code ix_todo_items_user_date} 的定义
     * （{@code user_id, due_date, due_time NULLS LAST, created_at}）逐列对齐，
     * 使排序可以随索引扫描一起完成。
     */
    @Select("select id, user_id, due_date, title, due_time, note, completed, created_at, updated_at "
            + "from todo_items where user_id = #{userId}::uuid and completed = false "
            + "order by due_date, due_time nulls last, created_at limit #{limit}")
    List<TodoItemEntity> listOpenForAssistant(@Param("userId") UUID userId, @Param("limit") int limit);

    /** 关键词检索待办：过滤在 SQL 内完成，避免只扫描日期最早的一页而漏掉后续匹配项。 */
    @Select("select id, user_id, due_date, title, due_time, note, completed, created_at, updated_at "
            + "from todo_items where user_id = #{userId}::uuid and completed = false "
            + "and position(lower(#{query}) in lower(title)) > 0 "
            + "order by due_date, due_time nulls last, created_at limit #{limit}")
    List<TodoItemEntity> searchOpenForAssistant(@Param("userId") UUID userId,
                                                @Param("query") String query,
                                                @Param("limit") int limit);
}
