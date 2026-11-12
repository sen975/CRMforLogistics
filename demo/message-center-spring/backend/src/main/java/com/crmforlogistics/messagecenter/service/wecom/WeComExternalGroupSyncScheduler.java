package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.mapper.WeComExternalGroupSyncMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComExternalGroupSyncScheduler {
    private static final Duration REFRESH_INTERVAL = Duration.ofHours(24);
    private final WeComExternalGroupSyncMapper syncs;
    private final WeComExternalGroupSyncWorker worker;

    public WeComExternalGroupSyncScheduler(WeComExternalGroupSyncMapper syncs,
                                           WeComExternalGroupSyncWorker worker) {
        this.syncs = syncs;
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.wecom-external-group-sync-poll-interval-seconds:60}000",
            initialDelay = 15000)
    public void run() {
        Instant now = Instant.now();
        syncs.enqueueDue(now.minus(REFRESH_INTERVAL), now);
        worker.runOnce(now, 5);
    }
}
