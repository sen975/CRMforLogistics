package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppMessageSyncService;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppTemplateSyncService;
import com.crmforlogistics.messagecenter.channel.email.EmailSyncService;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/channel-accounts")
public class ChannelSettingsController {

    private final ChannelAccountMapper channelAccountMapper;
    private final ChatAppMessageSyncService chatAppMessageSyncService;
    private final ChatAppTemplateSyncService chatAppTemplateSyncService;
    private final EmailSyncService emailSyncService;
    private final CredentialCipher credentialCipher;

    private static final Set<String> SECRET_KEYS = Set.of(
            "smtpPassword", "imapPassword", "accessKeySecret");

    public ChannelSettingsController(ChannelAccountMapper channelAccountMapper,
                                      ChatAppMessageSyncService chatAppMessageSyncService,
                                      ChatAppTemplateSyncService chatAppTemplateSyncService,
                                      EmailSyncService emailSyncService,
                                      CredentialCipher credentialCipher) {
        this.channelAccountMapper = channelAccountMapper;
        this.chatAppMessageSyncService = chatAppMessageSyncService;
        this.chatAppTemplateSyncService = chatAppTemplateSyncService;
        this.emailSyncService = emailSyncService;
        this.credentialCipher = credentialCipher;
    }

    @GetMapping
    public List<ChannelAccountSummary> list() {
        return channelAccountMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChannelAccountEntity>()
                        .isNull(ChannelAccountEntity::getDeletedAt)
                        .orderByAsc(ChannelAccountEntity::getCreatedAt)
        ).stream().map(ChannelAccountSummary::from).toList();
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        ChannelAccountEntity entity = channelAccountMapper.selectById(id);
        if (entity == null) return ResponseEntity.notFound().build();
        String name = body.get("name");
        String identifier = body.get("accountIdentifier");
        boolean hasName = name != null && !name.isBlank();
        boolean hasIdentifier = identifier != null && !identifier.isBlank();

        if (hasIdentifier && isFixedChatAppAccount(entity)) {
            if (!identifier.trim().equals(trimmed(entity.getAccountIdentifier()))) {
                throw new IllegalArgumentException("CHATAPP_ACCOUNT_IDENTIFIER_IMMUTABLE");
            }
            hasIdentifier = false;
        }

        if (hasName && hasIdentifier) {
            channelAccountMapper.updateNameAndIdentifier(id, name.trim(), identifier.trim());
            entity.setName(name.trim());
            entity.setAccountIdentifier(identifier.trim());
            entity.setAccountIdentifierNormalized(identifier.trim());
        } else if (hasName) {
            channelAccountMapper.updateName(id, name.trim());
            entity.setName(name.trim());
        } else if (hasIdentifier) {
            channelAccountMapper.updateAccountIdentifier(id, identifier.trim());
            entity.setAccountIdentifier(identifier.trim());
            entity.setAccountIdentifierNormalized(identifier.trim());
        }
        return ResponseEntity.ok(ChannelAccountSummary.from(entity));
    }

    private static boolean isFixedChatAppAccount(ChannelAccountEntity entity) {
        String channelType = trimmed(entity.getChannelType());
        return "chatapp".equalsIgnoreCase(channelType) || "whatsapp".equalsIgnoreCase(channelType);
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<?> sync(@PathVariable UUID id) {
        ChannelAccountEntity entity = channelAccountMapper.selectById(id);
        if (entity == null) return ResponseEntity.notFound().build();
        channelAccountMapper.updateSyncStatus(id, "syncing", null);
        try {
            Object result = switch (entity.getChannelType().toLowerCase()) {
                case "email" -> emailSyncService.receiveLatest();
                case "chatapp" -> {
                    var msgResult = chatAppMessageSyncService.runOnce();
                    var tplResult = chatAppTemplateSyncService.runOnce();
                    yield Map.of(
                            "messageSync", msgResult,
                            "templateSync", tplResult
                    );
                }
                default -> throw new IllegalArgumentException("Unsupported channel type: " + entity.getChannelType());
            };
            channelAccountMapper.updateSyncStatus(id, "success", java.time.Instant.now());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            channelAccountMapper.updateSyncStatus(id, "failed", java.time.Instant.now());
            return ResponseEntity.internalServerError().body(
                    Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/credentials")
    public ResponseEntity<?> getCredentials(@PathVariable UUID id) {
        ChannelAccountEntity entity = channelAccountMapper.selectById(id);
        if (entity == null) return ResponseEntity.notFound().build();
        String encrypted = entity.getEncryptedConfig();
        if (encrypted == null || encrypted.isBlank() || "{}".equals(encrypted.trim())) {
            return ResponseEntity.ok(Map.of());
        }
        try {
            Map<String, String> secrets = credentialCipher.decrypt(encrypted);
            Map<String, String> masked = new HashMap<>();
            secrets.forEach((k, v) -> masked.put(k, SECRET_KEYS.contains(k) ? "***" : v));
            return ResponseEntity.ok(masked);
        } catch (CredentialCipher.CredentialDecryptionException e) {
            return ResponseEntity.ok(Map.of());
        }
    }

    @PutMapping("/{id}/credentials")
    public ResponseEntity<?> updateCredentials(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        ChannelAccountEntity entity = channelAccountMapper.selectById(id);
        if (entity == null) return ResponseEntity.notFound().build();

        Map<String, String> existing = new HashMap<>();
        String encrypted = entity.getEncryptedConfig();
        if (encrypted != null && !encrypted.isBlank() && !"{}".equals(encrypted.trim())) {
            try {
                existing.putAll(credentialCipher.decrypt(encrypted));
            } catch (CredentialCipher.CredentialDecryptionException e) {
                // start fresh if decryption fails
            }
        }
        existing.putAll(body);
        existing.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank());

        try {
            String newEncrypted = credentialCipher.encrypt(existing);
            channelAccountMapper.updateEncryptedConfig(id, newEncrypted);
            return ResponseEntity.ok(Map.of("updated", existing.size()));
        } catch (CredentialCipher.CredentialEncryptionException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.code()));
        }
    }

    public record ChannelAccountSummary(
            UUID id, String channelType, String name, String accountIdentifier,
            String authStatus, String syncStatus, java.time.Instant lastSyncedAt,
            java.time.Instant createdAt) {
        static ChannelAccountSummary from(ChannelAccountEntity e) {
            return new ChannelAccountSummary(e.getId(), e.getChannelType(), e.getName(),
                    e.getAccountIdentifier(), e.getAuthStatus(), e.getSyncStatus(),
                    e.getLastSyncedAt(), e.getCreatedAt());
        }
    }
}
