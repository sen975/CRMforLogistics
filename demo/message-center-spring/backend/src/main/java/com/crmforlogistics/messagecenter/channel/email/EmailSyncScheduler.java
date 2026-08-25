package com.crmforlogistics.messagecenter.channel.email;

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
@ConditionalOnProperty(name = "app.email-sync-enabled", havingValue = "true")
public class EmailSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmailSyncScheduler.class);

    private final EmailSyncService syncService;
    private final ChannelAccountMapper channelAccountMapper;

    public EmailSyncScheduler(EmailSyncService syncService, ChannelAccountMapper channelAccountMapper) {
        this.syncService = syncService;
        this.channelAccountMapper = channelAccountMapper;
    }

    @Scheduled(fixedDelay = 300_000)
    public void syncEmail() {
        ChannelAccountEntity account = resolveEmailAccount();
        if (account != null) {
            channelAccountMapper.updateSyncStatus(account.getId(), "syncing", null);
        }
        try {
            EmailSyncService.SyncResult result = syncService.receiveLatest();
            log.info("Email sync: {}", result.message());
            if (account != null) {
                channelAccountMapper.updateSyncStatus(account.getId(), "success", Instant.now());
            }
        } catch (Exception e) {
            String code = e instanceof EmailException emailException
                    ? emailException.code() : "EMAIL_SYNC_FAILED";
            log.error("event=email.sync_failed code={} accountId={}", code,
                    account == null ? "" : account.getId(), e);
            if (account != null) {
                channelAccountMapper.updateSyncStatus(account.getId(), "failed", Instant.now());
            }
        }
    }

    private ChannelAccountEntity resolveEmailAccount() {
        List<ChannelAccountEntity> accounts = channelAccountMapper.selectList(
                new LambdaQueryWrapper<ChannelAccountEntity>()
                        .eq(ChannelAccountEntity::getChannelType, "email")
                        .isNull(ChannelAccountEntity::getDeletedAt));
        return accounts.isEmpty() ? null : accounts.get(0);
    }
}
