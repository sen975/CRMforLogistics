package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.response.ChannelAccountSummary;
import com.crmforlogistics.messagecenter.channel.email.EmailException;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.service.channel.ChannelAccountService;
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
        return channelAccountService.list();
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        ChannelAccountSummary summary = channelAccountService.update(id, body);
        if (summary == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(summary);
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<?> sync(@PathVariable UUID id) {
        try {
            Object result = channelAccountService.sync(id);
            if (result == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(result);
        } catch (EmailException exception) {
            throw exception;
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/credentials")
    public ResponseEntity<?> getCredentials(@PathVariable UUID id) {
        Map<String, String> credentials = channelAccountService.getCredentials(id);
        if (credentials == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(credentials);
    }

    @PutMapping("/{id}/credentials")
    public ResponseEntity<?> updateCredentials(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        try {
            Map<String, Object> result = channelAccountService.updateCredentials(id, body);
            if (result == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(result);
        } catch (CredentialCipher.CredentialEncryptionException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.code()));
        }
    }
}
