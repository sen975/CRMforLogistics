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
        for (ChannelAccountEntity account : resolveEmailAccounts()) {
            if (account.getOwnerUserId() == null) {
                log.warn("event=email.sync_skipped accountId={} reason=missing_owner", account.getId());
                continue;
            }
            channelAccountMapper.updateSyncStatusOwned(account.getOwnerUserId(), account.getId(), "syncing", null);
            try {
                EmailSyncService.SyncResult result = syncService.receiveLatest(account.getId(), account.getOwnerUserId());
                log.info("Email sync accountId={}: {}", account.getId(), result.message());
                channelAccountMapper.updateSyncStatusOwned(account.getOwnerUserId(), account.getId(), "success", Instant.now());
            } catch (Exception e) {
                String code = e instanceof EmailException emailException
                        ? emailException.code() : "EMAIL_SYNC_FAILED";
                log.error("event=email.sync_failed code={} accountId={}", code, account.getId(), e);
                channelAccountMapper.updateSyncStatusOwned(account.getOwnerUserId(), account.getId(), "failed", Instant.now());
            }
        }
    }

    private List<ChannelAccountEntity> resolveEmailAccounts() {
        List<ChannelAccountEntity> accounts = channelAccountMapper.selectList(
                new LambdaQueryWrapper<ChannelAccountEntity>()
                        .eq(ChannelAccountEntity::getChannelType, "email")
                        .eq(ChannelAccountEntity::getAuthStatus, "active")
                        .isNotNull(ChannelAccountEntity::getOwnerUserId)
                        .isNull(ChannelAccountEntity::getDeletedAt));
        return accounts;
    }
}
