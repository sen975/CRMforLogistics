package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppAccountSyncService;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAdminAuthorization;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppCamsConfigService;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppCamsConfigService.ConfigRequest;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppCamsConfigService.ConfigView;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppCamsConfigService.TestResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/whatsapp/cams")
public class WhatsAppCamsConfigController {
    private final WhatsAppCamsConfigService service;
    private final AdminWhatsAppAccountSyncService accountSync;
    private final WhatsAppAdminAuthorization authorization;

    public WhatsAppCamsConfigController(WhatsAppCamsConfigService service,
                                        AdminWhatsAppAccountSyncService accountSync,
                                        WhatsAppAdminAuthorization authorization) {
        this.service = service;
        this.accountSync = accountSync;
        this.authorization = authorization;
    }

    @GetMapping
    public List<ConfigView> list() {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return service.list();
    }

    @PostMapping
    public ConfigView create(@RequestBody ConfigRequest request) {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return service.create(actor, request);
    }

    @PutMapping("/{scopeId}")
    public ConfigView update(@PathVariable UUID scopeId, @RequestBody ConfigRequest request) {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return service.update(actor, scopeId, request);
    }

    @DeleteMapping("/{scopeId}")
    public ConfigView block(@PathVariable UUID scopeId, @RequestBody VersionRequest request) {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return service.block(actor, scopeId, request.expectedVersion());
    }

    @PostMapping("/{scopeId}/test")
    public TestResult test(@PathVariable UUID scopeId) {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return service.test(scopeId);
    }

    @PostMapping("/{scopeId}/sync")
    public AdminWhatsAppAccountSyncService.SyncResult sync(@PathVariable UUID scopeId) {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return accountSync.sync(actor, scopeId);
    }

    /** Compatibility endpoint for the pre-multi-scope settings page. */
    @PutMapping
    public ConfigView saveLegacy(@RequestBody ConfigRequest request) {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return service.save(actor, request);
    }

    /** Compatibility endpoint for the pre-multi-scope settings page. */
    @PostMapping("/test")
    public TestResult testLegacy() {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return service.test();
    }

    public record VersionRequest(long expectedVersion) { }
}
