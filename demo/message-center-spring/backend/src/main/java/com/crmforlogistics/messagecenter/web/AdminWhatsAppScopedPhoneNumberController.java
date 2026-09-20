package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppAccountSyncService;
import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppPhoneNumberService;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAdminAuthorization;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/whatsapp/cams/{scopeId}/accounts")
public class AdminWhatsAppScopedPhoneNumberController {
    private final AdminWhatsAppPhoneNumberService service;
    private final AdminWhatsAppAccountSyncService syncService;
    private final WhatsAppAdminAuthorization authorization;

    public AdminWhatsAppScopedPhoneNumberController(AdminWhatsAppPhoneNumberService service,
                                                    AdminWhatsAppAccountSyncService syncService,
                                                    WhatsAppAdminAuthorization authorization) {
        this.service = service;
        this.syncService = syncService;
        this.authorization = authorization;
    }

    @GetMapping
    public List<AdminWhatsAppPhoneNumberService.AccountProjection> list(@PathVariable UUID scopeId) {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return service.list(actor, scopeId);
    }

    @PostMapping("/sync")
    public AdminWhatsAppAccountSyncService.SyncResult sync(@PathVariable UUID scopeId) {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return syncService.sync(actor, scopeId);
    }

    @PostMapping("/{accountId}/assign")
    public AdminWhatsAppPhoneNumberService.AccountProjection assign(
            @PathVariable UUID scopeId, @PathVariable UUID accountId,
            @Valid @RequestBody AssignmentRequest request) {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return service.assign(actor, scopeId, accountId, request.targetOwnerId(), request.reason(), request.expectedVersion());
    }

    @PostMapping("/{accountId}/reclaim")
    public AdminWhatsAppPhoneNumberService.AccountProjection reclaim(
            @PathVariable UUID scopeId, @PathVariable UUID accountId,
            @Valid @RequestBody VersionedReasonRequest request) {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return service.reclaim(actor, scopeId, accountId, request.reason(), request.expectedVersion());
    }

    @PostMapping("/{accountId}/transfer")
    public AdminWhatsAppPhoneNumberService.AccountProjection transfer(
            @PathVariable UUID scopeId, @PathVariable UUID accountId,
            @Valid @RequestBody AssignmentRequest request) {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return service.transfer(actor, scopeId, accountId, request.targetOwnerId(), request.reason(), request.expectedVersion());
    }

    @GetMapping("/{accountId}/assignment-history")
    public List<AdminWhatsAppPhoneNumberService.AssignmentAuditProjection> assignmentHistory(
            @PathVariable UUID scopeId, @PathVariable UUID accountId) {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return service.assignmentHistory(actor, scopeId, accountId);
    }

    public record AssignmentRequest(@NotNull UUID targetOwnerId,
                                    @NotBlank @Size(max = 500) String reason,
                                    @NotNull @PositiveOrZero Long expectedVersion) { }

    public record VersionedReasonRequest(@NotBlank @Size(max = 500) String reason,
                                         @NotNull @PositiveOrZero Long expectedVersion) { }
}
