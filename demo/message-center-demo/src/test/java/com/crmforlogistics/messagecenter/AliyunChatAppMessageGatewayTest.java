package com.crmforlogistics.messagecenter;

import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageRequest;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponse;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliyunChatAppMessageGatewayTest {
    @TempDir
    Path tempDir;

    @Test
    void timeoutCancelsAliyunFuture() {
        RecordingFuture future = new RecordingFuture(false);
        AliyunChatAppMessageGateway gateway = new AliyunChatAppMessageGateway(
                request -> future, () -> { });

        assertThrows(TimeoutException.class,
                () -> gateway.listMessages(request(), Duration.ofMillis(1)));

        assertTrue(future.cancelledWithInterrupt);
        assertEquals(1L, future.requestedTimeout);
        assertEquals(TimeUnit.MILLISECONDS, future.requestedUnit);
    }

    @Test
    void timeoutIsCappedAtFifteenSeconds() {
        RecordingFuture future = new RecordingFuture(false);
        AliyunChatAppMessageGateway gateway = new AliyunChatAppMessageGateway(
                request -> future, () -> { });

        assertThrows(TimeoutException.class,
                () -> gateway.listMessages(request(), Duration.ofSeconds(30)));

        assertEquals(Duration.ofSeconds(15).toMillis(), future.requestedTimeout);
        assertEquals(TimeUnit.MILLISECONDS, future.requestedUnit);
    }

    @Test
    void interruptionCancelsFutureAndRestoresInterruptFlag() {
        RecordingFuture future = new RecordingFuture(true);
        AliyunChatAppMessageGateway gateway = new AliyunChatAppMessageGateway(
                request -> future, () -> { });

        try {
            assertThrows(InterruptedException.class,
                    () -> gateway.listMessages(request(), Duration.ofSeconds(15)));
            assertTrue(future.cancelledWithInterrupt);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void mapsGatewayRequestAndClosesAdapter() throws Exception {
        AtomicReference<ListChatappMessageRequest> captured = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        ListChatappMessageResponse response = response(List.of());
        AliyunChatAppMessageGateway gateway = new AliyunChatAppMessageGateway(request -> {
            captured.set(request);
            return java.util.concurrent.CompletableFuture.completedFuture(response);
        }, () -> closed.set(true));

        ChatAppMessageGateway.MessagePage page = gateway.listMessages(request(), Duration.ofSeconds(15));
        gateway.close();

        assertTrue(page.messages().isEmpty());
        assertEquals(10L, captured.get().getStartTime());
        assertEquals(20L, captured.get().getEndTime());
        assertEquals(2L, captured.get().getPage().getIndex());
        assertEquals(50L, captured.get().getPage().getSize());
        assertEquals("space", captured.get().getCustSpaceId());
        assertEquals("WHATSAPP", captured.get().getChannelType());
        assertEquals("business", captured.get().getBusinessNumber());
        assertEquals("user", captured.get().getUserNumber());
        assertEquals("Sent", captured.get().getMessageStatus());
        assertEquals("Accepted", captured.get().getClientAcceptStatus());
        assertTrue(closed.get());
    }

    @Test
    void syncUsesFifteenSecondTimeoutAndPreservesPaginationFilters() throws Exception {
        Config config = config(Map.ofEntries(
                Map.entry("SYNC_PAGE_SIZE", "1"),
                Map.entry("SYNC_MAX_PAGES", "2"),
                Map.entry("SYNC_START_TIME", "1000"),
                Map.entry("SYNC_END_TIME", "2000"),
                Map.entry("CUST_SPACE_ID", "space-1"),
                Map.entry("CHATAPP_CHANNEL_TYPE", "WHATSAPP"),
                Map.entry("CHATAPP_FROM", "business-1"),
                Map.entry("SYNC_USER_NUMBER", "user-1"),
                Map.entry("SYNC_MESSAGE_STATUS", "Sent"),
                Map.entry("SYNC_CLIENT_ACCEPT_STATUS", "Accepted"),
                Map.entry("CHATAPP_MEDIA_PRECACHE_MODE", "off")
        ));
        List<ChatAppMessageGateway.MessageRequest> requests = new ArrayList<>();
        List<Duration> timeouts = new ArrayList<>();
        AtomicBoolean closed = new AtomicBoolean();
        ChatAppMessageGateway gateway = new ChatAppMessageGateway() {
            @Override
            public MessagePage listMessages(MessageRequest request, Duration timeout) {
                requests.add(request);
                timeouts.add(timeout);
                return requests.size() == 1
                        ? new MessagePage(List.of(dataMessage("message-1")))
                        : new MessagePage(List.of());
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        ChatAppHistorySyncService service = service(config, () -> gateway);

        SyncResult result;
        try (service) {
            result = service.syncMessages(ChatAppHistorySyncService.CommitAction::run);
        }

        assertEquals(2, requests.size());
        assertEquals(List.of(Duration.ofSeconds(15), Duration.ofSeconds(15)), timeouts);
        ChatAppMessageGateway.MessageRequest first = requests.get(0);
        assertEquals(1000L, first.startTime());
        assertEquals(2000L, first.endTime());
        assertEquals(1, first.pageIndex());
        assertEquals(1, first.pageSize());
        assertEquals("space-1", first.custSpaceId());
        assertEquals("WHATSAPP", first.channelType());
        assertEquals("business-1", first.businessNumber());
        assertEquals("user-1", first.userNumber());
        assertEquals("Sent", first.messageStatus());
        assertEquals("Accepted", first.clientAcceptStatus());
        assertEquals(2, result.pages);
        assertEquals(1, result.fetched);
        assertEquals(1, result.saved);
        assertTrue(closed.get());
    }

    @Test
    void syncClosesGatewayWhenListingFails() {
        Config config = config(Map.of("CUST_SPACE_ID", "space-1"));
        AtomicBoolean closed = new AtomicBoolean();
        ChatAppMessageGateway gateway = new ChatAppMessageGateway() {
            @Override
            public MessagePage listMessages(MessageRequest request, Duration timeout) throws Exception {
                throw new IOException("list failed");
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        ChatAppHistorySyncService service = service(config, () -> gateway);

        try (service) {
            assertThrows(IOException.class,
                    () -> service.syncMessages(ChatAppHistorySyncService.CommitAction::run));
        }

        assertTrue(closed.get());
    }

    @Test
    void closedServiceRejectsNewMediaTasksWithStructuredFailure() throws Exception {
        Config config = config(Map.of(
                "CUST_SPACE_ID", "space-1",
                "CHATAPP_MEDIA_PRECACHE_MODE", "background"
        ));
        ChatAppHistorySyncService service = service(config,
                () -> { throw new AssertionError("gateway should not open"); });
        ChatAppHistorySyncService.ProjectedChatAppMessage message = projectedMessage("media-1");
        message.extra.put("mediaType", "image");
        message.extra.put("mediaUrl", "https://example.com/image.png");
        SyncResult result = new SyncResult("chatapp");

        service.close();
        service.appendAndPrecache(message, result);

        assertEquals(1, result.saved);
        assertEquals(0, result.mediaQueued);
        assertEquals(1, result.mediaFailed);
        assertEquals(1, result.mediaFailures.size());
        assertTrue(result.mediaFailures.get(0).reason.contains("closed"));
    }

    @Test
    void closeCannotOvertakeMediaTaskRegistration() throws Exception {
        Config config = config(Map.of(
                "CUST_SPACE_ID", "space-1",
                "CHATAPP_MEDIA_PRECACHE_MODE", "background"
        ));
        BlockingExecutor mediaExecutor = new BlockingExecutor();
        ChatAppHistorySyncService service = service(config,
                () -> { throw new AssertionError("gateway should not open"); }, mediaExecutor);
        ChatAppHistorySyncService.ProjectedChatAppMessage message = projectedMessage("media-race");
        message.extra.put("mediaType", "image");
        message.extra.put("mediaUrl", "https://example.com/race.png");
        SyncResult result = new SyncResult("chatapp");
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> append = callers.submit(() -> {
                try {
                    service.appendAndPrecache(message, result);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            assertTrue(mediaExecutor.executeEntered.await(2, TimeUnit.SECONDS));
            Future<?> close = callers.submit(service::close);

            assertThrows(TimeoutException.class, () -> close.get(200, TimeUnit.MILLISECONDS),
                    "close must wait for the in-flight media task registration boundary");
            mediaExecutor.allowExecute.countDown();
            append.get(2, TimeUnit.SECONDS);
            close.get(2, TimeUnit.SECONDS);

            assertEquals(1, result.mediaQueued);
            assertEquals(0, result.mediaFailed);
        } finally {
            mediaExecutor.allowExecute.countDown();
            service.close();
            callers.shutdownNow();
        }
    }

    private ChatAppHistorySyncService service(Config config, ChatAppMessageGateway.Factory factory) {
        return new ChatAppHistorySyncService(config, new ChatAppHistoryStore(config),
                new TemplateStore(config.chatappTemplateFile()), message -> { },
                ChatAppTemplateSynchronizer.create(config), factory);
    }

    private ChatAppHistorySyncService service(Config config, ChatAppMessageGateway.Factory factory,
                                              ThreadPoolExecutor mediaExecutor) {
        return new ChatAppHistorySyncService(config, new ChatAppHistoryStore(config),
                new TemplateStore(config.chatappTemplateFile()), message -> { },
                ChatAppTemplateSynchronizer.create(config), factory, mediaExecutor);
    }

    private Config config(Map<String, String> extra) {
        Map<String, String> values = new HashMap<>(extra);
        values.put("CHATAPP_DATA_FILE", tempDir.resolve("messages.jsonl").toString());
        values.put("CHATAPP_TEMPLATE_FILE", tempDir.resolve("templates.json").toString());
        return new Config(values);
    }

    private static ChatAppMessageGateway.MessageRequest request() {
        return new ChatAppMessageGateway.MessageRequest(
                10, 20, 2, 50, "space", "WHATSAPP", "business", "user", "Sent", "Accepted");
    }

    private static ListChatappMessageResponse response(List<ListChatappMessageResponseBody.Data> rows) {
        ListChatappMessageResponseBody body = ListChatappMessageResponseBody.builder()
                .code("OK").success(true).data(rows).build();
        return ListChatappMessageResponse.create().toBuilder().body(body).build();
    }

    private static ListChatappMessageResponseBody.Data dataMessage(String id) {
        return ListChatappMessageResponseBody.Data.builder()
                .messageId(id)
                .businessNumber("business")
                .userNumber("user")
                .messageSource("user")
                .message("hello")
                .sendTime("2026-07-31T00:00:00Z")
                .build();
    }

    private static ChatAppHistorySyncService.ProjectedChatAppMessage projectedMessage(String id) {
        ChatAppHistorySyncService.ProjectedChatAppMessage message =
                new ChatAppHistorySyncService.ProjectedChatAppMessage();
        message.id = id;
        message.direction = "inbound";
        message.from = "user";
        message.to = "business";
        message.text = "[image]";
        message.status = "Received";
        message.timestamp = "2026-07-31T00:00:00Z";
        message.raw = "{}";
        return message;
    }

    private static final class RecordingFuture implements Future<ListChatappMessageResponse> {
        private final boolean interrupted;
        private boolean cancelledWithInterrupt;
        private long requestedTimeout;
        private TimeUnit requestedUnit;

        private RecordingFuture(boolean interrupted) {
            this.interrupted = interrupted;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelledWithInterrupt = mayInterruptIfRunning;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelledWithInterrupt;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public ListChatappMessageResponse get() {
            throw new AssertionError("unbounded get must not be used");
        }

        @Override
        public ListChatappMessageResponse get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            requestedTimeout = timeout;
            requestedUnit = unit;
            if (interrupted) {
                throw new InterruptedException("test interruption");
            }
            throw new TimeoutException("test timeout");
        }
    }

    private static final class BlockingExecutor extends ThreadPoolExecutor {
        private final CountDownLatch executeEntered = new CountDownLatch(1);
        private final CountDownLatch allowExecute = new CountDownLatch(1);

        private BlockingExecutor() {
            super(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public void execute(Runnable command) {
            executeEntered.countDown();
            try {
                if (!allowExecute.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("media execute was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("media execute interrupted", exception);
            }
            super.execute(command);
        }
    }
}
