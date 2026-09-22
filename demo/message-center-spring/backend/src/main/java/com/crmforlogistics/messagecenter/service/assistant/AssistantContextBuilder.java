package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * 构造一轮请求的会话事实（设计文档 §7.1）。
 *
 * <h2>时区必须与提醒链路同一口径</h2>
 * 本项目的 {@code Clock} bean 是 {@code Clock.systemUTC()}，因此三种写法里只有一种是对的：
 *
 * <ul>
 *   <li>❌ {@code LocalDate.now()} —— 跟 JVM 默认时区走。开发机恰好是 {@code Asia/Shanghai}
 *       时看着正确，部署到 UTC 容器上就会差一天；</li>
 *   <li>❌ {@code LocalDate.now(clock)} —— 拿的是 UTC 日期，同样错；</li>
 *   <li>✅ {@code ZonedDateTime.now(clock.withZone(zone))} —— 与 {@code WeComTodoReminderScheduler}
 *       逐字一致。</li>
 * </ul>
 *
 * <p>时区值取自 {@code AppConfig.todoReminderZone()}（配置项 {@code app.todo-reminder-zone}），
 * 而不是助手自己的配置项。理由：助手说的「明天」必须和提醒发出的「明天」是同一天，
 * 这两个值一旦分叉，就会出现「助手建了明天的待办、提醒今天发出来」这种无法解释的现象。
 * 多一个配置项就多一次分叉的机会，所以这里刻意不新增。
 *
 * <p>候选清单走 {@link TodoItemService#listOpenForAssistant}，不直接访问 Mapper ——
 * 越权防线（{@code where user_id}）与硬上限都收口在 service 层。
 */
@Component
public class AssistantContextBuilder {

    private final TodoItemService todoService;
    private final ConversationCandidateProvider conversationCandidates;
    private final AppConfig appConfig;
    private final Clock clock;

    public AssistantContextBuilder(TodoItemService todoService,
                                   ConversationCandidateProvider conversationCandidates,
                                   AppConfig appConfig,
                                   Clock clock) {
        this.todoService = todoService;
        this.conversationCandidates = conversationCandidates;
        this.appConfig = appConfig;
        this.clock = clock;
    }

    /**
     * 组装会话事实。
     *
     * <p>多组候选的装配就是这里：<b>每一组各自由自己的 provider 提供、各自有界</b>，
     * 编排层只拿到一个 {@code List<CandidateSet>}。加一个域等于在这里多加一项，
     * 提示词与解析器都不用动（它们按集合名工作）。
     *
     * <p>待办那一组的条数上限来自配置（{@code assistant.candidate-todo-limit}），
     * 会话那一组的上限写在自己的声明里 —— 两者的「合理条数」不是一个量级，
     * 用一个配置项统一调只会得到一个对两边都不合适的值。
     */
    public AssistantContext build(java.util.UUID userId, int candidateLimit) {
        ZoneId zone = ZoneId.of(appConfig.todoReminderZone());
        LocalDate today = ZonedDateTime.now(clock.withZone(zone)).toLocalDate();

        List<AssistantContext.CandidateTodo> candidates = todoService
                .listOpenForAssistant(userId, candidateLimit)
                .stream()
                .map(AssistantContextBuilder::toCandidate)
                .toList();

        return new AssistantContext(zone.getId(), today, weekdayOf(today),
                List.of(new TodoCandidates(candidateLimit, candidates),
                        conversationCandidates.recent(userId)));
    }

    /**
     * 中文星期名。用 {@link TextStyle#FULL} + {@link Locale#CHINA} 而不是手写数组：
     * 手写映射会在有人改日期格式时不受影响，从而安静地和 {@code LocalDate} 的语义脱节。
     */
    static String weekdayOf(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
    }

    private static AssistantContext.CandidateTodo toCandidate(TodoItemEntity item) {
        return new AssistantContext.CandidateTodo(
                item.getId().toString(),
                item.getDueDate().toString(),
                item.getDueTime() == null ? null : item.getDueTime().toString(),
                item.getTitle());
    }
}
