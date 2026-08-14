package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

@Component
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank() and '${app.wecom-daily-summary-enabled:false}' == 'true'")
public class WeComDailySummaryScheduler {
    private static final Logger log = LoggerFactory.getLogger(WeComDailySummaryScheduler.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final AppConfig config;
    private final WeComDailySummaryService service;
    private final Clock clock;
    private final WeComStartupGate startupGate;
    private LocalDate lastDailyRun;

    @Autowired
    public WeComDailySummaryScheduler(AppConfig config, WeComDailySummaryService service,
                                      WeComStartupGate startupGate) {
        this(config, service, Clock.systemUTC(), startupGate);
    }

    WeComDailySummaryScheduler(AppConfig config, WeComDailySummaryService service, Clock clock,
                               WeComStartupGate startupGate) {
        this.config = config;
        this.service = service;
        this.clock = clock;
        this.startupGate = startupGate;
    }

    @Scheduled(fixedDelayString = "60000", initialDelay = 1000)
    public synchronized void tick() {
        Instant now = clock.instant();
        var local = now.atZone(BUSINESS_ZONE);
        LocalDate today = local.toLocalDate();
        LocalTime scheduled = LocalTime.of(
                config.wecomDailySummaryHour(), config.wecomDailySummaryMinute());
        try {
            startupGate.requireOpen();
            if (!local.toLocalTime().isBefore(scheduled) && !today.equals(lastDailyRun)) {
                service.run(now);
                lastDailyRun = today;
            }
            service.tick(now);
        } catch (Exception exception) {
            log.warn("wecom daily summary scheduler failed", exception);
        }
    }
}
