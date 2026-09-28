package com.crmforlogistics.messagecenter.service.chatapp.outbox;

import com.crmforlogistics.messagecenter.service.scheduling.AdaptivePollingScheduler;
import com.crmforlogistics.messagecenter.service.scheduling.PollingTask;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 出站 outbox 的轮询入口。
 *
 * <p>节拍交给 {@link AdaptivePollingScheduler}：有活时保持基线 1s，连续空转则退避到全局上限。
 * 消息落库提交后由 {@code MessageSendApplicationService} 主动唤醒复位，退避不会放大发送延迟。
 */
@Component
@ConditionalOnProperty(name = "app.chatapp-outbox-enabled", havingValue = "true", matchIfMissing = true)
public class MessageOutboxScheduler implements PollingTask {
    /** 唤醒时使用的任务标识；{@code MessageSendApplicationService} 依赖它。 */
    public static final String TASK_NAME = "chatapp-outbox";

    private static final int BATCH_SIZE = 20;
    private static final Duration BASELINE = Duration.ofSeconds(1);

    private final MessageOutboxWorker worker;
    private final AdaptivePollingScheduler scheduler;

    public MessageOutboxScheduler(MessageOutboxWorker worker, AdaptivePollingScheduler scheduler) {
        this.worker = worker;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void register() {
        scheduler.register(this, BASELINE);
    }

    @Override
    public String name() {
        return TASK_NAME;
    }

    @Override
    public int pollOnce() {
        int recovered = worker.recoverExpiredProcessing();
        int processed = worker.claimAndProcess(BATCH_SIZE);
        return recovered + processed;
    }
}
