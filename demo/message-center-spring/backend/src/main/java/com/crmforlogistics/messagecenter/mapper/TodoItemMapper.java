package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
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
}
