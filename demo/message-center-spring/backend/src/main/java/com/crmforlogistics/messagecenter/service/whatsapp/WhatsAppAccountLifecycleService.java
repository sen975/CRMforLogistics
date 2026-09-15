package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppAccountAssignmentAuditEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppHistorySyncJobEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppAccountAssignmentAuditMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppHistorySyncJobMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class WhatsAppAccountLifecycleService {
    private final ChannelAccountMapper accounts;
    private final WhatsAppAccountAssignmentAuditMapper audits;
    private final WhatsAppHistorySyncJobMapper historyJobs;

    public WhatsAppAccountLifecycleService(ChannelAccountMapper accounts,
                                            WhatsAppAccountAssignmentAuditMapper audits,
                                            WhatsAppHistorySyncJobMapper historyJobs) {
        this.accounts = accounts;
        this.audits = audits;
        this.historyJobs = historyJobs;
    }

    public List<AccountProjection> listMine(UUID ownerId) {
        return accounts.findAllByOwner(ownerId).stream()
                .filter(WhatsAppAccountLifecycleService::isVisibleWhatsAppAccount)
                .map(WhatsAppAccountLifecycleService::project)
                .toList();
    }

    @Transactional
    public void unlink(UUID ownerId, UUID accountId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw failure("WHATSAPP_UNLINK_REASON_REQUIRED", HttpStatus.BAD_REQUEST);
        }
        ChannelAccountEntity account = accounts.findByIdAndOwner(accountId, ownerId);
        if (!isVisibleWhatsAppAccount(account) || !"active".equalsIgnoreCase(account.getAuthStatus())) {
            throw failure("WHATSAPP_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        if (accounts.disableOwned(ownerId, accountId) != 1) {
            throw failure("WHATSAPP_ACCOUNT_UNAVAILABLE", HttpStatus.CONFLICT);
        }
        WhatsAppAccountAssignmentAuditEntity audit = new WhatsAppAccountAssignmentAuditEntity();
        audit.setId(UUID.randomUUID());
        audit.setChannelAccountId(accountId);
        audit.setPreviousOwnerUserId(ownerId);
        audit.setActorUserId(ownerId);
        audit.setAction("UNLINK");
        audit.setReason(reason.trim());
        if (audits.insert(audit) != 1) {
            throw failure("WHATSAPP_AUDIT_WRITE_FAILED", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public HistorySyncProjection requestHistorySync(UUID ownerId, UUID accountId) {
        ChannelAccountEntity account = accounts.findByIdAndOwner(accountId, ownerId);
        if (!isVisibleWhatsAppAccount(account)) {
            throw failure("WHATSAPP_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        if (!"BUSINESS_APP_COEXISTENCE".equalsIgnoreCase(account.getOnboardingMode())) {
            throw failure("WHATSAPP_HISTORY_SYNC_NOT_ALLOWED", HttpStatus.CONFLICT);
        }
        Instant end = Instant.now();
        historyJobs.insertPendingIfAbsent(accountId, ownerId, end);
        WhatsAppHistorySyncJobEntity job = historyJobs.findLatestByAccountAndOwner(accountId, ownerId);
        return new HistorySyncProjection(job == null ? null : job.getId(), accountId,
                job == null ? "PENDING" : job.getStatus());
    }

    private static boolean isVisibleWhatsAppAccount(ChannelAccountEntity account) {
        return account != null && account.getDeletedAt() == null
                && ("chatapp".equalsIgnoreCase(account.getChannelType())
                || "whatsapp".equalsIgnoreCase(account.getChannelType()))
                && !"disabled".equalsIgnoreCase(account.getAuthStatus());
    }

    private static AccountProjection project(ChannelAccountEntity account) {
        String mode = account.getOnboardingMode();
        String domain = "BUSINESS_APP_COEXISTENCE".equalsIgnoreCase(mode)
                ? "PRIVATE_BUSINESS_APP" : "ENTERPRISE_SHARED";
        String recoverableError = "active".equalsIgnoreCase(account.getAuthStatus())
                ? null : "WHATSAPP_ACCOUNT_AUTH_UNAVAILABLE";
        return new AccountProjection(account.getId(), mode, account.getName(), account.getRemark(),
                maskPhone(account.getAccountIdentifier()), account.getProviderPhoneStatus(),
                account.getPhoneVerificationStatus(), domain, recoverableError);
    }

    private static String maskPhone(String phone) {
        String value = phone == null ? "" : phone.trim();
        if (value.length() <= 4) return value;
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }

    private static WhatsAppAuthorizationException failure(String code, HttpStatus status) {
        return new WhatsAppAuthorizationException(code, status);
    }

    public record AccountProjection(UUID accountId, String mode, String name, String remark,
                                    String maskedPhone, String providerStatus,
                                    String verificationStatus, String templateDomain,
                                    String recoverableError) { }

    public record HistorySyncProjection(UUID jobId, UUID accountId, String status) { }
}
