package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppAccountAssignmentAuditEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppAccountAssignmentAuditMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AdminWhatsAppPhoneNumberService {
    private final ChannelAccountMapper accounts;
    private final WhatsAppAccountAssignmentAuditMapper audits;
    private final WhatsAppPhoneNumberService phoneNumbers;

    public AdminWhatsAppPhoneNumberService(ChannelAccountMapper accounts,
                                           WhatsAppAccountAssignmentAuditMapper audits,
                                           WhatsAppPhoneNumberService phoneNumbers) {
        this.accounts = accounts;
        this.audits = audits;
        this.phoneNumbers = phoneNumbers;
    }

    public List<WhatsAppPhoneNumberService.PhoneNumberStatus> list() {
        return phoneNumbers.list(null);
    }

    @Transactional
    public void assign(UUID actorId, UUID accountId, UUID targetOwnerId, String reason) {
        if (targetOwnerId == null || reason == null || reason.isBlank()) {
            throw failure("WHATSAPP_ASSIGNMENT_INPUT_INVALID", HttpStatus.BAD_REQUEST);
        }
        ChannelAccountEntity account = accounts.selectById(accountId);
        if (!isWhatsApp(account)) throw failure("WHATSAPP_ACCOUNT_REQUIRED", HttpStatus.NOT_FOUND);
        if (targetOwnerId.equals(account.getOwnerUserId())) return;
        if (accounts.reassignActiveWhatsAppAccount(accountId, targetOwnerId, actorId) != 1) {
            throw failure("WHATSAPP_ASSIGNMENT_CONFLICT", HttpStatus.CONFLICT);
        }
        audit(accountId, account.getOwnerUserId(), targetOwnerId, actorId, "ASSIGN", reason);
    }

    @Transactional
    public void disable(UUID actorId, UUID accountId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw failure("WHATSAPP_ASSIGNMENT_INPUT_INVALID", HttpStatus.BAD_REQUEST);
        }
        ChannelAccountEntity account = accounts.selectById(accountId);
        if (!isWhatsApp(account)) throw failure("WHATSAPP_ACCOUNT_REQUIRED", HttpStatus.NOT_FOUND);
        if (accounts.disableWhatsAppAccount(accountId) != 1) {
            throw failure("WHATSAPP_ACCOUNT_UNAVAILABLE", HttpStatus.CONFLICT);
        }
        audit(accountId, account.getOwnerUserId(), null, actorId, "DISABLE", reason);
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
        if (audits.insert(audit) != 1) throw failure("WHATSAPP_AUDIT_WRITE_FAILED", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static boolean isWhatsApp(ChannelAccountEntity account) {
        return account != null && account.getDeletedAt() == null
                && ("chatapp".equalsIgnoreCase(account.getChannelType())
                || "whatsapp".equalsIgnoreCase(account.getChannelType()));
    }

    private static WhatsAppAuthorizationException failure(String code, HttpStatus status) {
        return new WhatsAppAuthorizationException(code, status);
    }
}
