package com.crmforlogistics.messagecenter.channel.chatapp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateReconciliationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChatAppTemplateSyncService {
    private final WhatsAppTemplateReconciliationService reconciliationService;
    private final ChannelAccountMapper channelAccountMapper;

    public ChatAppTemplateSyncService(WhatsAppTemplateReconciliationService reconciliationService,
                                      ChannelAccountMapper channelAccountMapper) {
        this.reconciliationService = Objects.requireNonNull(reconciliationService);
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
    }

    public SyncResultRecord runOnce() {
        long started = System.nanoTime();
        UUID accountId = resolveChannelAccountId();
        if (accountId == null) {
            return new SyncResultRecord(0, 0, 0, elapsedMs(started));
        }
        WhatsAppTemplateReconciliationService.SyncResult result = reconciliationService.syncAccount(accountId);
        return new SyncResultRecord(result.pages(), result.fetched(), result.changed(), elapsedMs(started));
    }

    private UUID resolveChannelAccountId() {
        List<ChannelAccountEntity> accounts = channelAccountMapper.selectList(
                new LambdaQueryWrapper<ChannelAccountEntity>()
                        .eq(ChannelAccountEntity::getChannelType, "chatapp")
                        .isNull(ChannelAccountEntity::getDeletedAt)
                        .last("limit 1"));
        return accounts.isEmpty() ? null : accounts.get(0).getId();
    }

    private static long elapsedMs(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    public record SyncResultRecord(int pages, int fetched, int changed, long durationMs) {
    }
}
