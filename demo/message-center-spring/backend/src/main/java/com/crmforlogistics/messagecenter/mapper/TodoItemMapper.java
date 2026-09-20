package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import org.apache.ibatis.annotations.*;
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
}
