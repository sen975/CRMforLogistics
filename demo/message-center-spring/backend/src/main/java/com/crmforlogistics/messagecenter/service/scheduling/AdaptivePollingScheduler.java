package com.crmforlogistics.messagecenter.service.scheduling;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一的自适应轮询节拍器。
 *
 * <p>把原先各自 {@code @Scheduled(fixedDelay = 1000)} 的秒级轮询收进来统一驱动：每轮结束按
 * {@link PollingTask#pollOnce()} 报告的处理条数决定下一次间隔 —— 有活立刻回到基线，连续空转则
 * 指数退避到上限。这样"每秒一次、注定查不到"的存在性查询不再长期占用数据库连接。
 *
 * <p>退避不会放大端到端延迟：入队方可以在提交事务后调用 {@link #wake(String)} 立即把节拍复位，
 * 新任务无需等满一个退避周期。
 *
 * <p>每个任务持有一条独立的调度链、互不阻塞。这一点很关键：Spring 默认
 * {@code spring.task.scheduling.pool.size=1}，所有 {@code @Scheduled} 会挤在同一个线程上串行执行，
 * 一个调外部 API 的慢任务会把其余任务全部饿死。
 */
@Component
@EnableScheduling
public class AdaptivePollingScheduler {
    private static final Logger log = LoggerFactory.getLogger(AdaptivePollingScheduler.class);
    private static final long STOP_TIMEOUT_SECONDS = 5;

    private final ScheduledThreadPoolExecutor executor;
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    private final long defaultCeilingMs;
    private final AtomicBoolean closed = new AtomicBoolean();

    public AdaptivePollingScheduler(@Value("${app.polling.threads:4}") int threads,
                                    @Value("${app.polling.ceiling-ms:5000}") long defaultCeilingMs) {
        ScheduledThreadPoolExecutor scheduled = new ScheduledThreadPoolExecutor(
                Math.max(1, threads), namedThreads("adaptive-polling-"));
        scheduled.setRemoveOnCancelPolicy(true);
        this.executor = scheduled;
        this.defaultCeilingMs = Math.max(1L, defaultCeilingMs);
    }

    /** 按全局退避上限注册一个任务。首轮延迟一个基线周期，避免启动瞬间与其它初始化抢资源。 */
    public void register(PollingTask task, Duration baseline) {
        register(task, baseline, Duration.ofMillis(defaultCeilingMs));
    }

    public void register(PollingTask task, Duration baseline, Duration ceiling) {
        if (closed.get()) {
            throw new IllegalStateException("adaptive polling scheduler is closed");
        }
        Registration registration = new Registration(task, new Backoff(
                baseline.toMillis(), ceiling.toMillis()));
        Registration previous = registrations.put(task.name(), registration);
        if (previous != null) {
            previous.cancel();
            log.warn("event=polling.duplicate_registration task={}", task.name());
        }
        long baselineMs = registration.backoff.currentMs();
        schedule(registration, baselineMs, registration.generation.get());
        log.info("event=polling.registered task={} baselineMs={} ceilingMs={}",
                task.name(), baselineMs, registration.backoff.ceilingMs());
    }

    /**
     * 立即把任务的节拍复位到基线并触发一次。
     *
     * <p>若任务此刻正在执行，则不在中途插入第二轮，只把代次加一 —— 该轮结束时调度器会看到代次
     * 变化并立刻按基线重排，因此不会出现两条并行的调度链。
     */
    public void wake(String name) {
        Registration registration = registrations.get(name);
        if (registration == null || closed.get()) return;
        registration.backoff.reset();
        long generation = registration.generation.incrementAndGet();
        ScheduledFuture<?> future = registration.future;
        if (future != null && future.cancel(false)) {
            schedule(registration, 0L, generation);
        }
    }

    private void schedule(Registration registration, long delayMs, long generation) {
        if (closed.get()) return;
        registration.future = executor.schedule(
                () -> tick(registration, generation), delayMs, TimeUnit.MILLISECONDS);
    }

    private void tick(Registration registration, long generation) {
        long nextDelayMs;
        try {
            int processed = registration.task.pollOnce();
            nextDelayMs = processed > 0
                    ? registration.backoff.onWork()
                    : registration.backoff.onIdle();
        } catch (Exception failure) {
            // 这一层必须兜住异常：任务抛出后如果放任它冒泡，这个调度链就不会再排下一轮，
            // 该任务将永久停摆。退避而不是立刻重试，避免故障期间空转刷日志。
            log.warn("event=polling.task_failed task={} failureType={} failureMessage={}",
                    registration.task.name(), failure.getClass().getSimpleName(),
                    safeMessage(failure));
            nextDelayMs = registration.backoff.onIdle();
        }
        if (registrations.get(registration.task.name()) != registration) {
            return;
        }
        if (registration.generation.get() != generation) {
            nextDelayMs = registration.backoff.reset();
            generation = registration.generation.get();
        }
        schedule(registration, nextDelayMs, generation);
    }

    /** 当前间隔，供测试与诊断使用。 */
    Duration currentInterval(String name) {
        Registration registration = registrations.get(name);
        return registration == null ? null : Duration.ofMillis(registration.backoff.currentMs());
    }

    @PreDestroy
    public void shutdown() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
        try {
            if (!executor.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Adaptive polling scheduler did not stop within {}s", STOP_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return "";
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class Registration {
        private final PollingTask task;
        private final Backoff backoff;
        private final AtomicLong generation = new AtomicLong();
        private volatile ScheduledFuture<?> future;

        private Registration(PollingTask task, Backoff backoff) {
            this.task = task;
            this.backoff = backoff;
        }

        private void cancel() {
            ScheduledFuture<?> current = future;
            if (current != null) current.cancel(false);
        }
    }

    /**
     * 空转退避梯度：每空转一轮翻倍，到上限为止；一旦有活立刻回基线。
     * 基线 1s、上限 5s 时梯度为 1s → 2s → 4s → 5s。
     */
    static final class Backoff {
        private final long baselineMs;
        private final long ceilingMs;
        private long currentMs;

        Backoff(long baselineMs, long ceilingMs) {
            this.baselineMs = Math.max(1L, baselineMs);
            this.ceilingMs = Math.max(this.baselineMs, ceilingMs);
            this.currentMs = this.baselineMs;
        }

        synchronized long onIdle() {
            this.currentMs = Math.min(this.currentMs * 2L, this.ceilingMs);
            return this.currentMs;
        }

        synchronized long onWork() {
            this.currentMs = this.baselineMs;
            return this.currentMs;
        }

        synchronized long reset() {
            this.currentMs = this.baselineMs;
            return this.currentMs;
        }

        synchronized long currentMs() {
            return this.currentMs;
        }

        synchronized long ceilingMs() {
            return this.ceilingMs;
        }
    }
}
