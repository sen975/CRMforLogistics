package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.service.scheduling.AdaptivePollingScheduler;
import com.crmforlogistics.messagecenter.service.scheduling.PollingTask;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 非企微渠道入站消息的企微应用消息提醒轮询入口，节拍交由 {@link AdaptivePollingScheduler} 统一驱动。
 */
@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
@ConditionalOnProperty(
        name = "app.wecom-user-notification-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class WeComUserNotificationScheduler implements PollingTask {
    static final String TASK_NAME = "wecom-user-notification";

    private static final int BATCH_SIZE = 20;

    private final WeComUserNotificationWorker worker;
    private final AdaptivePollingScheduler scheduler;
    private final Duration baseline;

    public WeComUserNotificationScheduler(
            WeComUserNotificationWorker worker,
            AdaptivePollingScheduler scheduler,
            @Value("${app.wecom-user-notification-worker-interval-ms:1000}") long baselineMs) {
        this.worker = worker;
        this.scheduler = scheduler;
        this.baseline = Duration.ofMillis(Math.max(1L, baselineMs));
    }

    @PostConstruct
    void register() {
        scheduler.register(this, baseline);
    }

    @Override
    public String name() {
        return TASK_NAME;
    }

    @Override
    public int pollOnce() {
        int recovered = worker.recoverStuck();
        int processed = worker.runAvailable("wecom-user-notification-" + UUID.randomUUID(), BATCH_SIZE);
        return recovered + processed;
    }
}
