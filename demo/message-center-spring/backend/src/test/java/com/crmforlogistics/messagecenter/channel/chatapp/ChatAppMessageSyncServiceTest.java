package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageRequest;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponse;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.SyncCursorMapper;
import com.crmforlogistics.messagecenter.channel.chatapp.ChannelSyncCursorEntity;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppHistoryReconciliationResult;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessagePeerReconciliationService;
import com.crmforlogistics.messagecenter.service.chatapp.PeerReconciliationModels.PeerReconciliationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentials;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsResolver;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatAppMessageSyncServiceTest {

    @Mock ChannelAccountMapper channelAccountMapper;
    @Mock ChatAppPollingProjector pollingProjector;
    @Mock ChatAppMessagePeerReconciliationService peerReconciliationService;
    @Mock ChatAppAccountCredentialsResolver credentialsResolver;
    @Mock SyncCursorMapper syncCursorMapper;

    @BeforeEach
    void setUpCredentials() {
        when(credentialsResolver.resolve(any())).thenReturn(new ChatAppAccountCredentials(
                "account-key", "account-secret", "account-space", "60122222222",
                "ap-southeast-1", "cams.ap-southeast-1.aliyuncs.com"));
    }

    @Test
    void shouldConstructWithDependencies() {
        ChatAppMessageSyncService service = new ChatAppMessageSyncService(
                channelAccountMapper, pollingProjector, peerReconciliationService, credentialsResolver);
        assertNotNull(service);
    }

    @Test
    void shouldRejectNullDependencies() {
        assertThrows(NullPointerException.class, () -> new ChatAppMessageSyncService(
                null, pollingProjector, peerReconciliationService, credentialsResolver));
        assertThrows(NullPointerException.class, () -> new ChatAppMessageSyncService(
                channelAccountMapper, null, peerReconciliationService, credentialsResolver));
    }

    @Test
    void reportsMessageAndContactProjectionCountsWithoutChangingExistingJsonFields()
            throws Exception {
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setAccountIdentifier("8613266259485");
        when(channelAccountMapper.selectActiveChatAppAccountsForSync()).thenReturn(List.of(account));

        ListChatappMessageResponseBody.Data saved = ListChatappMessageResponseBody.Data.builder()
                .messageId("wamid-saved")
                .userNumber("60123456789")
                .build();
        ListChatappMessageResponseBody.Data skipped = ListChatappMessageResponseBody.Data.builder()
                .messageId("wamid-skipped")
                .userNumber("not-a-number")
                .build();
        AsyncClient client = mock(AsyncClient.class);
        when(client.listChatappMessage(any())).thenReturn(CompletableFuture.completedFuture(
                ListChatappMessageResponse.create().toBuilder()
                        .body(ListChatappMessageResponseBody.builder()
                                .data(List.of(saved, skipped))
                                .build())
                        .build()));
        when(pollingProjector.project(eq(saved), eq(accountId)))
                .thenReturn(projectionResult(true, true, ""));
        when(pollingProjector.project(eq(skipped), eq(accountId)))
                .thenReturn(projectionResult(false, false, "INVALID_USER_NUMBER"));

        ChatAppMessageSyncService.SyncResultRecord result =
                serviceWithClient(client).runOnce();

        assertThat(recordComponentNames(result)).containsExactly(
                "pages", "fetched", "saved", "skipped", "durationMs",
                "contactsProjected", "contactProjectionSkipped", "incomplete");
        assertThat(recordComponentValue(result, "pages")).isEqualTo(1);
        assertThat(recordComponentValue(result, "fetched")).isEqualTo(2);
        assertThat(recordComponentValue(result, "saved")).isEqualTo(1);
        assertThat(recordComponentValue(result, "skipped")).isEqualTo(1);
        assertThat(recordComponentValue(result, "contactsProjected")).isEqualTo(1);
        assertThat(recordComponentValue(result, "contactProjectionSkipped")).isEqualTo(1);
    }

    @Test
    void contactProjectionFailureIsCountedAsSkippedInsteadOfSaved() throws Exception {
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setAccountIdentifier("8613266259485");
        when(channelAccountMapper.selectActiveChatAppAccountsForSync()).thenReturn(List.of(account));

        ListChatappMessageResponseBody.Data row = ListChatappMessageResponseBody.Data.builder()
                .messageId("wamid-projection-error")
                .userNumber("60123456789")
                .build();
        AsyncClient client = mock(AsyncClient.class);
        when(client.listChatappMessage(any())).thenReturn(CompletableFuture.completedFuture(
                ListChatappMessageResponse.create().toBuilder()
                        .body(ListChatappMessageResponseBody.builder().data(List.of(row)).build())
                        .build()));
        when(pollingProjector.project(eq(row), eq(accountId)))
                .thenThrow(new IllegalStateException("CHATAPP_CONTACT_PROJECTION_FAILED"));

        ChatAppMessageSyncService.SyncResultRecord result =
                serviceWithClient(client).runOnce();

        assertThat(recordComponentValue(result, "saved")).isEqualTo(0);
        assertThat(recordComponentValue(result, "skipped")).isEqualTo(1);
        assertThat(recordComponentValue(result, "contactsProjected")).isEqualTo(0);
        assertThat(recordComponentValue(result, "contactProjectionSkipped")).isEqualTo(1);
    }

    @Test
    void projectionFailureWithoutDurableInboxDoesNotAdvanceWatermark() throws Exception {
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = activeAccount(accountId, "60199999999");
        when(channelAccountMapper.selectById(accountId)).thenReturn(account);
        ListChatappMessageResponseBody.Data row = ListChatappMessageResponseBody.Data.builder()
                .messageId("wamid-projection-error")
                .sendTime("1760000000000")
                .build();
        AsyncClient client = mock(AsyncClient.class);
        when(client.listChatappMessage(any())).thenReturn(CompletableFuture.completedFuture(
                ListChatappMessageResponse.create().toBuilder()
                        .body(ListChatappMessageResponseBody.builder().data(List.of(row)).build())
                        .build()));
        when(pollingProjector.project(eq(row), eq(accountId)))
                .thenThrow(new IllegalStateException("CHATAPP_CONTACT_PROJECTION_FAILED"));

        serviceWithClient(client).runAccount(accountId);

        verify(syncCursorMapper, never()).upsertCursor(any(), any(), any(), any(), any());
    }

    @Test
    void unknownProjectionFailureUsesPollingFailureCode() throws Exception {
        Method method = ChatAppMessageSyncService.class
                .getDeclaredMethod("projectionFailureCode", RuntimeException.class);
        method.setAccessible(true);

        assertThat(method.invoke(null, new IllegalStateException("unexpected")))
                .isEqualTo("CHATAPP_POLLING_PROJECTION_FAILED");
    }

    @Test
    void skipReasonCounterUsesStableBoundedCodes() {
        Map<String, Integer> counts = new LinkedHashMap<>();

        ChatAppMessageSyncService.addSkipReason(counts, "INVALID_USER_NUMBER");
        ChatAppMessageSyncService.addSkipReason(counts, "INVALID_USER_NUMBER");
        ChatAppMessageSyncService.addSkipReason(counts, "");

        assertThat(counts).containsEntry("INVALID_USER_NUMBER", 2)
                .containsEntry("UNKNOWN", 1);
    }

    @Test
    void providerFailureIsPropagatedToTheScheduler() throws Exception {
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setAccountIdentifier("8613266259485");
        when(channelAccountMapper.selectActiveChatAppAccountsForSync()).thenReturn(List.of(account));

        AsyncClient client = mock(AsyncClient.class);
        CompletableFuture<ListChatappMessageResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("provider unavailable"));
        when(client.listChatappMessage(any())).thenReturn(failed);

        assertThatThrownBy(() -> serviceWithClient(client).runOnce())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHATAPP_MESSAGE_HISTORY_SYNC_FAILED");
    }

    @Test
    void runAccountUsesTheSelectedAccountCredentials() throws Exception {
        UUID requestedAccountId = UUID.randomUUID();
        ChannelAccountEntity requestedAccount = new ChannelAccountEntity();
        requestedAccount.setId(requestedAccountId);
        requestedAccount.setChannelType("chatapp");
        requestedAccount.setAuthStatus("active");
        requestedAccount.setAccountIdentifier("60199999999");
        when(channelAccountMapper.selectById(requestedAccountId)).thenReturn(requestedAccount);

        AsyncClient client = mock(AsyncClient.class);
        when(client.listChatappMessage(any())).thenReturn(CompletableFuture.completedFuture(
                ListChatappMessageResponse.create().toBuilder()
                        .body(ListChatappMessageResponseBody.builder().data(List.of()).build())
                        .build()));

        serviceWithClient(client).runAccount(requestedAccountId);

        org.mockito.ArgumentCaptor<ListChatappMessageRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(ListChatappMessageRequest.class);
        verify(client).listChatappMessage(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getBusinessNumber()).isEqualTo("60122222222");
        assertThat(requestCaptor.getValue().getCustSpaceId()).isEqualTo("account-space");
        verify(credentialsResolver).resolve(requestedAccount);
    }

    @Test
    void incrementalPollingStartsFromLastSuccessfulSyncWithOverlap() throws Exception {
        UUID accountId = UUID.randomUUID();
        Instant lastSyncedAt = Instant.parse("2026-09-21T09:58:00Z");
        ChannelAccountEntity account = activeAccount(accountId, "60199999999");
        when(channelAccountMapper.selectById(accountId)).thenReturn(account);
        when(syncCursorMapper.selectCursor(accountId, "CHATAPP_MESSAGES", "default"))
                .thenReturn(java.util.Optional.of(cursor(lastSyncedAt, "cursor-id")));

        AsyncClient client = mock(AsyncClient.class);
        when(client.listChatappMessage(any())).thenReturn(CompletableFuture.completedFuture(
                ListChatappMessageResponse.create().toBuilder()
                        .body(ListChatappMessageResponseBody.builder().data(List.of()).build())
                        .build()));

        serviceWithClient(client).runAccount(accountId);

        org.mockito.ArgumentCaptor<ListChatappMessageRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(ListChatappMessageRequest.class);
        verify(client).listChatappMessage(requestCaptor.capture());
        ListChatappMessageRequest request = requestCaptor.getValue();
        assertThat(request.getStartTime()).isEqualTo(lastSyncedAt.toEpochMilli() - 120_000L);
        assertThat(request.getEndTime()).isGreaterThanOrEqualTo(lastSyncedAt.toEpochMilli());
    }

    @Test
    void incrementalPollingIgnoresGenericAccountSyncCursor() throws Exception {
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = activeAccount(accountId, "60199999999");
        account.setLastSyncedAt(Instant.parse("2020-01-01T00:00:00Z"));
        Instant messageCursor = Instant.parse("2026-09-21T09:58:00Z");
        when(syncCursorMapper.selectCursor(accountId, "CHATAPP_MESSAGES", "default"))
                .thenReturn(java.util.Optional.of(cursor(messageCursor, "cursor-id")));
        when(channelAccountMapper.selectById(accountId)).thenReturn(account);

        AsyncClient client = mock(AsyncClient.class);
        when(client.listChatappMessage(any())).thenReturn(CompletableFuture.completedFuture(
                ListChatappMessageResponse.create().toBuilder()
                        .body(ListChatappMessageResponseBody.builder().data(List.of()).build())
                        .build()));

        serviceWithClient(client).runAccount(accountId);

        org.mockito.ArgumentCaptor<ListChatappMessageRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(ListChatappMessageRequest.class);
        verify(client).listChatappMessage(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getStartTime())
                .isEqualTo(messageCursor.toEpochMilli() - 120_000L);
    }

    @Test
    void historyDryRunUsesExplicitRangeAndDoesNotMoveMessages() throws Exception {
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = activeAccount(accountId, "8613266259485");
        when(channelAccountMapper.selectById(accountId)).thenReturn(account);

        Instant start = Instant.parse("2026-07-01T00:00:00Z");
        Instant end = Instant.parse("2026-08-01T00:00:00Z");
        ListChatappMessageResponseBody.Data row = ListChatappMessageResponseBody.Data.builder()
                .messageId("wamid-history")
                .userNumber("8613428277520")
                .build();
        AsyncClient client = mock(AsyncClient.class);
        when(client.listChatappMessage(any())).thenReturn(CompletableFuture.completedFuture(
                ListChatappMessageResponse.create().toBuilder()
                        .body(ListChatappMessageResponseBody.builder().data(List.of(row)).build())
                        .build()));
        when(peerReconciliationService.inspect(any()))
                .thenReturn(PeerReconciliationResult.moved(null, null, true));

        ChatAppHistoryReconciliationResult result = serviceWithClient(client)
                .runAccount(accountId, start, end, 5, true);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.moved()).isEqualTo(1);
        assertThat(result.identitiesCreated()).isEqualTo(1);
        assertThat(result.dryRun()).isTrue();
        verify(peerReconciliationService).inspect(any());
        verify(peerReconciliationService, never()).reconcile(any());

        org.mockito.ArgumentCaptor<ListChatappMessageRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(ListChatappMessageRequest.class);
        verify(client).listChatappMessage(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getStartTime()).isEqualTo(start.toEpochMilli());
        assertThat(requestCaptor.getValue().getEndTime()).isEqualTo(end.toEpochMilli());
    }

    @Test
    void historyExecutionReturnsStructuredUnresolvedDetails() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(channelAccountMapper.selectById(accountId))
                .thenReturn(activeAccount(accountId, "8613266259485"));

        ListChatappMessageResponseBody.Data row = ListChatappMessageResponseBody.Data.builder()
                .messageId("wamid-missing")
                .userNumber("8613428277520")
                .build();
        AsyncClient client = mock(AsyncClient.class);
        when(client.listChatappMessage(any())).thenReturn(CompletableFuture.completedFuture(
                ListChatappMessageResponse.create().toBuilder()
                        .body(ListChatappMessageResponseBody.builder().data(List.of(row)).build())
                        .build()));
        when(peerReconciliationService.reconcile(any()))
                .thenReturn(PeerReconciliationResult.unresolved("CHATAPP_PEER_MESSAGE_NOT_FOUND"));

        ChatAppHistoryReconciliationResult result = serviceWithClient(client).runAccount(
                accountId,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                5,
                false);

        assertThat(result.unresolved()).isEqualTo(1);
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.providerMessageId()).isEqualTo("wamid-missing");
            assertThat(failure.reason()).isEqualTo("CHATAPP_PEER_MESSAGE_NOT_FOUND");
        });
    }

    @Test
    void historyRangeAndPageLimitsAreRejectedBeforeProviderAccess() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(channelAccountMapper.selectById(accountId))
                .thenReturn(activeAccount(accountId, "8613266259485"));
        AsyncClient client = mock(AsyncClient.class);
        ChatAppMessageSyncService service = serviceWithClient(client);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> service.runAccount(
                accountId, start, start.plusSeconds(91L * 24 * 60 * 60), 5, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_HISTORY_RANGE_TOO_LARGE");
        assertThatThrownBy(() -> service.runAccount(
                accountId, start, start.plusSeconds(60), 51, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_HISTORY_MAX_PAGES_INVALID");
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void hangingProviderFailsFastWithDedicatedTimeoutCode() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(channelAccountMapper.selectById(accountId))
                .thenReturn(activeAccount(accountId, "8613266259485"));

        AsyncClient client = mock(AsyncClient.class);
        when(client.listChatappMessage(any())).thenReturn(new CompletableFuture<>());

        long started = System.nanoTime();
        assertThatThrownBy(() -> serviceWithClient(client, Duration.ofMillis(80)).runAccount(accountId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHATAPP_API_TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void hangingProviderDuringHistoryReconciliationAlsoFailsFast() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(channelAccountMapper.selectById(accountId))
                .thenReturn(activeAccount(accountId, "8613266259485"));

        AsyncClient client = mock(AsyncClient.class);
        when(client.listChatappMessage(any())).thenReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> serviceWithClient(client, Duration.ofMillis(80)).runAccount(
                accountId,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                5,
                false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHATAPP_API_TIMEOUT");
    }

    @Test
    void pollingFailureDiagnosticDistinguishesFutureWaitTimeout() {
        ChatAppMessageSyncService.PollingFailureDiagnostic diagnostic =
                ChatAppMessageSyncService.diagnosePollingFailure(
                        new TimeoutException("future wait expired"));

        assertThat(diagnostic.code()).isEqualTo("CHATAPP_API_TIMEOUT");
        assertThat(diagnostic.source()).isEqualTo("CLIENT_FUTURE_WAIT");
        assertThat(diagnostic.exceptionType()).isEqualTo(TimeoutException.class.getName());
        assertThat(diagnostic.rootCauseType()).isEqualTo(TimeoutException.class.getName());
        assertThat(diagnostic.rootCauseMessage()).isEqualTo("future wait expired");
    }

    @Test
    void pollingFailureDiagnosticUnwrapsProviderCause() {
        IllegalStateException providerFailure = new IllegalStateException("CAMS response unavailable");
        java.util.concurrent.CompletionException wrapped =
                new java.util.concurrent.CompletionException(providerFailure);

        ChatAppMessageSyncService.PollingFailureDiagnostic diagnostic =
                ChatAppMessageSyncService.diagnosePollingFailure(wrapped);

        assertThat(diagnostic.code()).isEqualTo("CHATAPP_MESSAGE_HISTORY_SYNC_FAILED");
        assertThat(diagnostic.source()).isEqualTo("SDK_COMPLETION");
        assertThat(diagnostic.exceptionType()).isEqualTo(wrapped.getClass().getName());
        assertThat(diagnostic.rootCauseType()).isEqualTo(providerFailure.getClass().getName());
        assertThat(diagnostic.rootCauseMessage()).isEqualTo("CAMS response unavailable");
    }

    @Test
    void clientIsBuiltWithConfiguredTimeout() throws Exception {
        Method createClient = ChatAppMessageSyncService.class
                .getDeclaredMethod("createClient", ChatAppAccountCredentials.class);
        createClient.setAccessible(true);
        ChatAppMessageSyncService service = new ChatAppMessageSyncService(
                channelAccountMapper, pollingProjector, peerReconciliationService,
                credentialsResolver, null, Duration.ofSeconds(5));

        try (AsyncClient client = (AsyncClient) createClient.invoke(service,
                new ChatAppAccountCredentials("account-key", "account-secret", "account-space",
                        "60122222222", "ap-southeast-1", "cams.ap-southeast-1.aliyuncs.com"))) {
            assertNotNull(client);
        }
    }

    private ChatAppMessageSyncService serviceWithClient(AsyncClient client) throws Exception {
        return serviceWithClient(client, Duration.ofSeconds(30));
    }

    private ChatAppMessageSyncService serviceWithClient(AsyncClient client, Duration apiTimeout)
            throws Exception {
        Constructor<ChatAppMessageSyncService> constructor = ChatAppMessageSyncService.class
                .getDeclaredConstructor(ChannelAccountMapper.class,
                        ChatAppPollingProjector.class, ChatAppMessagePeerReconciliationService.class,
                        ChatAppAccountCredentialsResolver.class, Supplier.class, Duration.class,
                        SyncCursorMapper.class);
        constructor.setAccessible(true);
        return constructor.newInstance(channelAccountMapper, pollingProjector,
                peerReconciliationService, credentialsResolver,
                (Supplier<AsyncClient>) () -> client, apiTimeout, syncCursorMapper);
    }

    private static ChannelSyncCursorEntity cursor(Instant timestamp, String value) {
        ChannelSyncCursorEntity cursor = new ChannelSyncCursorEntity();
        cursor.setCursorTimestamp(timestamp);
        cursor.setCursorValue(value);
        return cursor;
    }

    private static ChannelAccountEntity activeAccount(UUID id, String identifier) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(id);
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        account.setAccountIdentifier(identifier);
        return account;
    }

    private static ChatAppPollingProjector.ProjectionResult projectionResult(
            boolean messageSaved, boolean contactProjected, String skipReason) {
        return new ChatAppPollingProjector.ProjectionResult(
                messageSaved, contactProjected, skipReason);
    }

    private static List<String> recordComponentNames(Object record) {
        return Arrays.stream(record.getClass().getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static Object recordComponentValue(Object record, String name) throws Exception {
        return record.getClass().getMethod(name).invoke(record);
    }
}
