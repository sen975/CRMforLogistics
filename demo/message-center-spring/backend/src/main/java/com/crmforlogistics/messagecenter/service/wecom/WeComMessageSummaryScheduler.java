package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.service.scheduling.AdaptivePollingScheduler;
import com.crmforlogistics.messagecenter.service.scheduling.PollingTask;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 企微官方消息概要的轮询入口。
 *
 * <p>调度条件与 {@link WeComMessageSummaryWorker} 保持一致：只有企微套件已配置、且显式打开
 * {@code app.wecom-message-summary-enabled} 时才注册。
 */
@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank() and '${app.wecom-message-summary-enabled:false}' == 'true'")
public class WeComMessageSummaryScheduler implements PollingTask {
    static final String TASK_NAME = "wecom-message-summary";

    private final WeComMessageSummaryWorker worker;
    private final AdaptivePollingScheduler scheduler;
    private final Duration baseline;

    public WeComMessageSummaryScheduler(
            WeComMessageSummaryWorker worker,
            AdaptivePollingScheduler scheduler,
            @Value("${app.wecom-message-summary-poll-interval-seconds:1}") long baselineSeconds) {
        this.worker = worker;
        this.scheduler = scheduler;
        this.baseline = Duration.ofSeconds(Math.max(1L, baselineSeconds));
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
        return worker.runOnce(Instant.now());
    }
}
