package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.DefaultCredentialProvider;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailRequest;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailResponse;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailResponseBody;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateRequest;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateResponse;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateResponseBody;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import darabonba.core.client.ClientOverrideConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ChatAppTemplateSyncService {

    private static final Logger log = LoggerFactory.getLogger(ChatAppTemplateSyncService.class);
    private static final String CURSOR_TYPE = "chatapp_template";
    private static final String SCOPE_KEY = "default";

    private final AppConfig config;
    private final SyncCursorMapper syncCursorMapper;
    private final TemplateMapper templateMapper;
    private final ChannelAccountMapper channelAccountMapper;

    public ChatAppTemplateSyncService(AppConfig config, SyncCursorMapper syncCursorMapper,
                                       TemplateMapper templateMapper, ChannelAccountMapper channelAccountMapper) {
        this.config = Objects.requireNonNull(config);
        this.syncCursorMapper = Objects.requireNonNull(syncCursorMapper);
        this.templateMapper = Objects.requireNonNull(templateMapper);
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
    }

    public SyncResultRecord runOnce() {
        long started = System.nanoTime();
        int pages = 0;
        int fetched = 0;
        int changed = 0;

        UUID channelAccountId = resolveChannelAccountId();
        if (channelAccountId == null) {
            log.debug("No chatapp channel account found, skipping template sync");
            return new SyncResultRecord(0, 0, 0, elapsedMs(started));
        }

        try (AsyncClient client = createClient()) {
            for (int pageIndex = 1; pageIndex <= 20; pageIndex++) {
                ListChatappTemplateRequest request = ListChatappTemplateRequest.builder()
                        .custSpaceId(config.custSpaceId())
                        .page(ListChatappTemplateRequest.Page.builder()
                                .index(pageIndex)
                                .size(100)
                                .build())
                        .build();

                ListChatappTemplateResponse response = client.listChatappTemplate(request).get();
                ListChatappTemplateResponseBody body = response.getBody();
                if (body == null || body.getListTemplate() == null || body.getListTemplate().isEmpty()) {
                    break;
                }

                pages++;
                List<ListChatappTemplateResponseBody.ListTemplate> templates = body.getListTemplate();
                fetched += templates.size();

                for (ListChatappTemplateResponseBody.ListTemplate tpl : templates) {
                    try {
                        GetChatappTemplateDetailRequest detailReq = GetChatappTemplateDetailRequest.builder()
                                .custSpaceId(config.custSpaceId())
                                .templateCode(tpl.getTemplateCode())
                                .language(tpl.getLanguage())
                                .build();
                        GetChatappTemplateDetailResponse detailResp =
                                client.getChatappTemplateDetail(detailReq).get();
                        GetChatappTemplateDetailResponseBody.Data detail =
                                detailResp.getBody() != null ? detailResp.getBody().getData() : null;

                        String bodyText = extractBodyText(detail != null ? detail.getComponents() : null);
                        TemplateEntity entity = new TemplateEntity();
                        entity.setChannelAccountId(channelAccountId);
                        entity.setProviderTemplateId(tpl.getTemplateCode());
                        entity.setName(tpl.getTemplateName());
                        entity.setLanguageCode(tpl.getLanguage());
                        entity.setBody(bodyText);
                        entity.setStatus("APPROVED");
                        entity.setProviderUpdatedAt(Instant.now());
                        entity.setLastSyncedAt(Instant.now());
                        templateMapper.insert(entity);
                        changed++;
                    } catch (Exception e) {
                        log.warn("Failed to sync template {}: {}", tpl.getTemplateCode(), e.getMessage());
                    }
                }

                if (templates.size() < 100) break;
            }

            syncCursorMapper.upsertCursor(channelAccountId, CURSOR_TYPE, SCOPE_KEY,
                    String.valueOf(pages), Instant.now());
        } catch (Exception e) {
            log.error("ChatApp template sync failed", e);
        }

        long durationMs = elapsedMs(started);
        log.info("ChatApp template sync: pages={} fetched={} changed={} durationMs={}",
                pages, fetched, changed, durationMs);
        return new SyncResultRecord(pages, fetched, changed, durationMs);
    }

    private UUID resolveChannelAccountId() {
        List<ChannelAccountEntity> accounts = channelAccountMapper.selectList(
                new LambdaQueryWrapper<ChannelAccountEntity>()
                        .eq(ChannelAccountEntity::getChannelType, "chatapp")
                        .isNull(ChannelAccountEntity::getDeletedAt)
                        .last("limit 1"));
        return accounts.isEmpty() ? null : accounts.get(0).getId();
    }

    private static String extractBodyText(
            List<GetChatappTemplateDetailResponseBody.Components> components) {
        if (components == null || components.isEmpty()) return "";
        for (var c : components) {
            if ("BODY".equalsIgnoreCase(c.getType())) {
                String text = ChatAppSendService.firstNonBlank(c.getText(), c.getCaption(), "");
                if (!text.isBlank()) return text;
            }
        }
        return "";
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

    public record SyncResultRecord(int pages, int fetched, int changed, long durationMs) {}
}
