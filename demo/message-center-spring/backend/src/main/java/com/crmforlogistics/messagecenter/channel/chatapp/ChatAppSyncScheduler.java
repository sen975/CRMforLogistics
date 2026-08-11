package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.chatapp-sync-enabled", havingValue = "true", matchIfMissing = true)
public class ChatAppSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChatAppSyncScheduler.class);

    private final ChatAppMessageSyncService messageSyncService;
    private final ChatAppTemplateSyncService templateSyncService;
    private final ChannelAccountMapper channelAccountMapper;

    public ChatAppSyncScheduler(ChatAppMessageSyncService messageSyncService,
                                 ChatAppTemplateSyncService templateSyncService,
                                 ChannelAccountMapper channelAccountMapper) {
        this.messageSyncService = messageSyncService;
        this.templateSyncService = templateSyncService;
        this.channelAccountMapper = channelAccountMapper;
    }

    @Scheduled(fixedDelay = 5000)
    public void syncMessages() {
        ChannelAccountEntity account = resolveChatappAccount();
        if (account != null) {
            channelAccountMapper.updateSyncStatus(account.getId(), "syncing", null);
        }
        try {
            ChatAppMessageSyncService.SyncResultRecord result = messageSyncService.runOnce();
            if (result.fetched() > 0) {
                log.info("Message sync: pages={} fetched={} saved={} durationMs={}",
                        result.pages(), result.fetched(), result.saved(), result.durationMs());
            }
            if (account != null) {
                channelAccountMapper.updateSyncStatus(account.getId(), "success", Instant.now());
            }
        } catch (Exception e) {
            log.error("Message sync failed", e);
            if (account != null) {
                channelAccountMapper.updateSyncStatus(account.getId(), "failed", Instant.now());
            }
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void syncTemplates() {
        try {
            ChatAppTemplateSyncService.SyncResultRecord result = templateSyncService.runOnce();
            if (result.fetched() > 0) {
                log.info("Template sync: pages={} fetched={} changed={} durationMs={}",
                        result.pages(), result.fetched(), result.changed(), result.durationMs());
            }
        } catch (Exception e) {
            log.error("Template sync failed", e);
        }
    }

    private ChannelAccountEntity resolveChatappAccount() {
        List<ChannelAccountEntity> accounts = channelAccountMapper.selectList(
                new LambdaQueryWrapper<ChannelAccountEntity>()
                        .eq(ChannelAccountEntity::getChannelType, "chatapp")
                        .isNull(ChannelAccountEntity::getDeletedAt)
                        .last("limit 1"));
        return accounts.isEmpty() ? null : accounts.get(0);
    }
}
