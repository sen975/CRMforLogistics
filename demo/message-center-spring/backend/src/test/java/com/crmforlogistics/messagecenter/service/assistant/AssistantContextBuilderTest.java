package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话事实的构造。
 *
 * <p>核心用例是<b>时区</b>：本项目的 {@code Clock} bean 是 {@code Clock.systemUTC()}，
 * 因此「今天是哪天」必须用 {@code clock.withZone(业务时区)} 取。开发机恰好是 Asia/Shanghai 时，
 * 写成 {@code LocalDate.now(clock)} 也能通过大部分用例 —— 所以这里专门造了一个
 * 「UTC 日期与上海日期不同」的时刻把两种写法分开。
 *
 * <p>候选不会在这里加载；候选检索由各自只读工具负责，本类只验证第一轮的日期/时区事实。
 */
class AssistantContextBuilderTest {

    private AssistantContextBuilder builder(String instant, String zone) {
        AppConfig config = mock(AppConfig.class);
        when(config.todoReminderZone()).thenReturn(zone);
        return new AssistantContextBuilder(config,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void todayFollowsTheBusinessZoneNotUtc() {
        // UTC 还是 9 月 21 日 20:00，上海已经是 9 月 22 日 04:00。
        AssistantContext context = builder("2026-09-21T20:00:00Z", "Asia/Shanghai").build();

        assertThat(context.today()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(context.zoneId()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void utcClockWouldHaveProducedADifferentDaySoTheZoneReallyMatters() {
        // 同上一个时刻，但业务时区配成 UTC —— 结果必须不同，否则上一条用例并没有在验时区。
        AssistantContext context = builder("2026-09-21T20:00:00Z", "UTC").build();

        assertThat(context.today()).isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void weekdayIsWrittenInChinese() {
        AssistantContext context = builder("2026-09-21T02:00:00Z", "Asia/Shanghai").build();

        assertThat(context.weekday()).isEqualTo("星期一");
    }

    @Test
    void initialContextDoesNotLoadCandidatesBeforeTheModelChoosesAReadTool() {
        AssistantContext context = builder("2026-09-21T02:00:00Z", "Asia/Shanghai")
                .build();

        assertThat(context.candidateSets()).isEmpty();
    }

}
