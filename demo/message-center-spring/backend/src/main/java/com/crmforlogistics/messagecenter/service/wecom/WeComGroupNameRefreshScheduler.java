package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComGroupNameRefreshScheduler {
    private final WeComGroupNameRefreshWorker worker;

    public WeComGroupNameRefreshScheduler(WeComGroupNameRefreshWorker worker) { this.worker = worker; }

    @Scheduled(fixedDelayString = "${app.wecom-group-name-refresh-poll-interval-seconds:5}000", initialDelay = 5000)
    public void run() { worker.runOnce(Instant.now(), 10); }
}
