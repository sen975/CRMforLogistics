package com.crmforlogistics.messagecenter.service.todo;

import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@Service
public class TodoItemService {
    /** 助手候选清单的默认条数；超出的待办不参与「标记完成 xx」的匹配。 */
    public static final int ASSISTANT_CANDIDATE_DEFAULT_LIMIT = 70;
    /** 调用方无论传多大的 limit 都不会超过这个值。 */
    public static final int ASSISTANT_CANDIDATE_MAX_LIMIT = 100;

    private final TodoItemMapper mapper;
    public TodoItemService(TodoItemMapper mapper) { this.mapper = mapper; }
    public List<TodoItemEntity> list(UUID userId) { return mapper.list(userId); }
    public TodoItemEntity create(UUID userId, String date, String title, String time, String note) {
        if (userId == null || title == null || title.isBlank() || title.length() > 200) throw new IllegalArgumentException("待办标题不能为空且不能超过 200 个字符");
        TodoItemEntity item = new TodoItemEntity(); item.setId(UUID.randomUUID()); item.setUserId(userId); item.setDueDate(LocalDate.parse(date)); item.setTitle(title.trim()); item.setDueTime(time == null || time.isBlank() ? null : LocalTime.parse(time)); item.setNote(note == null || note.isBlank() ? null : note.trim());
        mapper.insert(item); return item;
    }
    public void setCompleted(UUID userId, UUID id, boolean completed) { if (mapper.setCompleted(id, userId, completed) != 1) throw new TodoItemNotFoundException(); }
    public void delete(UUID userId, UUID id) { if (mapper.delete(id, userId) != 1) throw new TodoItemNotFoundException(); }

    /** 单条读取，必须属于该用户。供助手在改动前确认归属、改动后回读结果。 */
    public TodoItemEntity require(UUID userId, UUID id) {
        if (userId == null || id == null) throw new TodoItemNotFoundException();
        TodoItemEntity item = mapper.findById(id, userId);
        if (item == null) throw new TodoItemNotFoundException();
        return item;
    }

    /**
     * 助手候选清单：当前用户未完成的待办，有界。
     *
     * <p>{@code limit} 非正数取默认值，超过硬上限一律压到硬上限 —— 这个上限是必须的，
     * 因为它同时决定了注入提示词的体积和 {@code todo.complete} 的匹配范围，
     * 让调用方传多大就多大等于把这两件事交给调用方决定。
     */
    public List<TodoItemEntity> listOpenForAssistant(UUID userId, int limit) {
        if (userId == null) throw new IllegalArgumentException("缺少用户");
        int effective = limit <= 0 ? ASSISTANT_CANDIDATE_DEFAULT_LIMIT
                : Math.min(limit, ASSISTANT_CANDIDATE_MAX_LIMIT);
        return mapper.listOpenForAssistant(userId, effective);
    }

    /**
     * 改动单条待办，只更新显式给出的字段（{@code null} = 不修改）。
     *
     * <p><b>不提供置空语义</b>：{@code due_date} 是 {@code NOT NULL}、{@code title} 有非空白 CHECK，
     * 这两列本来就无法置空；与其让每列规则不同，不如统一为"只设置"。因此传了空白的
     * {@code title} / {@code note} 一律<b>报参数错误而不是静默忽略</b> —— 静默忽略会产出一个
     * 最糟的结果：模型回话"已改好"，而数据其实一动没动。
     */
    public TodoItemEntity update(UUID userId, UUID id, String title, String date, String time, String note) {
        if (userId == null || id == null) throw new TodoItemNotFoundException();

        String normalizedTitle = trimToNull(title);
        if (title != null && normalizedTitle == null) throw new IllegalArgumentException("待办标题不能为空");
        if (normalizedTitle != null && normalizedTitle.length() > 200) throw new IllegalArgumentException("待办标题不能超过 200 个字符");

        String normalizedNote = trimToNull(note);
        if (note != null && normalizedNote == null) throw new IllegalArgumentException("待办备注不能为空");
        if (normalizedNote != null && normalizedNote.length() > 1000) throw new IllegalArgumentException("待办备注不能超过 1000 个字符");

        LocalDate parsedDate = parseDate(date);
        LocalTime parsedTime = parseTime(time);

        if (normalizedTitle == null && parsedDate == null && parsedTime == null && normalizedNote == null) {
            throw new IllegalArgumentException("没有要修改的字段");
        }
        if (mapper.update(id, userId, normalizedTitle, parsedDate, parsedTime, normalizedNote) != 1) {
            // 归属校验已在 SQL 的 where user_id 里；这里 0 行只可能是对象在此期间被删了。
            throw new TodoItemNotFoundException();
        }
        return mapper.findById(id, userId);
    }

    /**
     * 日期解析失败统一转成 {@link IllegalArgumentException}。
     *
     * <p>{@code LocalDate.parse} 抛的是 {@code DateTimeParseException}（继承自
     * {@code DateTimeException}），与参数校验的异常不同族。工具层要按异常类型映射错误码，
     * 两族异常会让它必须记住"哪些算用户输入问题"，所以在新方法里统一收口。
     * 既有 {@code create} 的异常行为刻意不动。
     */
    private static LocalDate parseDate(String date) {
        if (date == null) return null;
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式应为 YYYY-MM-DD，收到：" + date);
        }
    }

    private static LocalTime parseTime(String time) {
        if (time == null) return null;
        try {
            return LocalTime.parse(time.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("时间格式应为 HH:mm，收到：" + time);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
