package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAuthorizationException;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppPhoneNumberService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/whatsapp/api-phone-operations")
public class WhatsAppPhoneNumberController {
    private final WhatsAppPhoneNumberService service;

    public WhatsAppPhoneNumberController(WhatsAppPhoneNumberService service) {
        this.service = service;
    }

    @PostMapping
    public WhatsAppPhoneNumberService.Status add(@RequestBody(required = false) AddRequest request) {
        throw selfServiceDisabled();
    }

    @PostMapping("/{operationId}/verification-code")
    public WhatsAppPhoneNumberService.Status sendCode(@PathVariable String operationId,
                                                       @RequestBody(required = false) CodeRequest request) {
        throw selfServiceDisabled();
    }

    @PostMapping("/{operationId}/verify")
    public WhatsAppPhoneNumberService.Status verify(@PathVariable String operationId,
                                                     @RequestBody(required = false) VerifyRequest request) {
        throw selfServiceDisabled();
    }

    @GetMapping
    public java.util.List<WhatsAppPhoneNumberService.OperationProjection> list() {
        throw selfServiceDisabled();
    }

    private static WhatsAppAuthorizationException selfServiceDisabled() {
        return new WhatsAppAuthorizationException("WHATSAPP_SELF_SERVICE_DISABLED", HttpStatus.GONE);
    }

    public record AddRequest(@NotBlank @Pattern(regexp = "[0-9]{1,4}") String countryCode,
                             @NotBlank @Pattern(regexp = "[+0-9 ()-]{6,24}") String phoneNumber,
                             @NotBlank @Size(max = 100) String verifiedName,
                             @NotBlank @Size(max = 100) String accountName,
                             @Size(max = 500) String accountRemark) { }
    public record CodeRequest(@NotBlank @Size(max = 20) String locale,
                              @NotBlank @Pattern(regexp = "(?i)sms|voice") String method,
                              boolean confirmed) { }
    public record VerifyRequest(@NotBlank @Pattern(regexp = "[0-9]{4,10}") String verificationCode) { }
}
