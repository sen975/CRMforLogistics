package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAuthorizationException;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAuthorizationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/whatsapp/authorization")
public class WhatsAppAuthorizationController {
    private final WhatsAppAuthorizationService service;

    public WhatsAppAuthorizationController(WhatsAppAuthorizationService service) {
        this.service = service;
    }

    @PostMapping("/attempts")
    public WhatsAppAuthorizationService.AttemptProjection createAttempt(
            @RequestBody(required = false) StartRequest request) {
        throw selfServiceDisabled();
    }

    @PostMapping("/complete")
    public WhatsAppAuthorizationService.CompletionProjection complete(
            @RequestBody(required = false) CompletionRequest request) {
        throw selfServiceDisabled();
    }

    @PostMapping("/complete/{attemptId}/phone")
    public WhatsAppAuthorizationService.CompletionProjection selectPhone(
            @PathVariable String attemptId,
            @RequestBody(required = false) PhoneSelectionRequest request) {
        throw selfServiceDisabled();
    }

    private static WhatsAppAuthorizationException selfServiceDisabled() {
        return new WhatsAppAuthorizationException("WHATSAPP_SELF_SERVICE_DISABLED", HttpStatus.GONE);
    }

    public record StartRequest(
            @NotBlank @Pattern(regexp = "ADMIN_API_WABA|EMPLOYEE_BUSINESS_APP") String onboardingMode,
            @Size(max = 100) String accountName,
            @Size(max = 500) String accountRemark) { }

    public record CompletionRequest(
            @NotNull UUID attemptId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{32,256}") String state,
            @NotBlank @Pattern(regexp = "(?i)FINISH") String event,
            @NotBlank @Size(max = 128) String wabaId,
            @NotBlank @Size(max = 128) String phoneNumberId,
            @NotBlank @Size(max = 512) String code) { }

    public record PhoneSelectionRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{32,256}") String state,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{32,256}") String candidateId) { }
}
