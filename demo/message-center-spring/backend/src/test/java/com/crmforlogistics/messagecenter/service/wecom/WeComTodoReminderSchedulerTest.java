package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComSendService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecenter.mapper.TodoDailyReminderMapper;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import com.crmforlogistics.messagecenter.service.scheduling.AdaptivePollingScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 待办提醒调度的行为契约。
 *
 * <p>时钟一律固定，并按 UTC 给值 —— 业务时区是 Asia/Shanghai，用例里刻意让 UTC 与上海跨日
 * （{@code 2026-09-19T16:05:00Z} = 上海 2026-09-20 00:05），这样"今天"取错时区会直接失败，
 * 而不是碰巧因为两者同一天而蒙混过关。
 */
class WeComTodoReminderSchedulerTest {
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DAILY_CLAIM = "daily-claim";
    private static final String LEAD_CLAIM = "lead-claim";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);

    private TodoItemMapper todoItemMapper;
    private TodoDailyReminderMapper dailyReminderMapper;
    private WeComSendService sendService;
    private AdaptivePollingScheduler pollingScheduler;

    @BeforeEach
    void setUp() {
        todoItemMapper = mock(TodoItemMapper.class);
        dailyReminderMapper = mock(TodoDailyReminderMapper.class);
        sendService = mock(WeComSendService.class);
        pollingScheduler = mock(AdaptivePollingScheduler.class);
    }

    @Test
    void sendsOneDailySummaryPerUserKeyedOnTheBusinessZone() {
        WeComTodoReminderScheduler scheduler = schedulerAt(Instant.parse("2026-09-19T16:05:00Z"), 0);
        when(todoItemMapper.listDailyReminderCandidates(TODAY))
                .thenReturn(List.of(todo("确认青岛仓出库时间", LocalTime.of(9, 30)),
                        todo("发送装车照片", null)));
        when(dailyReminderMapper.claim(eq(USER), eq(TODAY), eq(2), eq(5), anyString(), anyInt())).thenReturn(DAILY_CLAIM);
        when(dailyReminderMapper.markSent(eq(USER), eq(TODAY), eq(DAILY_CLAIM), eq("msg-1"))).thenReturn(1);
        when(sendService.sendToBoundUser(eq(USER), anyString())).thenReturn(sentResult("msg-1"));

        assertEquals(1, scheduler.pollOnce());

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sendService).sendToBoundUser(eq(USER), text.capture());
        assertTrue(text.getValue().startsWith("9月20日 待办提醒"), "实际内容：" + text.getValue());
        assertTrue(text.getValue().contains("○ 09:30 确认青岛仓出库时间"), "带时间的待办要带时间前缀");
        assertTrue(text.getValue().contains("○ 发送装车照片"), "没有时间的待办排在后面，不带时间前缀");
        verify(dailyReminderMapper).markSent(USER, TODAY, DAILY_CLAIM, "msg-1");
    }

    @Test
    void skipsUsersWhoseDailySummaryIsAlreadyRecorded() {
        WeComTodoReminderScheduler scheduler = schedulerAt(Instant.parse("2026-09-19T16:05:00Z"), 0);
        when(todoItemMapper.listDailyReminderCandidates(TODAY)).thenReturn(List.of(todo("确认仓库", null)));
        when(dailyReminderMapper.claim(any(), any(), anyInt(), anyInt(), anyString(), anyInt())).thenReturn(null);

        assertEquals(0, scheduler.pollOnce());
        verifyNoInteractions(sendService);
    }

    @Test
    void holdsTheDailySummaryUntilTheConfiguredHour() {
        // 上海 00:05，但汇总钟点配成 08:00：当天已经跨过 0 点，仍不该发。
        WeComTodoReminderScheduler scheduler = schedulerAt(Instant.parse("2026-09-19T16:05:00Z"), 8);
        when(todoItemMapper.listDailyReminderCandidates(TODAY)).thenReturn(List.of(todo("确认仓库", null)));

        assertEquals(0, scheduler.pollOnce());
        verify(dailyReminderMapper, never()).claim(any(), any(), anyInt(), anyInt(), anyString(), anyInt());
        verifyNoInteractions(sendService);
    }

    @Test
    void sendsLeadReminderInsideTheLeadWindow() {
        // 上海 06:45，待办 09:30 → 距开始 2h45m，落在 (now, now + 3h] 内。
        WeComTodoReminderScheduler scheduler = schedulerAt(Instant.parse("2026-09-19T22:45:00Z"), 0);
        TodoItemEntity item = todo("确认青岛仓出库时间", LocalTime.of(9, 30));
        when(todoItemMapper.listDailyReminderCandidates(TODAY)).thenReturn(List.of());
        when(todoItemMapper.listLeadReminderCandidates(TODAY,
                LocalDateTime.of(2026, 9, 20, 6, 45),
                LocalDateTime.of(2026, 9, 20, 9, 45), 300)).thenReturn(List.of(item));
        when(todoItemMapper.claimLeadReminder(eq(item.getId()), anyString(), anyInt())).thenReturn(1);
        when(todoItemMapper.markLeadReminderSent(eq(item.getId()), anyString())).thenReturn(1);

        assertEquals(1, scheduler.pollOnce());

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sendService).sendToBoundUser(eq(USER), text.capture());
        assertEquals("待办提醒 · 09:30 即将开始\n确认青岛仓出库时间", text.getValue());
    }

    @Test
    void releasesTheLeadMarkerWhenSendingFails() {
        WeComTodoReminderScheduler scheduler = schedulerAt(Instant.parse("2026-09-19T22:45:00Z"), 0);
        TodoItemEntity item = todo("确认青岛仓出库时间", LocalTime.of(9, 30));
        when(todoItemMapper.listDailyReminderCandidates(TODAY)).thenReturn(List.of());
        when(todoItemMapper.listLeadReminderCandidates(any(), any(), any(), anyInt())).thenReturn(List.of(item));
        when(todoItemMapper.claimLeadReminder(eq(item.getId()), anyString(), anyInt())).thenReturn(1);
        when(sendService.sendToBoundUser(any(), anyString()))
                .thenThrow(new IllegalStateException("企业微信接口超时"));

        assertEquals(0, scheduler.pollOnce());
        verify(todoItemMapper).releaseLeadReminder(eq(item.getId()), anyString());
    }

    @Test
    void doesNotSendLeadReminderWhenAnotherTickAlreadyClaimedIt() {
        WeComTodoReminderScheduler scheduler = schedulerAt(Instant.parse("2026-09-19T22:45:00Z"), 0);
        TodoItemEntity item = todo("确认青岛仓出库时间", LocalTime.of(9, 30));
        when(todoItemMapper.listDailyReminderCandidates(TODAY)).thenReturn(List.of());
        when(todoItemMapper.listLeadReminderCandidates(any(), any(), any(), anyInt())).thenReturn(List.of(item));
        when(todoItemMapper.claimLeadReminder(eq(item.getId()), anyString(), anyInt())).thenReturn(0);

        assertEquals(0, scheduler.pollOnce());
        verifyNoInteractions(sendService);
    }

    @Test
    void recordsDailySummaryFailureForTheNextTick() {
        WeComTodoReminderScheduler scheduler = schedulerAt(Instant.parse("2026-09-19T16:05:00Z"), 0);
        when(todoItemMapper.listDailyReminderCandidates(TODAY)).thenReturn(List.of(todo("确认仓库", null)));
        when(dailyReminderMapper.claim(eq(USER), eq(TODAY), eq(1), eq(5), anyString(), anyInt())).thenReturn(DAILY_CLAIM);
        when(sendService.sendToBoundUser(any(), anyString()))
                .thenThrow(new IllegalStateException("access_token 已过期"));

        assertEquals(0, scheduler.pollOnce());
        verify(dailyReminderMapper).markFailed(eq(USER), eq(TODAY), anyString(), contains("access_token 已过期"), eq(5));
        verify(dailyReminderMapper, never()).markSent(any(), any(), any(), any());
    }

    @Test
    void truncatesOverlongDailySummariesWithinTheWeComByteLimit() {
        WeComTodoReminderScheduler scheduler = schedulerAt(Instant.parse("2026-09-19T16:05:00Z"), 0);
        List<TodoItemEntity> items = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            items.add(todo("很长的待办标题需要占掉不少字节" + index, LocalTime.of(9, 0)));
        }
        when(todoItemMapper.listDailyReminderCandidates(TODAY)).thenReturn(items);
        when(dailyReminderMapper.claim(any(), any(), anyInt(), anyInt(), anyString(), anyInt())).thenReturn(DAILY_CLAIM);
        when(sendService.sendToBoundUser(any(), anyString())).thenReturn(sentResult("msg-2"));

        scheduler.pollOnce();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sendService).sendToBoundUser(any(), text.capture());
        assertTrue(text.getValue().getBytes(StandardCharsets.UTF_8).length <= 1800,
                "正文必须留在企微 2048 字节上限内，实际 "
                        + text.getValue().getBytes(StandardCharsets.UTF_8).length);
        assertTrue(text.getValue().contains("… 另有"), "被截断时要说明还剩几项");
    }

    @Test
    void registersWithAFixedMinuteCadenceOnItsOwnScheduleChain() {
        schedulerAt(Instant.parse("2026-09-19T16:05:00Z"), 0);

        verify(pollingScheduler).register(any(),
                eq(Duration.ofSeconds(60)), eq(Duration.ofSeconds(60)));
    }

    private WeComTodoReminderScheduler schedulerAt(Instant now, int dailyHour) {
        AppConfig config = mock(AppConfig.class);
        when(config.todoReminderZone()).thenReturn("Asia/Shanghai");
        when(config.todoReminderLeadHours()).thenReturn(3);
        when(config.todoReminderDailyHour()).thenReturn(dailyHour);
        when(config.todoReminderMaxAttempts()).thenReturn(5);
        when(config.todoReminderClaimLeaseSeconds()).thenReturn(300);
        when(config.todoReminderIntervalMs()).thenReturn(60_000L);
        WeComTodoReminderScheduler scheduler = new WeComTodoReminderScheduler(
                todoItemMapper, dailyReminderMapper, sendService, pollingScheduler, config,
                Clock.fixed(now, ZoneOffset.UTC));
        scheduler.register();
        return scheduler;
    }

    private static TodoItemEntity todo(String title, LocalTime dueTime) {
        TodoItemEntity item = new TodoItemEntity();
        item.setId(UUID.randomUUID());
        item.setUserId(USER);
        item.setDueDate(TODAY);
        item.setTitle(title);
        item.setDueTime(dueTime);
        return item;
    }

    private static WeComSendService.SendResult sentResult(String messageId) {
        return new WeComSendService.SendResult(messageId, "agent", "user", "", "sent");
    }
}
