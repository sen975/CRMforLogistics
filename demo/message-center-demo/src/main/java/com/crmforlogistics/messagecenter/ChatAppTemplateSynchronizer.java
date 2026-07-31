package com.crmforlogistics.messagecenter;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChatAppTemplateSynchronizer {
    private static final int MAX_TEMPLATE_RECORDS = 2_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration ROUND_TIMEOUT = Duration.ofSeconds(240);

    private final Config config;
    private final TemplateStore templateStore;
    private final ChatAppTemplateGateway.Factory gatewayFactory;
    private final Clock clock;
    private final Duration roundTimeout;
    private final AtomicBoolean running = new AtomicBoolean();

    ChatAppTemplateSynchronizer(Config config, TemplateStore templateStore,
                                ChatAppTemplateGateway.Factory gatewayFactory, Clock clock,
                                Duration roundTimeout) {
        this.config = Objects.requireNonNull(config);
        this.templateStore = Objects.requireNonNull(templateStore);
        this.gatewayFactory = Objects.requireNonNull(gatewayFactory);
        this.clock = Objects.requireNonNull(clock);
        this.roundTimeout = Objects.requireNonNull(roundTimeout);
    }

    public static ChatAppTemplateSynchronizer create(Config config) {
        return new ChatAppTemplateSynchronizer(config,
                new TemplateStore(config.chatappTemplateFile()),
                () -> AliyunChatAppTemplateGateway.open(config),
                Clock.systemUTC(), ROUND_TIMEOUT);
    }

    public Outcome sync() throws Exception {
        long started = System.nanoTime();
        if (!running.compareAndSet(false, true)) {
            return Outcome.lockBusy(started);
        }
        try {
            Optional<ChatAppTemplateSyncLock.Handle> acquired =
                    ChatAppTemplateSyncLock.tryAcquire(templateStore.file());
            if (acquired.isEmpty()) {
                return Outcome.lockBusy(started);
            }
            try (ChatAppTemplateSyncLock.Handle ignored = acquired.orElseThrow()) {
                return synchronizeLocked(started);
            }
        } finally {
            running.set(false);
        }
    }

    private Outcome synchronizeLocked(long started) throws Exception {
        long deadlineNanos = System.nanoTime() + roundTimeout.toNanos();
        Map<String, TemplateStore.TemplateRecord> records = new LinkedHashMap<>();
        int fetched = 0;
        int pages = 0;

        try (ChatAppTemplateGateway gateway = openGateway()) {
            for (int pageIndex = 1; pageIndex <= config.chatappTemplateMaxPages(); pageIndex++) {
                ChatAppTemplateGateway.TemplatePage page = listTemplates(
                        gateway, pageIndex, remainingTimeout(deadlineNanos));
                pages++;
                if (page.total() != null && page.total() > MAX_TEMPLATE_RECORDS) {
                    throw failure("list", "template_limit_exceeded: total=" + page.total());
                }
                List<ChatAppTemplateGateway.TemplateSummary> rows =
                        page.templates() == null ? List.of() : page.templates();
                if (fetched + rows.size() > MAX_TEMPLATE_RECORDS) {
                    throw failure("list",
                            "template_limit_exceeded: fetched=" + (fetched + rows.size()));
                }
                if (pageIndex == config.chatappTemplateMaxPages()
                        && rows.size() == config.chatappTemplatePageSize()) {
                    throw failure("list", "template_limit_exceeded: final_page_is_full");
                }
                for (ChatAppTemplateGateway.TemplateSummary row : rows) {
                    TemplateStore.TemplateRecord record = getTemplateDetail(
                            gateway, row, remainingTimeout(deadlineNanos));
                    if (record == null) {
                        throw failure("detail", "template_detail_missing_key");
                    }
                    record.updatedAt = clock.instant().toString();
                    String key = templateStore.key(record);
                    if (key.equals("|")) {
                        throw failure("detail", "template_detail_missing_key");
                    }
                    if (records.putIfAbsent(key, record) != null) {
                        throw failure("detail", "duplicate_template_key: " + key);
                    }
                    fetched++;
                }
                if (rows.size() < config.chatappTemplatePageSize()) {
                    break;
                }
            }
        } catch (SyncFailure failure) {
            throw failure;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SyncFailure("cancelled", "template_sync_cancelled", exception);
        } catch (Exception exception) {
            throw new SyncFailure("list", "template_sync_failed", exception);
        }

        remainingTimeout(deadlineNanos);
        TemplateStore.ReplaceResult committed;
        try {
            committed = templateStore.replaceIfChanged(new ArrayList<>(records.values()));
        } catch (Exception exception) {
            throw new SyncFailure("commit", "template_sync_failed", exception);
        }
        return Outcome.from(committed, fetched, pages, elapsedMillis(started));
    }

    private ChatAppTemplateGateway openGateway() throws Exception {
        try {
            ChatAppTemplateGateway gateway = gatewayFactory.open();
            if (gateway == null) {
                throw new IllegalStateException("gateway_factory_returned_null");
            }
            return gateway;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SyncFailure("cancelled", "template_sync_cancelled", exception);
        } catch (SyncFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new SyncFailure("list", "template_sync_failed", exception);
        }
    }

    private ChatAppTemplateGateway.TemplatePage listTemplates(
            ChatAppTemplateGateway gateway, int pageIndex, Duration timeout) {
        try {
            ChatAppTemplateGateway.TemplatePage page = gateway.listTemplates(
                    pageIndex, config.chatappTemplatePageSize(), timeout);
            if (page == null) {
                throw new IllegalStateException("template_page_is_null");
            }
            return page;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SyncFailure("cancelled", "template_sync_cancelled", exception);
        } catch (SyncFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new SyncFailure("list", "template_sync_failed", exception);
        }
    }

    private TemplateStore.TemplateRecord getTemplateDetail(
            ChatAppTemplateGateway gateway, ChatAppTemplateGateway.TemplateSummary summary,
            Duration timeout) {
        try {
            return gateway.getTemplateDetail(summary, timeout);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SyncFailure("cancelled", "template_sync_cancelled", exception);
        } catch (SyncFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new SyncFailure("detail", "template_sync_failed", exception);
        }
    }

    private Duration remainingTimeout(long deadlineNanos) {
        if (Thread.currentThread().isInterrupted()) {
            throw failure("cancelled", "template_sync_cancelled");
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw failure("timeout", "template_sync_round_timeout");
        }
        return Duration.ofNanos(Math.min(REQUEST_TIMEOUT.toNanos(), remainingNanos));
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static SyncFailure failure(String stage, String code) {
        return new SyncFailure(stage, code, null);
    }

    static final class SyncFailure extends IllegalStateException {
        private final String stage;

        SyncFailure(String stage, String code, Throwable cause) {
            super(code, cause);
            this.stage = stage;
        }

        String stage() {
            return stage;
        }
    }

    public enum Status {
        CHANGED,
        UNCHANGED,
        LOCK_BUSY
    }

    public record Outcome(Status status, int fetched, int changed, int count,
                          int pages, long durationMillis) {
        static Outcome lockBusy(long started) {
            return new Outcome(Status.LOCK_BUSY, 0, 0, 0, 0,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }

        static Outcome from(TemplateStore.ReplaceResult result, int fetched,
                            int pages, long durationMillis) {
            return new Outcome(result.changed() ? Status.CHANGED : Status.UNCHANGED,
                    fetched, result.changedRecords(), result.recordCount(), pages, durationMillis);
        }
    }
}
