package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.mapper.EmailSubmissionMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Component
public class EmailSubmissionLeaseKeeper {
    private static final Logger log = LoggerFactory.getLogger(EmailSubmissionLeaseKeeper.class);
    private static final long HEARTBEAT_SECONDS = 20;

    private final EmailSubmissionMapper submissionMapper;
    private final ConcurrentMap<UUID, UUID> activeLeases = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public EmailSubmissionLeaseKeeper(EmailSubmissionMapper submissionMapper) {
        this.submissionMapper = submissionMapper;
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "email-submission-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    @PostConstruct
    void start() {
        scheduler.scheduleWithFixedDelay(this::renewActiveLeases,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    public Registration track(UUID submissionId, UUID leaseToken) {
        UUID existing = activeLeases.putIfAbsent(submissionId, leaseToken);
        if (existing != null && !existing.equals(leaseToken)) {
            throw new IllegalStateException("Email submission already has an active lease owner");
        }
        return () -> activeLeases.remove(submissionId, leaseToken);
    }

    void renewActiveLeases() {
        activeLeases.forEach((submissionId, leaseToken) -> {
            try {
                if (submissionMapper.renewLease(submissionId, leaseToken) != 1) {
                    activeLeases.remove(submissionId, leaseToken);
                    log.debug("event=email_submission_lease_inactive submissionId={}", submissionId);
                }
            } catch (RuntimeException e) {
                log.error("event=email_submission_lease_renew_failed submissionId={}", submissionId, e);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        activeLeases.clear();
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
