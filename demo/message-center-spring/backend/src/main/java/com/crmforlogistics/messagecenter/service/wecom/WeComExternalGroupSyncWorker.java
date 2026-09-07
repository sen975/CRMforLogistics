package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.entity.WeComExternalGroupSyncEntity;
import com.crmforlogistics.messagecenter.mapper.WeComExternalGroupSyncMapper;
import com.crmforlogistics.messagecenter.mapper.WeComInstallationMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComExternalGroupSyncWorker {
    private static final int MAX_PAGES = 1000;
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LEASE = Duration.ofMinutes(2);
    private final WeComExternalGroupSyncMapper syncs;
    private final WeComExternalContactService contacts;
    private final WeComExternalGroupSyncPageProcessor pages;
    private final InstallationResolver installations;
    private final EventHub events;
    private final String workerId = "wecom-external-group-" + UUID.randomUUID();

    @Autowired
    public WeComExternalGroupSyncWorker(AppConfig config,
                                        WeComInstallationService installationService,
                                        WeComInstallationMapper installationMapper,
                                        WeComExternalGroupSyncMapper syncs,
                                        WeComExternalContactService contacts,
                                        WeComExternalGroupSyncPageProcessor pages,
                                        EventHub events) {
        this(syncs, contacts, pages, installationId -> {
            WeComInstallationEntity installation = installationMapper.selectById(installationId);
            if (installation == null) {
                throw new WeComException("WECOM_INSTALLATION_NOT_FOUND", 404, "企业微信安装不存在");
            }
            return installationService.resolveInstallation(config.wecomSuiteId(), installation.getAuthCorpId());
        }, events);
    }

    public WeComExternalGroupSyncWorker(WeComExternalGroupSyncMapper syncs,
                                        WeComExternalContactService contacts,
                                        WeComExternalGroupSyncPageProcessor pages,
                                        InstallationResolver installations) {
        this(syncs, contacts, pages, installations, null);
    }

    public WeComExternalGroupSyncWorker(WeComExternalGroupSyncMapper syncs,
                                        WeComExternalContactService contacts,
                                        WeComExternalGroupSyncPageProcessor pages,
                                        InstallationResolver installations,
                                        EventHub events) {
        this.syncs = syncs;
        this.contacts = contacts;
        this.pages = pages;
        this.installations = installations;
        this.events = events;
    }

    public int runOnce(Instant now, int limit) {
        int processed = 0;
        int boundedLimit = Math.min(10, Math.max(1, limit));
        for (WeComExternalGroupSyncEntity run : syncs.listRunnable(now, boundedLimit)) {
            if (syncs.claim(run.getId(), workerId, now.plus(LEASE)) == 0) continue;
            process(run, now);
            processed++;
        }
        return processed;
    }

    private void process(WeComExternalGroupSyncEntity run, Instant now) {
        if (value(run.getPageCount()) >= MAX_PAGES) {
            fail(run, now, "WECOM_EXTERNAL_GROUP_PAGE_LIMIT");
            return;
        }
        try {
            ResolvedInstallation installation = installations.resolve(run.getInstallationId());
            WeComExternalContactService.ExternalGroupPage page = contacts.externalGroupPageForSync(
                    installation, run.getCursor() == null ? "" : run.getCursor());
            pages.persistClaimedPage(run, page, now, workerId);
            if (page.nextCursor().isBlank() && events != null) {
                events.publish("wecom-group-kind-sync-completed",
                        "{\"installationId\":\"" + run.getInstallationId() + "\"}");
            }
        } catch (WeComException failure) {
            retryOrFail(run, now, failure.code());
        } catch (RuntimeException failure) {
            retryOrFail(run, now, "WECOM_EXTERNAL_GROUP_SYNC_RUNTIME");
        }
    }

    private void retryOrFail(WeComExternalGroupSyncEntity run, Instant now, String code) {
        int attempts = value(run.getAttemptCount()) + 1;
        if (attempts >= MAX_ATTEMPTS) {
            fail(run, now, code);
            return;
        }
        long delay = Math.min(900, 30L << Math.min(4, attempts - 1));
        syncs.retry(run.getId(), workerId, code, now.plusSeconds(delay));
    }

    private void fail(WeComExternalGroupSyncEntity run, Instant now, String code) {
        if (syncs.fail(run.getId(), workerId, code, now) == 1) {
            syncs.deleteItems(run.getId());
        }
    }

    private static int value(Integer value) { return value == null ? 0 : value; }

    @FunctionalInterface
    public interface InstallationResolver {
        ResolvedInstallation resolve(UUID installationId);
    }
}
