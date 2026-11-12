package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.response.ChannelAccountSummary;
import com.crmforlogistics.messagecenter.dto.request.CreateChannelAccountRequest;
import com.crmforlogistics.messagecenter.channel.email.EmailException;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.channel.ChannelAccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/channel-accounts")
public class ChannelSettingsController {

    private final ChannelAccountService channelAccountService;

    public ChannelSettingsController(ChannelAccountService channelAccountService) {
        this.channelAccountService = channelAccountService;
    }

    @GetMapping
    public List<ChannelAccountSummary> list() {
        return channelAccountService.list(SecurityUtil.currentUserId());
    }

    @PostMapping
    public ResponseEntity<ChannelAccountSummary> create(
            @Valid @RequestBody CreateChannelAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(channelAccountService.createOrBind(SecurityUtil.currentUserId(), request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(channelAccountService.update(SecurityUtil.currentUserId(), id, body));
    }

    @PostMapping("/{id}/unbind")
    public ResponseEntity<Void> unbind(@PathVariable UUID id) {
        channelAccountService.unbind(SecurityUtil.currentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<?> sync(@PathVariable UUID id) throws Exception {
        try {
            return ResponseEntity.ok(channelAccountService.sync(SecurityUtil.currentUserId(), id));
        } catch (EmailException exception) {
            throw exception;
        }
    }

    @GetMapping("/{id}/credentials")
    public ResponseEntity<?> getCredentials(@PathVariable UUID id) {
        return ResponseEntity.ok(channelAccountService.getCredentials(SecurityUtil.currentUserId(), id));
    }

    @PutMapping("/{id}/credentials")
    public ResponseEntity<?> updateCredentials(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(channelAccountService.updateCredentials(
                    SecurityUtil.currentUserId(), id, body));
        } catch (CredentialCipher.CredentialEncryptionException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.code()));
        }
    }
}
