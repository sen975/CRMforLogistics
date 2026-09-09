package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppPhoneNumberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/whatsapp/phone-numbers")
public class WhatsAppPhoneNumberController {
    private final WhatsAppPhoneNumberService service;

    public WhatsAppPhoneNumberController(WhatsAppPhoneNumberService service) {
        this.service = service;
    }

    @PostMapping
    public WhatsAppPhoneNumberService.Status add(@Valid @RequestBody AddRequest request) {
        return service.start(SecurityUtil.currentUserId(), new WhatsAppPhoneNumberService.AddCommand(
                request.countryCode(), request.phoneNumber(), request.verifiedName()));
    }

    @PostMapping("/{phoneNumber}/verification-code")
    public WhatsAppPhoneNumberService.Status sendCode(@PathVariable String phoneNumber,
                                                       @Valid @RequestBody CodeRequest request) {
        return service.sendCode(SecurityUtil.currentUserId(),
                new WhatsAppPhoneNumberService.CodeCommand(phoneNumber, request.locale(), request.method()));
    }

    @PostMapping("/{phoneNumber}/verify")
    public WhatsAppPhoneNumberService.Status verify(@PathVariable String phoneNumber,
                                                     @Valid @RequestBody VerifyRequest request) {
        return service.verify(SecurityUtil.currentUserId(),
                new WhatsAppPhoneNumberService.VerifyCommand(phoneNumber, request.verificationCode()));
    }

    @GetMapping
    public java.util.List<WhatsAppPhoneNumberService.PhoneNumberStatus> list() {
        return service.list(SecurityUtil.currentUserId());
    }

    public record AddRequest(@NotBlank @Pattern(regexp = "[0-9]{1,4}") String countryCode,
                             @NotBlank @Pattern(regexp = "[+0-9 ()-]{6,24}") String phoneNumber,
                             @NotBlank @Size(max = 100) String verifiedName) { }
    public record CodeRequest(@NotBlank @Size(max = 20) String locale,
                              @NotBlank @Pattern(regexp = "(?i)sms|voice") String method) { }
    public record VerifyRequest(@NotBlank @Pattern(regexp = "[0-9]{4,10}") String verificationCode) { }
}
