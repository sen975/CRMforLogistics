package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AppConfig;
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
 * <p>候选清单不由本类加载；各只读工具通过自己的 provider/service 查询，并把有界候选交回编排层。
 */
@Component
public class AssistantContextBuilder {

    private final AppConfig appConfig;
    private final Clock clock;

    public AssistantContextBuilder(AppConfig appConfig,
                                   Clock clock) {
        this.appConfig = appConfig;
        this.clock = clock;
    }

    /**
     * 组装第一轮会话事实。候选不在这里预加载；只读工具命中后通过 {@code ToolResult.discovered}
     * 回灌到 {@link AssistantContext#withCandidateSet}，这样无关请求不会支付候选清单的上下文成本。
     */
    public AssistantContext build() {
        ZoneId zone = ZoneId.of(appConfig.todoReminderZone());
        LocalDate today = ZonedDateTime.now(clock.withZone(zone)).toLocalDate();

        return new AssistantContext(zone.getId(), today, weekdayOf(today), List.of());
    }

    /**
     * 中文星期名。用 {@link TextStyle#FULL} + {@link Locale#CHINA} 而不是手写数组：
     * 手写映射会在有人改日期格式时不受影响，从而安静地和 {@code LocalDate} 的语义脱节。
     */
    static String weekdayOf(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
    }
}
