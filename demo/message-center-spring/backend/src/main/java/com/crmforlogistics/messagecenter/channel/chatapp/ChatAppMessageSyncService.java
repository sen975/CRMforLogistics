package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.DefaultCredentialProvider;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageRequest;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponse;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import darabonba.core.client.ClientOverrideConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Service
public class ChatAppMessageSyncService {
    private static final Logger LOG = LoggerFactory.getLogger(ChatAppMessageSyncService.class);
    private static final int MAX_PAGES = 50;
    private static final int PAGE_SIZE = 100;

    private final AppConfig config;
    private final ChannelAccountMapper channelAccountMapper;
    private final ChatAppPollingProjector pollingProjector;

    public ChatAppMessageSyncService(AppConfig config,
                                     ChannelAccountMapper channelAccountMapper,
                                     ChatAppPollingProjector pollingProjector) {
        this.config = Objects.requireNonNull(config);
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
        this.pollingProjector = Objects.requireNonNull(pollingProjector);
    }

    public SyncResultRecord runOnce() {
        long started = System.nanoTime();
        int pages = 0;
        int fetched = 0;
        int saved = 0;
        int skipped = 0;
        ChannelAccountEntity account = resolveChannelAccount();
        if (account == null) {
            return new SyncResultRecord(0, 0, 0, 0, elapsedMs(started));
        }

        long endTime = System.currentTimeMillis();
        long startTime = endTime - TimeUnit.DAYS.toMillis(30);
        try (AsyncClient client = createClient()) {
            for (int pageIndex = 1; pageIndex <= MAX_PAGES; pageIndex++) {
                ListChatappMessageRequest request = ListChatappMessageRequest.builder()
                        .custSpaceId(config.custSpaceId())
                        .channelType("whatsapp")
                        .businessNumber(account.getAccountIdentifier())
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
                        if (pollingProjector.project(row, account.getId())) saved++;
                        else skipped++;
                    } catch (RuntimeException error) {
                        LOG.warn("Failed to project ChatApp polling row: {}", error.getMessage());
                        skipped++;
                    }
                }
                if (rows.size() < PAGE_SIZE) break;
            }
        } catch (Exception error) {
            LOG.error("ChatApp message polling failed", error);
        }
        long durationMs = elapsedMs(started);
        LOG.info("ChatApp message polling: pages={} fetched={} saved={} skipped={} durationMs={}",
                pages, fetched, saved, skipped, durationMs);
        return new SyncResultRecord(pages, fetched, saved, skipped, durationMs);
    }

    private ChannelAccountEntity resolveChannelAccount() {
        List<ChannelAccountEntity> accounts = channelAccountMapper.selectList(
                new LambdaQueryWrapper<ChannelAccountEntity>()
                        .in(ChannelAccountEntity::getChannelType, List.of("chatapp", "whatsapp"))
                        .eq(ChannelAccountEntity::getAuthStatus, "active")
                        .isNull(ChannelAccountEntity::getDeletedAt)
                        .last("limit 2"));
        if (accounts.isEmpty()) return null;
        if (accounts.size() > 1) {
            throw new IllegalStateException("CHATAPP_FIXED_ACCOUNT_VIOLATION");
        }
        return accounts.get(0);
    }

    private AsyncClient createClient() {
        return AsyncClient.builder()
                .region(ChatAppSendService.defaulted(config.camsRegion(), "ap-southeast-1"))
                .credentialsProvider(createCredentialsProvider())
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(ChatAppSendService.defaulted(
                                config.camsEndpoint(), "cams.ap-southeast-1.aliyuncs.com")))
                .build();
    }

    private ICredentialProvider createCredentialsProvider() {
        String keyId = config.aliyunAccessKeyId();
        String keySecret = config.aliyunAccessKeySecret();
        if (keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank()) {
            return StaticCredentialProvider.create(Credential.builder()
                    .accessKeyId(keyId)
                    .accessKeySecret(keySecret)
                    .build());
        }
        return DefaultCredentialProvider.builder().build();
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    public record SyncResultRecord(int pages, int fetched, int saved, int skipped, long durationMs) {}
}
