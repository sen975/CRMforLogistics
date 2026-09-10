package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
@ConditionalOnProperty(
        name = "app.wecom-user-notification-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class WeComUserNotificationScheduler {
    private static final int BATCH_SIZE = 20;

    private final WeComUserNotificationWorker worker;

    public WeComUserNotificationScheduler(WeComUserNotificationWorker worker) {
        this.worker = worker;
    }

    @Scheduled(
            fixedDelayString = "${app.wecom-user-notification-worker-interval-ms:1000}",
            initialDelayString = "${app.wecom-user-notification-worker-initial-delay-ms:1000}")
    public void run() {
        worker.recoverStuck();
        worker.runAvailable("wecom-user-notification-" + UUID.randomUUID(), BATCH_SIZE);
    }
}
