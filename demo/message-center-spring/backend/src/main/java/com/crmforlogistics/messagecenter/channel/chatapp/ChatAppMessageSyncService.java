package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageRequest;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponse;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppHistoryReconciliationResult;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessagePeerReconciliationService;
import com.crmforlogistics.messagecenter.service.chatapp.PeerReconciliationModels.PeerReconciliationCommand;
import com.crmforlogistics.messagecenter.service.chatapp.PeerReconciliationModels.PeerReconciliationResult;
import darabonba.core.client.ClientOverrideConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentials;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsResolver;

@Service
public class ChatAppMessageSyncService {
    private static final Logger LOG = LoggerFactory.getLogger(ChatAppMessageSyncService.class);
    private static final int MAX_PAGES = 50;
    private static final int PAGE_SIZE = 100;

    private final ChannelAccountMapper channelAccountMapper;
    private final ChatAppPollingProjector pollingProjector;
    private final ChatAppMessagePeerReconciliationService peerReconciliationService;
    private final ChatAppAccountCredentialsResolver credentialsResolver;
    private final Supplier<AsyncClient> clientSupplier;
    private final Set<UUID> runningHistoryAccounts = ConcurrentHashMap.newKeySet();

    @Autowired
    public ChatAppMessageSyncService(ChannelAccountMapper channelAccountMapper,
                                     ChatAppPollingProjector pollingProjector,
                                     ChatAppMessagePeerReconciliationService peerReconciliationService,
                                     ChatAppAccountCredentialsResolver credentialsResolver) {
        this(channelAccountMapper, pollingProjector, peerReconciliationService,
                credentialsResolver, null);
    }

    ChatAppMessageSyncService(ChannelAccountMapper channelAccountMapper,
                              ChatAppPollingProjector pollingProjector,
                              ChatAppMessagePeerReconciliationService peerReconciliationService,
                              ChatAppAccountCredentialsResolver credentialsResolver,
                              Supplier<AsyncClient> clientSupplier) {
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
        this.pollingProjector = Objects.requireNonNull(pollingProjector);
        this.peerReconciliationService = Objects.requireNonNull(peerReconciliationService);
        this.credentialsResolver = Objects.requireNonNull(credentialsResolver);
        this.clientSupplier = clientSupplier;
    }

    public SyncResultRecord runOnce() {
        long started = System.nanoTime();
        SyncResultRecord total = new SyncResultRecord(0, 0, 0, 0, 0, 0, 0);
        for (ChannelAccountEntity account : channelAccountMapper.selectActiveChatAppAccountsForSync()) {
            total = total.plus(syncAccount(account));
        }
        return total.withDurationMs(elapsedMs(started));
    }

    public SyncResultRecord runAccount(UUID channelAccountId) {
        if (channelAccountId == null) {
            throw new IllegalArgumentException("CHATAPP_CHANNEL_ACCOUNT_NOT_FOUND");
        }
        ChannelAccountEntity account = channelAccountMapper.selectById(channelAccountId);
        validateActiveChatAppAccount(account);
        return syncAccount(account);
    }

    public SyncResultRecord runOwnedAccount(UUID ownerId) {
        if (ownerId == null) {
            throw new IllegalArgumentException("CHATAPP_CHANNEL_ACCOUNT_NOT_FOUND");
        }
        List<ChannelAccountEntity> accounts =
                channelAccountMapper.findByOwnerAndChannelType(ownerId, "chatapp");
        if (accounts.size() != 1) {
            throw new IllegalArgumentException("CHATAPP_CHANNEL_ACCOUNT_NOT_FOUND");
        }
        ChannelAccountEntity account = accounts.get(0);
        validateActiveChatAppAccount(account);
        return syncAccount(account);
    }

    public ChatAppHistoryReconciliationResult runAccount(
            UUID channelAccountId, Instant startTime, Instant endTime, int maxPages) {
        return runAccount(channelAccountId, startTime, endTime, maxPages, false);
    }

    public ChatAppHistoryReconciliationResult runAccount(
            UUID channelAccountId, Instant startTime, Instant endTime,
            int maxPages, boolean dryRun) {
        return runOwnedAccount(null, channelAccountId, startTime, endTime, maxPages, dryRun);
    }

    public ChatAppHistoryReconciliationResult runOwnedAccount(
            UUID ownerId, UUID channelAccountId, Instant startTime, Instant endTime,
            int maxPages, boolean dryRun) {
        validateHistoryRange(startTime, endTime, maxPages);
        ChannelAccountEntity account = ownerId == null
                ? channelAccountMapper.selectById(channelAccountId)
                : channelAccountMapper.findByIdAndOwner(channelAccountId, ownerId);
        validateActiveChatAppAccount(account);
        if (ownerId != null && !ownerId.equals(account.getOwnerUserId())) {
            throw new IllegalArgumentException("CHATAPP_CHANNEL_ACCOUNT_NOT_FOUND");
        }
        if (!runningHistoryAccounts.add(channelAccountId)) {
            throw new IllegalStateException("CHATAPP_HISTORY_RECONCILIATION_IN_PROGRESS");
        }
        try {
            return reconcileHistory(account, startTime, endTime, maxPages, dryRun);
        } finally {
            runningHistoryAccounts.remove(channelAccountId);
        }
    }

    private SyncResultRecord syncAccount(ChannelAccountEntity account) {
        ChatAppAccountCredentials credentials = credentialsResolver.resolve(account);
        long started = System.nanoTime();
        int pages = 0;
        int fetched = 0;
        int saved = 0;
        int skipped = 0;
        int contactsProjected = 0;
        int contactProjectionSkipped = 0;
        Map<String, Integer> skipReasons = new LinkedHashMap<>();
        long endTime = System.currentTimeMillis();
        long startTime = endTime - TimeUnit.DAYS.toMillis(30);
        try (AsyncClient client = createClient(credentials)) {
            for (int pageIndex = 1; pageIndex <= MAX_PAGES; pageIndex++) {
                ListChatappMessageRequest request = ListChatappMessageRequest.builder()
                        .custSpaceId(credentials.custSpaceId())
                        .channelType("whatsapp")
                        .businessNumber(credentials.chatappFrom())
                        .startTime(startTime)
                        .endTime(endTime)
                        .page(ListChatappMessageRequest.Page.builder()
                                .index((long) pageIndex)
                                .size((long) PAGE_SIZE)
                                .build())
                        .build();
                ListChatappMessageResponse response = client.listChatappMessage(request).get();
                ListChatappMessageResponseBody body = response.getBody();
                if (body == null || body.getData() == null || body.getData().isEmpty()) break;

                List<ListChatappMessageResponseBody.Data> rows = body.getData();
                pages++;
                fetched += rows.size();
                for (ListChatappMessageResponseBody.Data row : rows) {
                    try {
                        ChatAppPollingProjector.ProjectionResult result =
                                pollingProjector.project(row, account.getId());
                        if (result.messageSaved()) saved++;
                        else {
                            skipped++;
                            addSkipReason(skipReasons, result.skipReason());
                        }
                        if (result.contactProjected()) contactsProjected++;
                        else contactProjectionSkipped++;
                    } catch (RuntimeException error) {
                        LOG.warn("Failed to project ChatApp polling row: code={}",
                                projectionFailureCode(error));
                        skipped++;
                        contactProjectionSkipped++;
                        addSkipReason(skipReasons, projectionFailureCode(error));
                    }
                }
                if (rows.size() < PAGE_SIZE) break;
            }
        } catch (Exception error) {
            String code = pollingFailureCode(error);
            LOG.error("ChatApp message polling failed: code={}", code);
            throw new IllegalStateException(code, error);
        }
        long durationMs = elapsedMs(started);
        LOG.info("ChatApp message polling: pages={} fetched={} saved={} skipped={} contactsProjected={} contactProjectionSkipped={} skipReasons={} durationMs={}",
                pages, fetched, saved, skipped, contactsProjected, contactProjectionSkipped,
                skipReasons, durationMs);
        return new SyncResultRecord(pages, fetched, saved, skipped, durationMs,
                contactsProjected, contactProjectionSkipped);
    }

    private ChatAppHistoryReconciliationResult reconcileHistory(
            ChannelAccountEntity account, Instant startTime, Instant endTime,
            int maxPages, boolean dryRun) {
        ChatAppAccountCredentials credentials = credentialsResolver.resolve(account);
        long started = System.nanoTime();
        int pages = 0;
        int scanned = 0;
        int unchanged = 0;
        int moved = 0;
        int unresolved = 0;
        int failed = 0;
        int identitiesCreated = 0;
        List<ChatAppHistoryReconciliationResult.Failure> failures = new ArrayList<>();
        try (AsyncClient client = createClient(credentials)) {
            for (int pageIndex = 1; pageIndex <= maxPages; pageIndex++) {
                ListChatappMessageRequest request = historyRequest(
                        credentials, startTime, endTime, pageIndex);
                ListChatappMessageResponse response = client.listChatappMessage(request).get();
                ListChatappMessageResponseBody body = response.getBody();
                if (body == null || body.getData() == null || body.getData().isEmpty()) break;
                List<ListChatappMessageResponseBody.Data> rows = body.getData();
                pages++;
                scanned += rows.size();
                for (ListChatappMessageResponseBody.Data row : rows) {
                    String providerMessageId = firstNonBlank(
                            row.getMessageId(), row.getUniqueMessageId());
                    try {
                        PeerReconciliationCommand command = new PeerReconciliationCommand(
                                account.getId(), null, providerMessageId, row.getUserNumber());
                        PeerReconciliationResult result = dryRun
                                ? peerReconciliationService.inspect(command)
                                : peerReconciliationService.reconcile(command);
                        switch (result.kind()) {
                            case UNCHANGED -> unchanged++;
                            case MOVED -> moved++;
                            case UNRESOLVED -> {
                                unresolved++;
                                failures.add(new ChatAppHistoryReconciliationResult.Failure(
                                        providerMessageId, result.reason()));
                            }
                        }
                        if (result.identityCreated()) identitiesCreated++;
                    } catch (RuntimeException error) {
                        failed++;
                        failures.add(new ChatAppHistoryReconciliationResult.Failure(
                                providerMessageId, projectionFailureCode(error)));
                    }
                }
                if (rows.size() < PAGE_SIZE) break;
            }
        } catch (Exception error) {
            String code = pollingFailureCode(error);
            LOG.error("ChatApp history reconciliation failed: accountId={} code={}",
                    account.getId(), code);
            throw new IllegalStateException(code, error);
        }
        return new ChatAppHistoryReconciliationResult(
                pages, scanned, unchanged, moved, unresolved, failed, identitiesCreated,
                dryRun, elapsedMs(started), failures);
    }

    private ListChatappMessageRequest historyRequest(
            ChatAppAccountCredentials credentials, Instant startTime, Instant endTime, int pageIndex) {
        return ListChatappMessageRequest.builder()
                .custSpaceId(credentials.custSpaceId())
                .channelType("whatsapp")
                .businessNumber(credentials.chatappFrom())
                .startTime(startTime.toEpochMilli())
                .endTime(endTime.toEpochMilli())
                .page(ListChatappMessageRequest.Page.builder()
                        .index((long) pageIndex)
                        .size((long) PAGE_SIZE)
                        .build())
                .build();
    }

    private static void validateHistoryRange(
            Instant startTime, Instant endTime, int maxPages) {
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("CHATAPP_HISTORY_RANGE_INVALID");
        }
        if (Duration.between(startTime, endTime).compareTo(Duration.ofDays(90)) > 0) {
            throw new IllegalArgumentException("CHATAPP_HISTORY_RANGE_TOO_LARGE");
        }
        if (maxPages < 1 || maxPages > MAX_PAGES) {
            throw new IllegalArgumentException("CHATAPP_HISTORY_MAX_PAGES_INVALID");
        }
    }

    private static void validateActiveChatAppAccount(ChannelAccountEntity account) {
        if (account == null || account.getDeletedAt() != null
                || !("chatapp".equalsIgnoreCase(account.getChannelType())
                || "whatsapp".equalsIgnoreCase(account.getChannelType()))
                || !"active".equalsIgnoreCase(account.getAuthStatus())) {
            throw new IllegalArgumentException("CHATAPP_CHANNEL_ACCOUNT_NOT_FOUND");
        }
    }

    private AsyncClient createClient(ChatAppAccountCredentials credentials) {
        if (clientSupplier != null) return clientSupplier.get();
        return AsyncClient.builder()
                .region(credentials.region())
                .credentialsProvider(createCredentialsProvider(credentials))
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(credentials.endpoint()))
                .build();
    }

    private ICredentialProvider createCredentialsProvider(ChatAppAccountCredentials credentials) {
        return StaticCredentialProvider.create(Credential.builder()
                .accessKeyId(credentials.accessKeyId())
                .accessKeySecret(credentials.accessKeySecret())
                .build());
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static String projectionFailureCode(RuntimeException error) {
        String message = error.getMessage();
        if (message != null && message.matches("CHATAPP_[A-Z0-9_]+")) return message;
        return "CHATAPP_POLLING_PROJECTION_FAILED";
    }

    static void addSkipReason(Map<String, Integer> counts, String reason) {
        String code = reason == null ? "" : reason.trim();
        if (code.isBlank() || !code.matches("[A-Z0-9_]{1,96}")) code = "UNKNOWN";
        counts.merge(code, 1, Integer::sum);
    }

    private static String pollingFailureCode(Exception error) {
        String message = error.getMessage();
        if (message != null && message.matches("CHATAPP_[A-Z0-9_]+")) return message;
        return "CHATAPP_MESSAGE_HISTORY_SYNC_FAILED";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    public record SyncResultRecord(int pages, int fetched, int saved, int skipped, long durationMs,
                                   int contactsProjected, int contactProjectionSkipped) {
        public SyncResultRecord(int pages, int fetched, int saved, int skipped, long durationMs) {
            this(pages, fetched, saved, skipped, durationMs, 0, 0);
        }

        private SyncResultRecord plus(SyncResultRecord other) {
            return new SyncResultRecord(
                    pages + other.pages,
                    fetched + other.fetched,
                    saved + other.saved,
                    skipped + other.skipped,
                    durationMs + other.durationMs,
                    contactsProjected + other.contactsProjected,
                    contactProjectionSkipped + other.contactProjectionSkipped);
        }

        private SyncResultRecord withDurationMs(long totalDurationMs) {
            return new SyncResultRecord(pages, fetched, saved, skipped, totalDurationMs,
                    contactsProjected, contactProjectionSkipped);
        }
    }
}
