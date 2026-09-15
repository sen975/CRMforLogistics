package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppAccountAssignmentAuditEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.mapper.UserMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppAccountAssignmentAuditMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AdminWhatsAppPhoneNumberService {
    private final ChannelAccountMapper accounts;
    private final WhatsAppAccountAssignmentAuditMapper audits;
    private final ConversationMapper conversations;
    private final UserMapper users;
    private final RoleMapper roles;

    public AdminWhatsAppPhoneNumberService(ChannelAccountMapper accounts,
                                           WhatsAppAccountAssignmentAuditMapper audits,
                                           WhatsAppPhoneNumberService ignored) {
        this(accounts, audits, null, null, null);
    }

    @Autowired
    public AdminWhatsAppPhoneNumberService(ChannelAccountMapper accounts,
                                           WhatsAppAccountAssignmentAuditMapper audits,
                                           ConversationMapper conversations,
                                           UserMapper users,
                                           RoleMapper roles) {
        this.accounts = accounts;
        this.audits = audits;
        this.conversations = conversations;
        this.users = users;
        this.roles = roles;
    }

    @Transactional(readOnly = true)
    public List<AccountProjection> list() {
        return list(null);
    }

    @Transactional(readOnly = true)
    public List<AccountProjection> list(UUID actorId) {
        if (actorId != null) requireAdmin(actorId);
        List<ChannelAccountEntity> values = accounts.findAllWhatsAppForSync();
        return values == null ? List.of() : values.stream().map(AdminWhatsAppPhoneNumberService::project).toList();
    }

    @Transactional
    public AccountProjection assign(UUID actorId, UUID accountId, UUID targetOwnerId,
                                    String reason, Long expectedVersion) {
        requireAdmin(actorId);
        validateInput(targetOwnerId, reason, expectedVersion);
        ChannelAccountEntity account = lockAccount(accountId);
        validateTarget(targetOwnerId);
        checkVersion(account, expectedVersion);
        if (targetOwnerId.equals(account.getOwnerUserId())) return project(account);
        if (account.getOwnerUserId() != null) {
            throw failure("WHATSAPP_ASSIGNMENT_CONFLICT", HttpStatus.CONFLICT);
        }
        ensureTargetHasNoAccount(targetOwnerId);
        if (accounts.assignWhatsAppOwner(accountId, targetOwnerId, expectedVersion) != 1) {
            throw failure("WHATSAPP_ASSIGNMENT_CONFLICT", HttpStatus.CONFLICT);
        }
        grantHistory(accountId, targetOwnerId, actorId);
        audit(accountId, null, targetOwnerId, actorId, "ASSIGN", reason);
        account.setOwnerUserId(targetOwnerId);
        account.setVersion(expectedVersion + 1);
        return project(account);
    }

    @Transactional
    public AccountProjection reclaim(UUID actorId, UUID accountId, String reason, Long expectedVersion) {
        requireAdmin(actorId);
        validateInput(null, reason, expectedVersion);
        ChannelAccountEntity account = lockAccount(accountId);
        checkVersion(account, expectedVersion);
        if (account.getOwnerUserId() == null) {
            throw failure("WHATSAPP_ASSIGNMENT_CONFLICT", HttpStatus.CONFLICT);
        }
        UUID previousOwner = account.getOwnerUserId();
        if (accounts.reclaimWhatsAppOwner(accountId, expectedVersion) != 1) {
            throw failure("WHATSAPP_ASSIGNMENT_CONFLICT", HttpStatus.CONFLICT);
        }
        audit(accountId, previousOwner, null, actorId, "RECLAIM", reason);
        account.setOwnerUserId(null);
        account.setVersion(expectedVersion + 1);
        return project(account);
    }

    @Transactional
    public AccountProjection transfer(UUID actorId, UUID accountId, UUID targetOwnerId,
                                      String reason, Long expectedVersion) {
        requireAdmin(actorId);
        validateInput(targetOwnerId, reason, expectedVersion);
        ChannelAccountEntity account = lockAccount(accountId);
        validateTarget(targetOwnerId);
        checkVersion(account, expectedVersion);
        if (targetOwnerId.equals(account.getOwnerUserId())) return project(account);
        UUID previousOwner = account.getOwnerUserId();
        if (previousOwner == null) {
            throw failure("WHATSAPP_ASSIGNMENT_CONFLICT", HttpStatus.CONFLICT);
        }
        ensureTargetHasNoAccount(targetOwnerId);
        if (accounts.transferWhatsAppOwner(accountId, targetOwnerId, expectedVersion) != 1) {
            throw failure("WHATSAPP_ASSIGNMENT_CONFLICT", HttpStatus.CONFLICT);
        }
        grantHistory(accountId, targetOwnerId, actorId);
        audit(accountId, previousOwner, targetOwnerId, actorId, "TRANSFER", reason);
        account.setOwnerUserId(targetOwnerId);
        account.setVersion(expectedVersion + 1);
        return project(account);
    }

    @Transactional(readOnly = true)
    public List<AssignmentAuditProjection> assignmentHistory(UUID actorId, UUID accountId) {
        requireAdmin(actorId);
        ChannelAccountEntity account = accounts.selectById(accountId);
        if (!isWhatsApp(account)) {
            throw failure("WHATSAPP_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        List<WhatsAppAccountAssignmentAuditEntity> values = audits.findByAccountId(account.getId());
        return values == null ? List.of() : values.stream()
                .map(value -> new AssignmentAuditProjection(value.getId(), value.getPreviousOwnerUserId(),
                        value.getNextOwnerUserId(), value.getActorUserId(), value.getAction(),
                        value.getReason(), value.getCreatedAt()))
                .toList();
    }

    private ChannelAccountEntity lockAccount(UUID accountId) {
        ChannelAccountEntity account = accounts.findWhatsAppByIdForUpdate(accountId);
        if (!isWhatsApp(account)) throw failure("WHATSAPP_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND);
        if ("disabled".equalsIgnoreCase(account.getAuthStatus())) {
            throw failure("WHATSAPP_ACCOUNT_NOT_SENDABLE", HttpStatus.CONFLICT);
        }
        return account;
    }

    private void validateTarget(UUID targetOwnerId) {
        if (users == null) return;
        Optional<?> target = targetOwnerId == null ? Optional.empty() : users.findAssignableSalesUser(targetOwnerId);
        if (target == null || target.isEmpty()) {
            throw failure("WHATSAPP_TARGET_USER_INVALID", HttpStatus.CONFLICT);
        }
    }

    private void ensureTargetHasNoAccount(UUID targetOwnerId) {
        if (accounts.countActiveWhatsAppByOwner(targetOwnerId) > 0) {
            throw failure("WHATSAPP_TARGET_ACCOUNT_ALREADY_EXISTS", HttpStatus.CONFLICT);
        }
    }

    private void grantHistory(UUID accountId, UUID userId, UUID grantedBy) {
        if (conversations != null) conversations.grantAccountHistory(accountId, userId, grantedBy);
    }

    private void audit(UUID accountId, UUID previousOwner, UUID nextOwner,
                       UUID actorId, String action, String reason) {
        WhatsAppAccountAssignmentAuditEntity audit = new WhatsAppAccountAssignmentAuditEntity();
        audit.setId(UUID.randomUUID());
        audit.setChannelAccountId(accountId);
        audit.setPreviousOwnerUserId(previousOwner);
        audit.setNextOwnerUserId(nextOwner);
        audit.setActorUserId(actorId);
        audit.setAction(action);
        audit.setReason(reason.trim());
        if (audits.insert(audit) != 1) {
            throw failure("WHATSAPP_AUDIT_WRITE_FAILED", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private void requireAdmin(UUID actorId) {
        if (roles != null && (actorId == null || !roles.userHasRole(actorId, "admin"))) {
            throw failure("WHATSAPP_ADMIN_REQUIRED", HttpStatus.FORBIDDEN);
        }
    }

    private static void validateInput(UUID targetOwnerId, String reason, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0 || reason == null || reason.isBlank()) {
            throw failure("WHATSAPP_ASSIGNMENT_INPUT_INVALID", HttpStatus.BAD_REQUEST);
        }
    }

    private static void checkVersion(ChannelAccountEntity account, long expectedVersion) {
        long current = account.getVersion() == null ? 0L : account.getVersion();
        if (current != expectedVersion) {
            throw failure("WHATSAPP_ASSIGNMENT_CONFLICT", HttpStatus.CONFLICT);
        }
    }

    private static boolean isWhatsApp(ChannelAccountEntity account) {
        return account != null && account.getDeletedAt() == null
                && ("chatapp".equalsIgnoreCase(account.getChannelType())
                || "whatsapp".equalsIgnoreCase(account.getChannelType()));
    }

    private static AccountProjection project(ChannelAccountEntity account) {
        return new AccountProjection(account.getId(), account.getOwnerUserId(),
                maskPhone(account.getAccountIdentifier()), account.getName(),
                account.getProviderPhoneStatus(), account.getPhoneVerificationStatus(),
                account.getVersion() == null ? 0L : account.getVersion());
    }

    private static String maskPhone(String phone) {
        String value = phone == null ? "" : phone.trim();
        if (value.length() <= 4) return value;
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }

    private static WhatsAppAuthorizationException failure(String code, HttpStatus status) {
        return new WhatsAppAuthorizationException(code, status);
    }

    public record AccountProjection(UUID accountId, UUID ownerUserId, String maskedPhone,
                                    String name, String providerStatus,
                                    String verificationStatus, long version) { }

    public record AssignmentAuditProjection(UUID auditId, UUID previousOwnerUserId,
                                             UUID nextOwnerUserId, UUID actorUserId,
                                             String action, String reason,
                                             java.time.Instant createdAt) { }
}
