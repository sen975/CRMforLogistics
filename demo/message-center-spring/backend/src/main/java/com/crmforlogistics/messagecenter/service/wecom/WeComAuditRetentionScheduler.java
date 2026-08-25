package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComAuditRetentionScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(WeComAuditRetentionScheduler.class);

    private final WeComAuditRetentionService retentionService;
    private final WeComStartupGate startupGate;
    private final Clock clock;

    @Autowired
    public WeComAuditRetentionScheduler(WeComAuditRetentionService retentionService,
                                        WeComStartupGate startupGate) {
        this(retentionService, startupGate, Clock.systemUTC());
    }

    WeComAuditRetentionScheduler(WeComAuditRetentionService retentionService,
                                 WeComStartupGate startupGate, Clock clock) {
        this.retentionService = retentionService;
        this.startupGate = startupGate;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${app.wecom-audit-cleanup-interval-seconds:3600}000",
            initialDelay = 60000)
    public void tick() {
        try {
            startupGate.requireOpen();
            WeComAuditRetentionService.RetentionRunResult result =
                    retentionService.enforce(clock.instant());
            if ("failed".equals(result.status())) {
                LOG.warn("event=wecom.audit_retention status=failed viewerDeleted={} "
                                + "authorizationDeleted={} apiDeleted={} batches={}",
                        result.viewerDeleted(), result.authorizationDeleted(), result.apiDeleted(),
                        result.batches());
            }
        } catch (RuntimeException exception) {
            LOG.warn("event=wecom.audit_retention status=scheduler_failed errorType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
