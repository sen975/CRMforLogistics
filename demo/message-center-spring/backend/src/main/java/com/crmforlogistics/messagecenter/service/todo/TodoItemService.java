package com.crmforlogistics.messagecenter.service.todo;

import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Service
public class TodoItemService {
    private final TodoItemMapper mapper;
    public TodoItemService(TodoItemMapper mapper) { this.mapper = mapper; }
    public List<TodoItemEntity> list(UUID userId) { return mapper.list(userId); }
    public TodoItemEntity create(UUID userId, String date, String title, String time, String note) {
        if (userId == null || title == null || title.isBlank() || title.length() > 200) throw new IllegalArgumentException("待办标题不能为空且不能超过 200 个字符");
        TodoItemEntity item = new TodoItemEntity(); item.setId(UUID.randomUUID()); item.setUserId(userId); item.setDueDate(LocalDate.parse(date)); item.setTitle(title.trim()); item.setDueTime(time == null || time.isBlank() ? null : LocalTime.parse(time)); item.setNote(note == null || note.isBlank() ? null : note.trim());
        mapper.insert(item); return item;
    }
    public void setCompleted(UUID userId, UUID id, boolean completed) { if (mapper.setCompleted(id, userId, completed) != 1) throw new IllegalArgumentException("待办不存在"); }
    public void delete(UUID userId, UUID id) { if (mapper.delete(id, userId) != 1) throw new IllegalArgumentException("待办不存在"); }
}
