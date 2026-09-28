package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.response.WhatsAppCallbackConfigView;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAdminAuthorization;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppCallbackConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/whatsapp/cams/{scopeId}/callbacks")
public class WhatsAppCallbackConfigController {
    private final WhatsAppCallbackConfigService service;
    private final WhatsAppAdminAuthorization authorization;

    public WhatsAppCallbackConfigController(WhatsAppCallbackConfigService service,
                                             WhatsAppAdminAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @GetMapping
    public WhatsAppCallbackConfigView list(@PathVariable UUID scopeId) {
        authorization.requireAdmin(SecurityUtil.currentUserId());
        return service.list(scopeId);
    }

    @PutMapping("/phones/{channelAccountId}")
    public WhatsAppCallbackConfigView updatePhone(@PathVariable UUID scopeId,
                                                  @PathVariable UUID channelAccountId,
                                                  @RequestBody PhoneRequest request) {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return service.applyPhone(actor, scopeId, channelAccountId,
                new WhatsAppCallbackConfigService.PhoneRequest(request.upCallbackUrl(),
                        request.statusCallbackUrl(), request.httpFlag(), request.queueFlag(), request.expectedVersion()));
    }

    @PutMapping("/account")
    public WhatsAppCallbackConfigView updateAccount(@PathVariable UUID scopeId,
                                                    @RequestBody AccountRequest request) {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return service.applyAccount(actor, scopeId,
                new WhatsAppCallbackConfigService.AccountRequest(request.statusCallbackUrl(),
                        request.httpFlag(), request.queueFlag(), request.expectedVersion()));
    }

    public record PhoneRequest(String upCallbackUrl, String statusCallbackUrl,
                               String httpFlag, String queueFlag, long expectedVersion) { }
    public record AccountRequest(String statusCallbackUrl, String httpFlag,
                                 String queueFlag, long expectedVersion) { }
}
