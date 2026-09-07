package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.PropertyResult;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class WhatsAppTemplatePermissionReconciliationService {
    private static final int CANDIDATE_LIMIT = 20;
    private static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(6), Duration.ofHours(24));

    private final TemplateMapper templateMapper;
    private final WhatsAppTemplateGateway gateway;
    private final Clock clock;

    public WhatsAppTemplatePermissionReconciliationService(
            TemplateMapper templateMapper, WhatsAppTemplateGateway gateway, Clock clock) {
        this.templateMapper = Objects.requireNonNull(templateMapper);
        this.gateway = Objects.requireNonNull(gateway);
        this.clock = Objects.requireNonNull(clock);
    }

    public ReconciliationResult reconcileDueTemplates(UUID channelAccountId, String workerId) {
        if (channelAccountId == null) {
            throw new IllegalArgumentException("TEMPLATE_PERMISSION_ACCOUNT_ID_REQUIRED");
        }
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("TEMPLATE_PERMISSION_WORKER_ID_REQUIRED");
        }
        Instant now = clock.instant();
        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        List<TemplateEntity> candidates =
                templateMapper.findPermissionReconciliationCandidates(
                        channelAccountId, now, CANDIDATE_LIMIT);
        for (TemplateEntity template : candidates) {
            long version = template.getVersion() == null ? 0L : template.getVersion();
            if (templateMapper.markPermissionPending(template.getId(), version, now) != 1) {
                skipped++;
                continue;
            }
            boolean desired = Boolean.TRUE.equals(template.getDesiredAllowSend());
            try {
                PropertyResult result = gateway.setSendPermission(
                        template.getChannelAccountId(), template.getProviderTemplateId(),
                        template.getLanguageCode(), desired);
                if (templateMapper.markPermissionSucceeded(
                        template.getId(), desired, result.allowSend(), now) == 1) {
                    succeeded++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException error) {
                int attempts = Math.max(0, value(template.getPermissionSyncAttemptCount())) + 1;
                int updated = templateMapper.markPermissionFailed(
                        template.getId(), desired, attempts, now.plus(backoff(attempts)),
                        errorCode(error), safeMessage(error), now);
                if (updated == 1) {
                    failed++;
                } else {
                    skipped++;
                }
            }
        }
        return new ReconciliationResult(candidates.size(), succeeded, failed, skipped);
    }

    static Duration backoff(int attemptCount) {
        int index = Math.max(1, attemptCount) - 1;
        return BACKOFF.get(Math.min(index, BACKOFF.size() - 1));
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String errorCode(RuntimeException error) {
        if (error instanceof WhatsAppTemplateException templateError) {
            return templateError.code();
        }
        return "TEMPLATE_PERMISSION_SYNC_FAILED";
    }

    private static String safeMessage(RuntimeException error) {
        return TemplatePermissionErrorSanitizer.sanitize(error.getMessage());
    }

    public record ReconciliationResult(int candidates, int succeeded, int failed, int skipped) {
    }
}
