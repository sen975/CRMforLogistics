package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppPhoneNumberService;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppPhoneNumberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/admin/whatsapp/phone-numbers")
public class AdminWhatsAppPhoneNumberController {
    private final AdminWhatsAppPhoneNumberService service;

    public AdminWhatsAppPhoneNumberController(AdminWhatsAppPhoneNumberService service) {
        this.service = service;
    }

    @GetMapping
    public List<WhatsAppPhoneNumberService.PhoneNumberStatus> list() { return service.list(); }

    @PostMapping("/{accountId}/assign")
    public void assign(@PathVariable UUID accountId, @Valid @RequestBody AssignmentRequest request) {
        service.assign(SecurityUtil.currentUserId(), accountId, request.targetOwnerId(), request.reason());
    }

    @PostMapping("/{accountId}/disable")
    public void disable(@PathVariable UUID accountId, @Valid @RequestBody ReasonRequest request) {
        service.disable(SecurityUtil.currentUserId(), accountId, request.reason());
    }

    public record AssignmentRequest(@NotNull UUID targetOwnerId, @NotBlank @Size(max = 500) String reason) { }
    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) { }
}
