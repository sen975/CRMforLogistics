package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAuthorizationService;
import jakarta.validation.Valid;
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
            @RequestBody(required = false) @Valid StartRequest request) {
        if (request == null) {
            return service.createAttempt(SecurityUtil.currentUserId());
        }
        return service.createAttempt(SecurityUtil.currentUserId(), request.onboardingMode(),
                request.accountName(), request.accountRemark());
    }

    @PostMapping("/complete")
    public WhatsAppAuthorizationService.CompletionProjection complete(
            @Valid @RequestBody CompletionRequest request) {
        return service.completeAuthorization(SecurityUtil.currentUserId(),
                new WhatsAppAuthorizationService.SelfServiceCompletionCommand(
                        request.attemptId(), request.state(), request.event(), request.code(),
                request.wabaId(), request.phoneNumberId()));
    }

    @PostMapping("/complete/{attemptId}/phone")
    public WhatsAppAuthorizationService.CompletionProjection selectPhone(
            @PathVariable UUID attemptId,
            @Valid @RequestBody PhoneSelectionRequest request) {
        return service.selectBusinessAppPhone(SecurityUtil.currentUserId(),
                new WhatsAppAuthorizationService.PhoneSelectionCommand(
                        attemptId, request.state(), request.candidateId()));
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
