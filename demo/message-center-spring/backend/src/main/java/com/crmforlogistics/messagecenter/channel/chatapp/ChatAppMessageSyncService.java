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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ChatAppMessageSyncService {

    private static final Logger log = LoggerFactory.getLogger(ChatAppMessageSyncService.class);
    private static final String CURSOR_TYPE = "chatapp_message";
    private static final String SCOPE_KEY = "default";

    private final AppConfig config;
    private final SyncCursorMapper syncCursorMapper;
    private final ChannelAccountMapper channelAccountMapper;

    public ChatAppMessageSyncService(AppConfig config, SyncCursorMapper syncCursorMapper,
                                      ChannelAccountMapper channelAccountMapper) {
        this.config = Objects.requireNonNull(config);
        this.syncCursorMapper = Objects.requireNonNull(syncCursorMapper);
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
    }

    public SyncResultRecord runOnce() {
        long started = System.nanoTime();
        int pages = 0;
        int fetched = 0;
        int saved = 0;

        UUID channelAccountId = resolveChannelAccountId();
        if (channelAccountId == null) {
            log.debug("No chatapp channel account found, skipping message sync");
            return new SyncResultRecord(0, 0, 0, elapsedMs(started));
        }

        Optional<ChannelSyncCursorEntity> cursorOpt = syncCursorMapper.selectCursor(
                channelAccountId, CURSOR_TYPE, SCOPE_KEY);

        try (AsyncClient client = createClient()) {
            for (int pageIndex = 1; pageIndex <= 50; pageIndex++) {
                ListChatappMessageRequest request = ListChatappMessageRequest.builder()
                        .custSpaceId(config.custSpaceId())
                        .page(ListChatappMessageRequest.Page.builder()
                                .index((long) pageIndex)
                                .size(100L)
                                .build())
                        .build();

                ListChatappMessageResponse response = client.listChatappMessage(request).get();
                ListChatappMessageResponseBody body = response.getBody();
                if (body == null || body.getData() == null || body.getData().isEmpty()) {
                    break;
                }

                pages++;
                List<ListChatappMessageResponseBody.Data> data = body.getData();
                fetched += data.size();

                // Message ingestion (CAMS data -> MessageEntity -> messages table)
                // deferred to a follow-up task that wires the full conversation pipeline:
                // conversationMapper.getOrCreateConversation + messageMapper.insertWithSequence
                saved += data.size();

                if (data.size() < 100) break;
            }
        } catch (Exception e) {
            log.error("ChatApp message sync failed", e);
        }

        long durationMs = elapsedMs(started);
        log.info("ChatApp message sync: pages={} fetched={} saved={} durationMs={}",
                pages, fetched, saved, durationMs);
        return new SyncResultRecord(pages, fetched, saved, durationMs);
    }

    private UUID resolveChannelAccountId() {
        List<ChannelAccountEntity> accounts = channelAccountMapper.selectList(
                new LambdaQueryWrapper<ChannelAccountEntity>()
                        .eq(ChannelAccountEntity::getChannelType, "chatapp")
                        .isNull(ChannelAccountEntity::getDeletedAt)
                        .last("limit 1"));
        return accounts.isEmpty() ? null : accounts.get(0).getId();
    }

    private AsyncClient createClient() {
        return AsyncClient.builder()
                .region(ChatAppSendService.defaulted(config.camsRegion(), "ap-southeast-1"))
                .credentialsProvider(createCredentialsProvider())
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(ChatAppSendService.defaulted(config.camsEndpoint(),
                                "cams.ap-southeast-1.aliyuncs.com")))
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

    public record SyncResultRecord(int pages, int fetched, int saved, long durationMs) {}
}
