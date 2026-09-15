package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppAccountSyncService;
import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppPhoneNumberService;
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
@RequestMapping("/api/admin/whatsapp/accounts")
public class AdminWhatsAppPhoneNumberController {
    private final AdminWhatsAppPhoneNumberService service;
    private final AdminWhatsAppAccountSyncService syncService;

    public AdminWhatsAppPhoneNumberController(AdminWhatsAppPhoneNumberService service,
                                              AdminWhatsAppAccountSyncService syncService) {
        this.service = service;
        this.syncService = syncService;
    }

    @GetMapping
    public List<AdminWhatsAppPhoneNumberService.AccountProjection> list() {
        return service.list(SecurityUtil.currentUserId());
    }

    @PostMapping("/sync")
    public AdminWhatsAppAccountSyncService.SyncResult sync() {
        return syncService.sync(SecurityUtil.currentUserId());
    }

    @PostMapping("/{accountId}/assign")
    public AdminWhatsAppPhoneNumberService.AccountProjection assign(
            @PathVariable UUID accountId, @Valid @RequestBody AssignmentRequest request) {
        return service.assign(SecurityUtil.currentUserId(), accountId, request.targetOwnerId(),
                request.reason(), request.expectedVersion());
    }

    @PostMapping("/{accountId}/reclaim")
    public AdminWhatsAppPhoneNumberService.AccountProjection reclaim(
            @PathVariable UUID accountId, @Valid @RequestBody VersionedReasonRequest request) {
        return service.reclaim(SecurityUtil.currentUserId(), accountId, request.reason(),
                request.expectedVersion());
    }

    @PostMapping("/{accountId}/transfer")
    public AdminWhatsAppPhoneNumberService.AccountProjection transfer(
            @PathVariable UUID accountId, @Valid @RequestBody AssignmentRequest request) {
        return service.transfer(SecurityUtil.currentUserId(), accountId, request.targetOwnerId(),
                request.reason(), request.expectedVersion());
    }

    @GetMapping("/{accountId}/assignment-history")
    public List<AdminWhatsAppPhoneNumberService.AssignmentAuditProjection> assignmentHistory(
            @PathVariable UUID accountId) {
        return service.assignmentHistory(SecurityUtil.currentUserId(), accountId);
    }

    public record AssignmentRequest(@NotNull UUID targetOwnerId,
                                    @NotBlank @Size(max = 500) String reason,
                                    @NotNull @PositiveOrZero Long expectedVersion) { }

    public record VersionedReasonRequest(@NotBlank @Size(max = 500) String reason,
                                         @NotNull @PositiveOrZero Long expectedVersion) { }
}
