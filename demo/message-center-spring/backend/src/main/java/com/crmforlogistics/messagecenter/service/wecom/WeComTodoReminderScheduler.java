package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComSendService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecenter.mapper.TodoDailyReminderMapper;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import com.crmforlogistics.messagecenter.service.scheduling.AdaptivePollingScheduler;
import com.crmforlogistics.messagecenter.service.scheduling.PollingTask;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 待办提醒的服务端调度：把「前端点按钮才发」换成两个自动触发点。
 *
 * <ol>
 *   <li><b>当天汇总</b> —— 当天 {@code dailyHour} 点（默认 0 点）起，当天还没成功发过汇总的用户
 *       各收到一条当天清单。判据不是"现在正好是 0 点"，而是"当天已经跨过 0 点、且当天还没发过"，
 *       因此服务在 0 点前后重启、或用户 0 点后才补建当天待办，都不会漏掉这条汇总；一天仍然最多一条。</li>
 *   <li><b>开始前 N 小时</b> —— 只有带具体时间的待办参与，进入 {@code (now, now + leadHours]}
 *       窗口时逐条发送。上界是"还没开始"，避免停机恢复后把早已开始的事项补成"即将开始"。</li>
 * </ol>
 *
 * <p>节奏交给 {@link AdaptivePollingScheduler}：baseline 与 ceiling 都取配置间隔（默认 60 秒），
 * 于是一个"每分钟一次"的任务照样跑在自己的调度链上 —— 不受 Spring 默认
 * {@code spring.task.scheduling.pool.size=1} 影响，也不会把一次企微 HTTP 调用变成其余
 * {@code @Scheduled} 的排队时间。空转时不做任何事、不打日志。
 *
 * <p>幂等分两处落库，重复 tick 不会重复发送：当天汇总用 {@code todo_daily_reminders} 的
 * {@code (user_id, reminder_date)} 唯一约束抢发送权；提前提醒用待办自身的
 * {@code lead_reminder_sent_at} 先占位、发送失败再退回。
 */
@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
@ConditionalOnProperty(
        name = "app.todo-reminder-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class WeComTodoReminderScheduler implements PollingTask {
    static final String TASK_NAME = "todo-reminder";

    private static final Logger LOG = LoggerFactory.getLogger(WeComTodoReminderScheduler.class);

    /** 企微 text 消息正文上限 2048 字节，留出余量后按 UTF-8 字节数截断当天清单。 */
    private static final int MESSAGE_MAX_BYTES = 1800;

    /**
     * 单个 tick 的发送上限。当天汇总要按用户逐个发，用户一多就会在一个 tick 里连打几十次企微接口；
     * 超出预算的用户顺延到下一个 tick（他们还没抢到台账行，不会被误判成已发）。
     */
    private static final int MAX_SENDS_PER_TICK = 20;

    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("M月d日");
    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");

    private final TodoItemMapper todoItemMapper;
    private final TodoDailyReminderMapper dailyReminderMapper;
    private final WeComSendService sendService;
    private final AdaptivePollingScheduler scheduler;
    private final Clock clock;
    private final ZoneId zone;
    private final int leadHours;
    private final int dailyHour;
    private final int maxAttempts;
    private final int claimLeaseSeconds;
    private final Duration interval;

    public WeComTodoReminderScheduler(TodoItemMapper todoItemMapper,
                                      TodoDailyReminderMapper dailyReminderMapper,
                                      WeComSendService sendService,
                                      AdaptivePollingScheduler scheduler,
                                      AppConfig config,
                                      Clock clock) {
        this.todoItemMapper = todoItemMapper;
        this.dailyReminderMapper = dailyReminderMapper;
        this.sendService = sendService;
        this.scheduler = scheduler;
        this.clock = clock;
        this.zone = ZoneId.of(config.todoReminderZone());
        this.leadHours = Math.max(1, config.todoReminderLeadHours());
        this.dailyHour = Math.min(23, Math.max(0, config.todoReminderDailyHour()));
        this.maxAttempts = Math.max(1, config.todoReminderMaxAttempts());
        this.claimLeaseSeconds = Math.max(1, config.todoReminderClaimLeaseSeconds());
        this.interval = Duration.ofMillis(Math.max(1000L, config.todoReminderIntervalMs()));
    }

    @PostConstruct
    void register() {
        scheduler.register(this, interval, interval);
    }

    @Override
    public String name() {
        return TASK_NAME;
    }

    @Override
    public int pollOnce() {
        ZonedDateTime localNow = ZonedDateTime.now(clock.withZone(zone));
        LocalDate today = localNow.toLocalDate();
        int budget = MAX_SENDS_PER_TICK;
        int sent = 0;
        if (!localNow.toLocalTime().isBefore(LocalTime.of(dailyHour, 0))) {
            int used = sendDailySummaries(today, budget);
            sent += used;
            budget -= used;
        }
        if (budget > 0) {
            sent += sendLeadReminders(today, localNow.toLocalDateTime(), budget);
        }
        return sent;
    }

    /** 当天汇总：一个用户一条，内容是该用户当天所有未完成待办。 */
    private int sendDailySummaries(LocalDate today, int budget) {
        List<TodoItemEntity> candidates = todoItemMapper.listDailyReminderCandidates(today);
        if (candidates.isEmpty()) {
            return 0;
        }
        Map<UUID, List<TodoItemEntity>> byUser = candidates.stream()
                .collect(Collectors.groupingBy(TodoItemEntity::getUserId,
                        LinkedHashMap::new, Collectors.toList()));
        int sent = 0;
        for (Map.Entry<UUID, List<TodoItemEntity>> entry : byUser.entrySet()) {
            if (sent >= budget) {
                break;
            }
            UUID userId = entry.getKey();
            List<TodoItemEntity> items = entry.getValue();
            String claimToken = UUID.randomUUID().toString();
            String claimedToken = dailyReminderMapper.claim(userId, today, items.size(), maxAttempts,
                    claimToken, claimLeaseSeconds);
            if (claimedToken == null) {
                continue;
            }
            try {
                WeComSendService.SendResult result =
                        sendService.sendToBoundUser(userId, dailySummaryText(today, items));
                if (dailyReminderMapper.markSent(userId, today, claimedToken, result.messageId()) != 1) {
                    LOG.warn("event=todo.daily_reminder_claim_lost userId={} date={}", userId, today);
                    continue;
                }
                sent++;
                LOG.info("event=todo.daily_reminder_sent userId={} date={} taskCount={} messageId={}",
                        userId, today, items.size(), result.messageId());
            } catch (RuntimeException failure) {
                String error = failureMessage(failure);
                dailyReminderMapper.markFailed(userId, today, claimedToken, error, maxAttempts);
                LOG.warn("event=todo.daily_reminder_failed userId={} date={} taskCount={} "
                                + "attempts={} failureType={} failureMessage={}",
                        userId, today, items.size(), maxAttempts,
                        failure.getClass().getSimpleName(), error);
            }
        }
        return sent;
    }

    /** 开始前 N 小时：逐条发送，先抢标记再发送，失败退回标记留待下个 tick 重试。 */
    private int sendLeadReminders(LocalDate today, LocalDateTime localNow, int budget) {
        List<TodoItemEntity> candidates = todoItemMapper.listLeadReminderCandidates(
                today, localNow, localNow.plusHours(leadHours), claimLeaseSeconds);
        int sent = 0;
        for (TodoItemEntity item : candidates) {
            if (sent >= budget) {
                break;
            }
            String claimToken = UUID.randomUUID().toString();
            if (todoItemMapper.claimLeadReminder(item.getId(), claimToken, claimLeaseSeconds) != 1) {
                continue;
            }
            try {
                sendService.sendToBoundUser(item.getUserId(), leadReminderText(item));
                if (todoItemMapper.markLeadReminderSent(item.getId(), claimToken) != 1) {
                    LOG.warn("event=todo.lead_reminder_claim_lost userId={} todoId={}",
                            item.getUserId(), item.getId());
                    continue;
                }
                sent++;
                LOG.info("event=todo.lead_reminder_sent userId={} todoId={} dueAt={}",
                        item.getUserId(), item.getId(), item.getDueTime());
            } catch (RuntimeException failure) {
                // 退回标记，让这条待办下个 tick 还有机会 —— 窗口内每分钟一次，直到发出去或窗口关闭。
                todoItemMapper.releaseLeadReminder(item.getId(), claimToken);
                LOG.warn("event=todo.lead_reminder_failed userId={} todoId={} dueAt={} "
                                + "failureType={} failureMessage={}",
                        item.getUserId(), item.getId(), item.getDueTime(),
                        failure.getClass().getSimpleName(), failureMessage(failure));
            }
        }
        return sent;
    }

    private String dailySummaryText(LocalDate date, List<TodoItemEntity> items) {
        StringBuilder text = new StringBuilder(date.format(DATE_LABEL)).append(" 待办提醒");
        int shown = 0;
        for (TodoItemEntity item : items) {
            String line = "\n○ " + (item.getDueTime() == null
                    ? "" : item.getDueTime().format(TIME_LABEL) + " ") + item.getTitle();
            if (utf8Length(text) + utf8Length(line) > MESSAGE_MAX_BYTES) {
                break;
            }
            text.append(line);
            shown++;
        }
        if (shown < items.size()) {
            text.append("\n… 另有 ").append(items.size() - shown).append(" 项");
        }
        return text.toString();
    }

    private static String leadReminderText(TodoItemEntity item) {
        return "待办提醒 · " + item.getDueTime().format(TIME_LABEL) + " 即将开始\n" + item.getTitle();
    }

    private static int utf8Length(CharSequence value) {
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private static String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}
