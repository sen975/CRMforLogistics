package com.crmforlogistics.messagecenter;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class WeComDailySummaryScheduler implements AutoCloseable {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final Config config;
    private final TimedAction dailyAction;
    private final TimedAction workerAction;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private LocalDate lastDailyRun;

    public WeComDailySummaryScheduler(Config config, WeComDailySummaryService service) {
        this(config, service::run, service::tick, Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "wecom-daily-summary");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    private WeComDailySummaryScheduler(Config config, TimedAction dailyAction,
                                       TimedAction workerAction, Clock clock,
                                       ScheduledExecutorService executor) {
        this.config = Objects.requireNonNull(config, "config");
        this.dailyAction = Objects.requireNonNull(dailyAction, "dailyAction");
        this.workerAction = Objects.requireNonNull(workerAction, "workerAction");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = executor;
    }

    static WeComDailySummaryScheduler forTests(Config config, TimedAction dailyAction,
                                                TimedAction workerAction) {
        return new WeComDailySummaryScheduler(config, dailyAction, workerAction,
                Clock.systemUTC(), null);
    }

    public void start() {
        if (executor == null) return;
        executor.scheduleAtFixedRate(() -> tick(clock.instant()), 0, 60, TimeUnit.SECONDS);
    }

    public synchronized void tick(Instant now) {
        var local = now.atZone(BUSINESS_ZONE);
        LocalDate today = local.toLocalDate();
        LocalTime scheduled = LocalTime.of(
                config.wecomDailySummaryHour(), config.wecomDailySummaryMinute());
        try {
            if (!local.toLocalTime().isBefore(scheduled) && !today.equals(lastDailyRun)) {
                dailyAction.run(now);
                lastDailyRun = today;
            }
            workerAction.run(now);
        } catch (Exception exception) {
            System.err.println("wecom.daily_summary.scheduler_failed");
        }
    }

    @Override
    public void close() {
        if (executor != null) executor.shutdownNow();
    }

    interface TimedAction {
        void run(Instant now) throws Exception;
    }
}
