package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAppTemplateSynchronizerTest {
    @TempDir
    Path tempDir;

    Path templateFile;
    Config config;
    TemplateStore store;

    @BeforeEach
    void setUp() {
        templateFile = tempDir.resolve("templates.json");
        config = new Config(Map.of(
                "CUST_SPACE_ID", "space-1",
                "CHATAPP_TEMPLATE_FILE", templateFile.toString()));
        store = new TemplateStore(templateFile);
    }

    @Test
    void replacesSnapshotWithCompleteUpstreamResultSoDeletedTemplatesDisappear() throws Exception {
        store.replaceIfChanged(List.of(record("deleted", "old"), record("keep", "old")));
        FakeGateway gateway = new FakeGateway(List.of(
                new ChatAppTemplateGateway.TemplatePage(List.of(summary("keep")), 1),
                new ChatAppTemplateGateway.TemplatePage(List.of(), 1)));
        gateway.details.put("keep", record("keep", "new"));
        ChatAppTemplateSynchronizer synchronizer = synchronizer(gateway);

        ChatAppTemplateSynchronizer.Outcome outcome = synchronizer.sync();

        assertEquals(ChatAppTemplateSynchronizer.Status.CHANGED, outcome.status());
        assertEquals(List.of("keep"), store.readAll().stream().map(item -> item.templateCode).toList());
        assertEquals("new", store.readAll().get(0).body);
    }

    @Test
    void reportsUnchangedWhenOnlyTheLocalSyncTimestampDiffers() throws Exception {
        TemplateStore.TemplateRecord existing = record("keep", "same");
        existing.updatedAt = "2026-07-01T00:00:00Z";
        store.replaceIfChanged(List.of(existing));
        FakeGateway gateway = new FakeGateway(List.of(
                new ChatAppTemplateGateway.TemplatePage(List.of(summary("keep")), 1)));
        gateway.details.put("keep", record("keep", "same"));

        ChatAppTemplateSynchronizer.Outcome outcome = synchronizer(gateway).sync();

        assertEquals(ChatAppTemplateSynchronizer.Status.UNCHANGED, outcome.status());
        assertEquals(0, outcome.changed());
        assertEquals(1, outcome.count());
    }

    @Test
    void preservesOldSnapshotWhenAnyDetailFails() throws Exception {
        store.replaceIfChanged(List.of(record("stable", "old")));
        String before = Files.readString(templateFile, StandardCharsets.UTF_8);
        FakeGateway gateway = new FakeGateway(List.of(
                new ChatAppTemplateGateway.TemplatePage(List.of(summary("stable")), 1)));
        gateway.detailFailure = new IOException("detail unavailable");

        ChatAppTemplateSynchronizer.SyncFailure failure = assertThrows(
                ChatAppTemplateSynchronizer.SyncFailure.class, () -> synchronizer(gateway).sync());

        assertEquals("detail", failure.stage());
        assertEquals("template_sync_failed", failure.getMessage());
        assertEquals(before, Files.readString(templateFile, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsAFullFinalAllowedPageInsteadOfSavingTruncatedSnapshot() throws Exception {
        Config onePage = new Config(Map.ofEntries(
                Map.entry("CUST_SPACE_ID", "space-1"),
                Map.entry("CHATAPP_TEMPLATE_FILE", templateFile.toString()),
                Map.entry("TEMPLATE_PAGE_SIZE", "50"),
                Map.entry("TEMPLATE_MAX_PAGES", "1")));
        List<ChatAppTemplateGateway.TemplateSummary> summaries =
                IntStream.range(0, 50).mapToObj(index -> summary("code-" + index)).toList();
        FakeGateway gateway = new FakeGateway(List.of(
                new ChatAppTemplateGateway.TemplatePage(summaries, 50)));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> synchronizer(onePage, gateway).sync());

        assertTrue(exception.getMessage().contains("final_page_is_full"));
        assertEquals(0, gateway.detailCalls.get());
        assertFalse(Files.exists(templateFile));
    }

    @Test
    void rejectsReportedTotalsAboveTwoThousandBeforeFetchingDetails() throws Exception {
        FakeGateway gateway = new FakeGateway(List.of(
                new ChatAppTemplateGateway.TemplatePage(List.of(summary("code-1")), 2_001)));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> synchronizer(gateway).sync());

        assertTrue(exception.getMessage().contains("total=2001"));
        assertEquals(0, gateway.detailCalls.get());
        assertFalse(Files.exists(templateFile));
    }

    @Test
    void rejectsShortPageWhoseReportedTotalExceedsFetchedRows() throws Exception {
        store.replaceIfChanged(List.of(record("stable", "old")));
        String before = Files.readString(templateFile, StandardCharsets.UTF_8);
        FakeGateway gateway = new FakeGateway(List.of(
                new ChatAppTemplateGateway.TemplatePage(List.of(summary("partial")), 2)));
        gateway.details.put("partial", record("partial", "new"));

        ChatAppTemplateSynchronizer.SyncFailure failure = assertThrows(
                ChatAppTemplateSynchronizer.SyncFailure.class, () -> synchronizer(gateway).sync());

        assertEquals("list", failure.stage());
        assertTrue(failure.getMessage().contains("reported=2"));
        assertTrue(failure.getMessage().contains("fetched=1"));
        assertEquals(before, Files.readString(templateFile, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsAccumulatedRowsAboveTwoThousandBeforeFetchingDetails() throws Exception {
        List<ChatAppTemplateGateway.TemplateSummary> summaries =
                IntStream.range(0, 2_001).mapToObj(index -> summary("code-" + index)).toList();
        FakeGateway gateway = new FakeGateway(List.of(
                new ChatAppTemplateGateway.TemplatePage(summaries, null)));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> synchronizer(gateway).sync());

        assertTrue(exception.getMessage().contains("fetched=2001"));
        assertEquals(0, gateway.detailCalls.get());
        assertFalse(Files.exists(templateFile));
    }

    @Test
    void rejectsDuplicateStableKeysBeforeCommit() throws Exception {
        FakeGateway gateway = new FakeGateway(List.of(
                new ChatAppTemplateGateway.TemplatePage(
                        List.of(summary("first"), summary("second")), 2)));
        gateway.details.put("first", record("duplicate", "first"));
        gateway.details.put("second", record("duplicate", "second"));

        ChatAppTemplateSynchronizer.SyncFailure failure = assertThrows(
                ChatAppTemplateSynchronizer.SyncFailure.class, () -> synchronizer(gateway).sync());

        assertEquals("detail", failure.stage());
        assertTrue(failure.getMessage().contains("duplicate_template_key"));
        assertFalse(Files.exists(templateFile));
    }

    @Test
    void rejectsMissingStableKeysBeforeCommit() throws Exception {
        FakeGateway gateway = new FakeGateway(List.of(
                new ChatAppTemplateGateway.TemplatePage(List.of(summary("missing")), 1)));
        TemplateStore.TemplateRecord missing = record(null, "body");
        missing.languageCode = null;
        gateway.details.put("missing", missing);

        ChatAppTemplateSynchronizer.SyncFailure failure = assertThrows(
                ChatAppTemplateSynchronizer.SyncFailure.class, () -> synchronizer(gateway).sync());

        assertEquals("detail", failure.stage());
        assertEquals("template_detail_missing_key", failure.getMessage());
        assertFalse(Files.exists(templateFile));
    }

    @Test
    void returnsLockBusyWithoutCallingTheGateway() throws Exception {
        try (ChatAppTemplateSyncLock.Handle ignored =
                     ChatAppTemplateSyncLock.tryAcquire(templateFile).orElseThrow()) {
            FakeGateway gateway = new FakeGateway(List.of());
            ChatAppTemplateSynchronizer.Outcome outcome = synchronizer(gateway).sync();
            assertEquals(ChatAppTemplateSynchronizer.Status.LOCK_BUSY, outcome.status());
            assertEquals(0, gateway.listCalls.get());
        }
    }

    @Test
    void lockHandleCloseIsIdempotentAndReleasesTheLock() throws Exception {
        ChatAppTemplateSyncLock.Handle handle =
                ChatAppTemplateSyncLock.tryAcquire(templateFile).orElseThrow();

        handle.close();
        handle.close();

        try (ChatAppTemplateSyncLock.Handle reacquired =
                     ChatAppTemplateSyncLock.tryAcquire(templateFile).orElseThrow()) {
            assertNotNull(reacquired);
        }
        assertTrue(Files.exists(tempDir.resolve("templates.json.lock")));
    }

    @Test
    void concurrentTriggersUseTheInProcessSingleFlightGuard() throws Exception {
        store.replaceIfChanged(List.of(record("old", "old")));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChatAppTemplateGateway blocking = new ChatAppTemplateGateway() {
            @Override
            public TemplatePage listTemplates(int pageIndex, int pageSize, Duration timeout)
                    throws Exception {
                entered.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
                return new TemplatePage(List.of(), 0);
            }

            @Override
            public TemplateStore.TemplateRecord getTemplateDetail(
                    TemplateSummary summary, Duration timeout) {
                throw new AssertionError("detail must not run");
            }

            @Override
            public void close() {}
        };
        ChatAppTemplateSynchronizer synchronizer = new ChatAppTemplateSynchronizer(
                config, store, () -> blocking, Clock.systemUTC(), Duration.ofSeconds(5));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChatAppTemplateSynchronizer.Outcome> first = executor.submit(synchronizer::sync);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            ChatAppTemplateSynchronizer.Outcome duplicate = synchronizer.sync();
            assertEquals(ChatAppTemplateSynchronizer.Status.LOCK_BUSY, duplicate.status());
            release.countDown();
            assertEquals(ChatAppTemplateSynchronizer.Status.CHANGED, first.get().status());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedRoundReleasesTheInProcessSingleFlightGuard() throws Exception {
        FakeGateway gateway = new FakeGateway(List.of(
                new ChatAppTemplateGateway.TemplatePage(List.of(), 0)));
        gateway.listFailure = new IOException("first list failed");
        ChatAppTemplateSynchronizer synchronizer = synchronizer(gateway);

        assertThrows(ChatAppTemplateSynchronizer.SyncFailure.class, synchronizer::sync);
        gateway.listFailure = null;

        assertEquals(ChatAppTemplateSynchronizer.Status.UNCHANGED, synchronizer.sync().status());
        assertEquals(2, gateway.listCalls.get());
    }

    @Test
    void listFailureIsWrappedWithoutLeakingItsMessageAndPreservesTheSnapshot() throws Exception {
        store.replaceIfChanged(List.of(record("stable", "old")));
        String before = Files.readString(templateFile, StandardCharsets.UTF_8);
        FakeGateway gateway = new FakeGateway(List.of());
        gateway.listFailure = new IOException("sensitive upstream list response");

        ChatAppTemplateSynchronizer.SyncFailure failure = assertThrows(
                ChatAppTemplateSynchronizer.SyncFailure.class, () -> synchronizer(gateway).sync());

        assertEquals("list", failure.stage());
        assertEquals("template_sync_failed", failure.getMessage());
        assertFalse(failure.getMessage().contains("sensitive"));
        assertEquals(before, Files.readString(templateFile, StandardCharsets.UTF_8));
    }

    @Test
    void commitFailureIsWrappedAndPreservesTheSnapshot() throws Exception {
        store.replaceIfChanged(List.of(record("stable", "old")));
        String before = Files.readString(templateFile, StandardCharsets.UTF_8);
        TemplateStore failingStore = new TemplateStore(templateFile,
                (source, target) -> {
                    throw new IOException("sensitive filesystem path");
                });
        FakeGateway gateway = new FakeGateway(List.of(
                new ChatAppTemplateGateway.TemplatePage(List.of(summary("stable")), 1)));
        gateway.details.put("stable", record("stable", "new"));

        ChatAppTemplateSynchronizer.SyncFailure failure = assertThrows(
                ChatAppTemplateSynchronizer.SyncFailure.class,
                () -> synchronizer(config, failingStore, gateway, Duration.ofSeconds(240)).sync());

        assertEquals("commit", failure.stage());
        assertEquals("template_sync_failed", failure.getMessage());
        assertFalse(failure.getMessage().contains("filesystem"));
        assertEquals(before, Files.readString(templateFile, StandardCharsets.UTF_8));
    }

    @Test
    void interruptionAfterGatewayClosePreventsCommit() throws Exception {
        store.replaceIfChanged(List.of(record("stable", "old")));
        String before = Files.readString(templateFile, StandardCharsets.UTF_8);
        ChatAppTemplateGateway interrupted = new ChatAppTemplateGateway() {
            @Override
            public TemplatePage listTemplates(int pageIndex, int pageSize, Duration timeout) {
                Thread.currentThread().interrupt();
                return new TemplatePage(List.of(), 0);
            }

            @Override
            public TemplateStore.TemplateRecord getTemplateDetail(
                    TemplateSummary summary, Duration timeout) {
                throw new AssertionError("detail must not run");
            }

            @Override
            public void close() {}
        };

        ChatAppTemplateSynchronizer.SyncFailure failure;
        try {
            failure = assertThrows(ChatAppTemplateSynchronizer.SyncFailure.class,
                    () -> new ChatAppTemplateSynchronizer(config, store, () -> interrupted,
                            Clock.systemUTC(), Duration.ofSeconds(240)).sync());
        } finally {
            assertTrue(Thread.interrupted(), "test thread interrupt flag must be cleared");
        }

        assertEquals("cancelled", failure.stage());
        assertEquals("template_sync_cancelled", failure.getMessage());
        assertEquals(before, Files.readString(templateFile, StandardCharsets.UTF_8));
    }

    @Test
    void roundTimeoutPreservesTheOldSnapshot() throws Exception {
        store.replaceIfChanged(List.of(record("stable", "old")));
        String before = Files.readString(templateFile, StandardCharsets.UTF_8);
        ChatAppTemplateGateway slow = new ChatAppTemplateGateway() {
            @Override
            public TemplatePage listTemplates(int pageIndex, int pageSize, Duration timeout)
                    throws Exception {
                Thread.sleep(timeout.toMillis() + 10);
                throw new TimeoutException("forced timeout");
            }

            @Override
            public TemplateStore.TemplateRecord getTemplateDetail(
                    TemplateSummary summary, Duration timeout) {
                throw new AssertionError("detail must not run");
            }

            @Override
            public void close() {}
        };
        ChatAppTemplateSynchronizer synchronizer = new ChatAppTemplateSynchronizer(
                config, store, () -> slow, Clock.systemUTC(), Duration.ofMillis(50));

        assertThrows(Exception.class, synchronizer::sync);
        assertEquals(before, Files.readString(templateFile, StandardCharsets.UTF_8));
    }

    @Test
    void historyServiceMapsTheSharedSynchronizerOutcome() throws Exception {
        FakeGateway gateway = new FakeGateway(List.of(
                new ChatAppTemplateGateway.TemplatePage(List.of(summary("keep")), 1)));
        gateway.details.put("keep", record("keep", "new"));
        ChatAppHistorySyncService service = new ChatAppHistorySyncService(
                config, synchronizer(gateway));

        SyncResult result = service.syncTemplates();

        assertEquals(1, result.fetched);
        assertEquals(1, result.saved);
        assertEquals(0, result.skipped);
        assertEquals(1, result.pages);
        assertTrue(result.message.contains("template sync changed"));
    }

    private ChatAppTemplateSynchronizer synchronizer(FakeGateway gateway) {
        return synchronizer(config, gateway);
    }

    private ChatAppTemplateSynchronizer synchronizer(Config selectedConfig, FakeGateway gateway) {
        return synchronizer(selectedConfig, new TemplateStore(selectedConfig.chatappTemplateFile()),
                gateway, Duration.ofSeconds(240));
    }

    private ChatAppTemplateSynchronizer synchronizer(Config selectedConfig, TemplateStore selectedStore,
                                                       FakeGateway gateway, Duration roundTimeout) {
        return new ChatAppTemplateSynchronizer(selectedConfig, selectedStore,
                () -> gateway, Clock.systemUTC(), roundTimeout);
    }

    private static ChatAppTemplateGateway.TemplateSummary summary(String code) {
        return new ChatAppTemplateGateway.TemplateSummary(code, code, "zh_CN", "WHATSAPP");
    }

    private static TemplateStore.TemplateRecord record(String code, String body) {
        TemplateStore.TemplateRecord item = new TemplateStore.TemplateRecord();
        item.templateCode = code;
        item.templateName = code;
        item.languageCode = "zh_CN";
        item.body = body;
        item.raw = "{\"auditStatus\":\"pass\",\"body\":\"" + body + "\"}";
        return item;
    }

    private static final class FakeGateway implements ChatAppTemplateGateway {
        private final ArrayDeque<TemplatePage> pages;
        private final Map<String, TemplateStore.TemplateRecord> details = new HashMap<>();
        private final AtomicInteger listCalls = new AtomicInteger();
        private final AtomicInteger detailCalls = new AtomicInteger();
        private Duration lastTimeout = Duration.ZERO;
        private Exception listFailure;
        private Exception detailFailure;

        FakeGateway(List<TemplatePage> pages) {
            this.pages = new ArrayDeque<>(pages);
        }

        @Override
        public TemplatePage listTemplates(int pageIndex, int pageSize, Duration timeout)
                throws Exception {
            listCalls.incrementAndGet();
            lastTimeout = timeout;
            assertTrue(timeout.compareTo(Duration.ofSeconds(15)) <= 0);
            if (listFailure != null) throw listFailure;
            return pages.isEmpty() ? new TemplatePage(List.of(), 0) : pages.removeFirst();
        }

        @Override
        public TemplateStore.TemplateRecord getTemplateDetail(
                TemplateSummary summary, Duration timeout) throws Exception {
            detailCalls.incrementAndGet();
            lastTimeout = timeout;
            assertTrue(timeout.compareTo(Duration.ofSeconds(15)) <= 0);
            if (detailFailure != null) throw detailFailure;
            return details.get(summary.templateCode());
        }

        @Override
        public void close() {}
    }
}
