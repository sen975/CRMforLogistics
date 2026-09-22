package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话事实的构造。
 *
 * <p>核心用例是<b>时区</b>：本项目的 {@code Clock} bean 是 {@code Clock.systemUTC()}，
 * 因此「今天是哪天」必须用 {@code clock.withZone(业务时区)} 取。开发机恰好是 Asia/Shanghai 时，
 * 写成 {@code LocalDate.now(clock)} 也能通过大部分用例 —— 所以这里专门造了一个
 * 「UTC 日期与上海日期不同」的时刻把两种写法分开。
 *
 * <p>会话候选的来源在这里给空：本类验的是待办与时区，无关域给真实数据只会让断言变脆
 * （而且「空候选也要渲染成合法空数组」这件事由提示词那一侧专门覆盖）。
 */
class AssistantContextBuilderTest {

    private final TodoItemService todoService = mock(TodoItemService.class);
    private final ConversationCandidateProvider conversationCandidates =
            mock(ConversationCandidateProvider.class);
    private final ContactCandidateProvider contactCandidates = mock(ContactCandidateProvider.class);

    /**
     * 默认给两组空候选（会话、联系人）。
     *
     * <p>刻意放在 {@code @BeforeEach} 而不是 builder 里：Mockito 的规则是「最后一次 stub 生效」，
     * 而在 builder 里补 stub 会让它总在用例自己的 stub <b>之后</b>执行，把用例的意图覆盖掉 ——
     * 症状是「候选恒为空」，而且不会报错。
     */
    @BeforeEach
    void defaultCandidates() {
        when(conversationCandidates.recent(any())).thenReturn(new ConversationCandidates(0, List.of()));
        when(contactCandidates.recent(any())).thenReturn(new ContactCandidates(0, List.of()));
    }

    private AssistantContextBuilder builder(String instant, String zone) {
        AppConfig config = mock(AppConfig.class);
        when(config.todoReminderZone()).thenReturn(zone);
        return new AssistantContextBuilder(todoService, conversationCandidates, contactCandidates, config,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void todayFollowsTheBusinessZoneNotUtc() {
        // UTC 还是 9 月 21 日 20:00，上海已经是 9 月 22 日 04:00。
        AssistantContext context = builder("2026-09-21T20:00:00Z", "Asia/Shanghai").build(AssistantFixtures.USER, 70);

        assertThat(context.today()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(context.zoneId()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void utcClockWouldHaveProducedADifferentDaySoTheZoneReallyMatters() {
        // 同上一个时刻，但业务时区配成 UTC —— 结果必须不同，否则上一条用例并没有在验时区。
        AssistantContext context = builder("2026-09-21T20:00:00Z", "UTC").build(AssistantFixtures.USER, 70);

        assertThat(context.today()).isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void weekdayIsWrittenInChinese() {
        AssistantContext context = builder("2026-09-21T02:00:00Z", "Asia/Shanghai").build(AssistantFixtures.USER, 70);

        assertThat(context.weekday()).isEqualTo("星期一");
    }

    @Test
    void candidatesAreTheOpenTodosMappedToThePromptShape() {
        when(todoService.listOpenForAssistant(AssistantFixtures.USER, 70)).thenReturn(List.of(
                todo(LocalDate.of(2026, 9, 22), LocalTime.of(15, 0), "和张总确认报价"),
                todo(LocalDate.of(2026, 9, 25), null, "整理上周会议纪要")));

        AssistantContext context = builder("2026-09-21T02:00:00Z", "Asia/Shanghai")
                .build(AssistantFixtures.USER, 70);

        assertThat(context.candidates()).hasSize(2);
        assertThat(context.candidates().get(0).date()).isEqualTo("2026-09-22");
        assertThat(context.candidates().get(0).time()).isEqualTo("15:00");
        assertThat(context.candidates().get(0).title()).isEqualTo("和张总确认报价");
        // 待办可以只有日期没有时间，提示词里必须是 null 而不是空字符串 —— 模型要靠它区分
        // 「没有时间」与「时间是 00:00」。
        assertThat(context.candidates().get(1).time()).isNull();
    }

    @Test
    void theLimitIsPassedThroughToTheService() {
        when(todoService.listOpenForAssistant(AssistantFixtures.USER, 12)).thenReturn(List.of());

        builder("2026-09-21T02:00:00Z", "Asia/Shanghai").build(AssistantFixtures.USER, 12);

        verify(todoService).listOpenForAssistant(AssistantFixtures.USER, 12);
    }

    /**
     * 多组候选的装配：待办那一组的上限来自配置，会话与联系人两组的上限写在自己的声明里。
     * 这条断言钉住的是「三组都在、且各自独立」，而不是具体的条数 ——
     * 加了第三个域之后这个用例没有变形，正是候选集抽象要保证的事。
     */
    @Test
    void threeCandidateGroupsAreAssembledIndependently() {
        when(todoService.listOpenForAssistant(AssistantFixtures.USER, 12)).thenReturn(List.of());
        when(conversationCandidates.recent(AssistantFixtures.USER))
                .thenReturn(AssistantFixtures.conversationCandidates());
        when(contactCandidates.recent(AssistantFixtures.USER))
                .thenReturn(AssistantFixtures.contactCandidates());

        AssistantContext context = builder("2026-09-21T02:00:00Z", "Asia/Shanghai")
                .build(AssistantFixtures.USER, 12);

        assertThat(context.candidateSets()).extracting(CandidateSet::name)
                .containsExactly(TodoCandidates.NAME, ConversationCandidates.NAME, ContactCandidates.NAME);
        assertThat(context.todoCandidates().limit()).isEqualTo(12);
        assertThat(context.candidateSet(ConversationCandidates.NAME).limit())
                .isEqualTo(ConversationCandidateProvider.LIMIT);
        assertThat(context.candidateSet(ContactCandidates.NAME).limit())
                .isEqualTo(ContactCandidateProvider.LIMIT);
        assertThat(context.contains(ConversationCandidates.NAME, AssistantFixtures.CONVERSATION_ZHOU)).isTrue();
        assertThat(context.contains(TodoCandidates.NAME, AssistantFixtures.CONVERSATION_ZHOU))
                .as("复合键不能串到待办那一组去")
                .isFalse();
        assertThat(context.contains(ContactCandidates.NAME, AssistantFixtures.CONTACT_ZHOU_REF)).isTrue();
        assertThat(context.contains(ConversationCandidates.NAME, AssistantFixtures.CONTACT_ZHOU_REF))
                .as("同一个 id 在两个域里都合法：它本来就是同一个联系人的 id")
                .isTrue();
        assertThat(context.contains(ContactCandidates.NAME, AssistantFixtures.CONVERSATION_SEA))
                .as("企微群的 ref 不是联系人 —— 这正是 contact.brief 需要自己一组候选的理由")
                .isFalse();
    }

    private static TodoItemEntity todo(LocalDate date, LocalTime time, String title) {
        TodoItemEntity item = new TodoItemEntity();
        item.setId(UUID.randomUUID());
        item.setDueDate(date);
        item.setDueTime(time);
        item.setTitle(title);
        return item;
    }
}
