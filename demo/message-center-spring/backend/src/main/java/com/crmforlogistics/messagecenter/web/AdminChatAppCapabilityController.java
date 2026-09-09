package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppCapabilityProbe;
import com.crmforlogistics.messagecenter.dto.response.ChatAppCapabilityReport;
import com.crmforlogistics.messagecenter.service.channel.ChannelAccountException;
import com.crmforlogistics.messagecenter.service.channel.ChatAppCapabilityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/chatapp/capabilities")
public class AdminChatAppCapabilityController {
    private static final String CONFIRMATION = "CONFIRM_CHATAPP_PROVISIONING_TEST";

    private final ChatAppCapabilityService service;

    public AdminChatAppCapabilityController(ChatAppCapabilityService service) {
        this.service = service;
    }

    @GetMapping
    public ChatAppCapabilityReport latest() {
        return service.latest();
    }

    @PostMapping("/read-only")
    public ChatAppCapabilityReport readOnly(@Valid @RequestBody AccountRequest request) {
        return service.probeReadOnly(request.accountId());
    }

    @PostMapping("/add-number")
    public ChatAppCapabilityReport addNumber(
            @RequestHeader(value = "X-ChatApp-Provisioning-Confirmation", required = false) String confirmation,
            @Valid @RequestBody AddNumberRequest request) {
        requireConfirmation(confirmation);
        return service.addNumber(request.accountId(), new ChatAppCapabilityProbe.AddNumberCommand(
                request.countryCode(), request.phoneNumber(), request.verifiedName()));
    }

    @PostMapping("/send-code")
    public ChatAppCapabilityReport sendCode(
            @RequestHeader(value = "X-ChatApp-Provisioning-Confirmation", required = false) String confirmation,
            @Valid @RequestBody SendCodeRequest request) {
        requireConfirmation(confirmation);
        return service.sendCode(request.accountId(), new ChatAppCapabilityProbe.SendCodeCommand(
                request.phoneNumber(), request.locale(), request.method()));
    }

    @PostMapping("/verify")
    public ChatAppCapabilityReport verify(
            @RequestHeader(value = "X-ChatApp-Provisioning-Confirmation", required = false) String confirmation,
            @Valid @RequestBody VerifyRequest request) {
        requireConfirmation(confirmation);
        return service.verify(request.accountId(), new ChatAppCapabilityProbe.VerifyCommand(
                request.phoneNumber(), request.verificationCode()));
    }

    @PostMapping("/migration-review")
    public ChatAppCapabilityReport migrationReview() {
        return service.migrationReview();
    }

    private static void requireConfirmation(String confirmation) {
        if (!CONFIRMATION.equals(confirmation)) {
            throw new ChannelAccountException("CHATAPP_PROVISIONING_CONFIRMATION_REQUIRED", HttpStatus.CONFLICT);
        }
    }

    public record AccountRequest(@NotNull UUID accountId) { }
    public record AddNumberRequest(@NotNull UUID accountId,
                                   @NotBlank @Pattern(regexp = "[0-9]{1,4}") String countryCode,
                                   @NotBlank @Pattern(regexp = "[0-9]{6,20}") String phoneNumber,
                                   @NotBlank @Size(max = 100) String verifiedName) { }
    public record SendCodeRequest(@NotNull UUID accountId,
                                  @NotBlank @Pattern(regexp = "[0-9]{6,20}") String phoneNumber,
                                  @NotBlank @Size(max = 20) String locale,
                                  @NotBlank @Pattern(regexp = "(?i)sms|voice") String method) { }
    public record VerifyRequest(@NotNull UUID accountId,
                                @NotBlank @Pattern(regexp = "[0-9]{6,20}") String phoneNumber,
                                @NotBlank @Pattern(regexp = "[0-9]{4,10}") String verificationCode) { }
}
