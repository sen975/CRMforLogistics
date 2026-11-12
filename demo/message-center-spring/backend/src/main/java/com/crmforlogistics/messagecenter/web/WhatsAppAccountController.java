package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAccountLifecycleService;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAuthorizationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/whatsapp/accounts")
public class WhatsAppAccountController {
    private final WhatsAppAccountLifecycleService service;

    public WhatsAppAccountController(WhatsAppAccountLifecycleService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public List<WhatsAppAccountLifecycleService.AccountProjection> me() {
        return service.listMine(SecurityUtil.currentUserId());
    }

    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> unlink(@PathVariable String accountId) {
        throw selfServiceDisabled();
    }

    @PostMapping("/{accountId}/history-sync")
    public WhatsAppAccountLifecycleService.HistorySyncProjection historySync(@PathVariable UUID accountId) {
        return service.requestHistorySync(SecurityUtil.currentUserId(), accountId);
    }

    private static WhatsAppAuthorizationException selfServiceDisabled() {
        return new WhatsAppAuthorizationException("WHATSAPP_SELF_SERVICE_DISABLED", HttpStatus.GONE);
    }
}
