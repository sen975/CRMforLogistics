package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.entity.WeComExternalGroupSyncEntity;
import com.crmforlogistics.messagecenter.mapper.WeComExternalGroupSyncMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComExternalGroupSyncPageProcessor {
    private final WeComExternalGroupSyncMapper syncs;

    public WeComExternalGroupSyncPageProcessor(WeComExternalGroupSyncMapper syncs) {
        this.syncs = syncs;
    }

    @Transactional
    public void persistClaimedPage(WeComExternalGroupSyncEntity run,
                                   WeComExternalContactService.ExternalGroupPage page,
                                   Instant now, String workerId) {
        if (!page.chatIds().isEmpty()) syncs.insertItems(run.getId(), page.chatIds());
        if (!page.nextCursor().isBlank()) {
            requireLease(syncs.advance(run.getId(), workerId, page.nextCursor(), now));
            return;
        }
        syncs.markSeenExternal(run.getInstallationId(), run.getId());
        syncs.markUnknownUnseenInternal(run.getInstallationId(), run.getId());
        requireLease(syncs.complete(run.getId(), workerId, now));
        syncs.deleteItems(run.getId());
    }

    private static void requireLease(int updated) {
        if (updated != 1) throw new IllegalStateException("WECOM_EXTERNAL_GROUP_SYNC_LEASE_LOST");
    }
}
