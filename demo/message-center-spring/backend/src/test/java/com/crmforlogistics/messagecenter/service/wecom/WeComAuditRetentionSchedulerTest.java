package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeComAuditRetentionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void doesNotRunRetentionBeforeStartupGateOpens() {
        WeComAuditRetentionService service = mock(WeComAuditRetentionService.class);
        WeComStartupGate gate = mock(WeComStartupGate.class);
        doThrow(new IllegalStateException("gate closed")).when(gate).requireOpen();
        WeComAuditRetentionScheduler scheduler = scheduler(service, gate);

        scheduler.tick();

        verifyNoInteractions(service);
    }

    @Test
    void runsRetentionWithClockInstantAfterStartupGateOpens() {
        WeComAuditRetentionService service = mock(WeComAuditRetentionService.class);
        WeComStartupGate gate = mock(WeComStartupGate.class);
        when(service.enforce(NOW)).thenReturn(
                new WeComAuditRetentionService.RetentionRunResult("success", 0, 0, 0, 1));
        WeComAuditRetentionScheduler scheduler = scheduler(service, gate);

        scheduler.tick();

        verify(gate).requireOpen();
        verify(service).enforce(eq(NOW));
    }

    @Test
    void swallowsRetentionFailureAtSchedulerBoundary() {
        WeComAuditRetentionService service = mock(WeComAuditRetentionService.class);
        WeComStartupGate gate = mock(WeComStartupGate.class);
        doThrow(new IllegalStateException("db down")).when(service).enforce(NOW);
        WeComAuditRetentionScheduler scheduler = scheduler(service, gate);

        scheduler.tick();
    }

    @Test
    void schedulerRequiresEnabledWeComModuleAndSuiteId() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(WeComAuditRetentionService.class,
                        () -> mock(WeComAuditRetentionService.class))
                .withBean(WeComStartupGate.class, () -> mock(WeComStartupGate.class))
                .withUserConfiguration(WeComAuditRetentionScheduler.class);

        runner.withPropertyValues("app.wecom-enabled=false", "app.wecom-suite-id=suite")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(WeComAuditRetentionScheduler.class));
        runner.withPropertyValues("app.wecom-enabled=true", "app.wecom-suite-id=")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(WeComAuditRetentionScheduler.class));
        runner.withPropertyValues("app.wecom-enabled=true", "app.wecom-suite-id=suite")
                .run(context -> assertThat(context)
                        .hasSingleBean(WeComAuditRetentionScheduler.class));
    }

    private static WeComAuditRetentionScheduler scheduler(WeComAuditRetentionService service,
                                                            WeComStartupGate gate) {
        return new WeComAuditRetentionScheduler(service, gate,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
