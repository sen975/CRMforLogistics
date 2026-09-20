package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppWorkbenchService;
import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppWorkbenchService.OverviewView;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAdminAuthorization;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/whatsapp")
public class AdminWhatsAppWorkbenchController {
    private final AdminWhatsAppWorkbenchService service;
    private final WhatsAppAdminAuthorization authorization;

    public AdminWhatsAppWorkbenchController(AdminWhatsAppWorkbenchService service,
                                            WhatsAppAdminAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @GetMapping("/overview")
    public OverviewView overview() {
        UUID actor = SecurityUtil.currentUserId();
        authorization.requireAdmin(actor);
        return service.overview();
    }
}
