package com.crmforlogistics.messagecenter.service.channel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppMessageSyncService;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppTemplateSyncService;
import com.crmforlogistics.messagecenter.channel.email.EmailSyncService;
import com.crmforlogistics.messagecenter.dto.response.ChannelAccountSummary;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.wecom.WeComChatDataSyncService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ChannelAccountService {

    private static final Set<String> SECRET_KEYS = Set.of(
            "smtpPassword", "imapPassword", "accessKeySecret");

    private final ChannelAccountMapper channelAccountMapper;
    private final ChatAppMessageSyncService chatAppMessageSyncService;
    private final ChatAppTemplateSyncService chatAppTemplateSyncService;
    private final EmailSyncService emailSyncService;
    private final CredentialCipher credentialCipher;
    private final WeComChatDataSyncService weComSyncService;

    @Autowired
    public ChannelAccountService(ChannelAccountMapper channelAccountMapper,
                                 ChatAppMessageSyncService chatAppMessageSyncService,
                                 ChatAppTemplateSyncService chatAppTemplateSyncService,
                                 EmailSyncService emailSyncService,
                                 CredentialCipher credentialCipher,
                                 ObjectProvider<WeComChatDataSyncService> weComSyncProvider) {
        this(channelAccountMapper, chatAppMessageSyncService, chatAppTemplateSyncService,
                emailSyncService, credentialCipher, weComSyncProvider.getIfAvailable());
    }

    ChannelAccountService(ChannelAccountMapper channelAccountMapper,
                          ChatAppMessageSyncService chatAppMessageSyncService,
                          ChatAppTemplateSyncService chatAppTemplateSyncService,
                          EmailSyncService emailSyncService,
                          CredentialCipher credentialCipher) {
        this(channelAccountMapper, chatAppMessageSyncService, chatAppTemplateSyncService,
                emailSyncService, credentialCipher, (WeComChatDataSyncService) null);
    }

    ChannelAccountService(ChannelAccountMapper channelAccountMapper,
                          ChatAppMessageSyncService chatAppMessageSyncService,
                          ChatAppTemplateSyncService chatAppTemplateSyncService,
                          EmailSyncService emailSyncService,
                          CredentialCipher credentialCipher,
                          WeComChatDataSyncService weComSyncService) {
        this.channelAccountMapper = channelAccountMapper;
        this.chatAppMessageSyncService = chatAppMessageSyncService;
        this.chatAppTemplateSyncService = chatAppTemplateSyncService;
        this.emailSyncService = emailSyncService;
        this.credentialCipher = credentialCipher;
        this.weComSyncService = weComSyncService;
    }

    public List<ChannelAccountSummary> list() {
        return channelAccountMapper.selectList(
                new LambdaQueryWrapper<ChannelAccountEntity>()
                        .isNull(ChannelAccountEntity::getDeletedAt)
                        .orderByAsc(ChannelAccountEntity::getCreatedAt)
        ).stream().map(ChannelAccountSummary::from).toList();
    }

    public ChannelAccountSummary update(UUID id, Map<String, String> body) {
        ChannelAccountEntity entity = channelAccountMapper.selectById(id);
        if (entity == null) return null;
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
        return ChannelAccountSummary.from(entity);
    }

    public Object sync(UUID id) throws Exception {
        ChannelAccountEntity entity = channelAccountMapper.selectById(id);
        if (entity == null) return null;
        channelAccountMapper.updateSyncStatus(id, "syncing", null);
        try {
            Object result = switch (entity.getChannelType().toLowerCase()) {
                case "email" -> emailSyncService.receiveLatest();
                case "wecom" -> requireWeComSync().syncSystem();
                case "chatapp", "whatsapp" -> {
                    var msgResult = chatAppMessageSyncService.runAccount(id);
                    var tplResult = chatAppTemplateSyncService.runAccount(id);
                    yield Map.of(
                            "messageSync", msgResult,
                            "templateSync", tplResult
                    );
                }
                default -> throw new IllegalArgumentException("Unsupported channel type: " + entity.getChannelType());
            };
            channelAccountMapper.updateSyncStatus(id, "success", Instant.now());
            return result;
        } catch (Exception e) {
            channelAccountMapper.updateSyncStatus(id, "failed", Instant.now());
            throw e;
        }
    }

    private WeComChatDataSyncService requireWeComSync() {
        if (weComSyncService == null) {
            throw new IllegalStateException("WECOM_CHATDATA_NOT_CONFIGURED");
        }
        return weComSyncService;
    }

    public Map<String, String> getCredentials(UUID id) {
        ChannelAccountEntity entity = channelAccountMapper.selectById(id);
        if (entity == null) return null;
        String encrypted = entity.getEncryptedConfig();
        if (encrypted == null || encrypted.isBlank() || "{}".equals(encrypted.trim())) {
            return Map.of();
        }
        try {
            Map<String, String> secrets = credentialCipher.decrypt(encrypted);
            Map<String, String> masked = new HashMap<>();
            secrets.forEach((k, v) -> masked.put(k, SECRET_KEYS.contains(k) ? "***" : v));
            return masked;
        } catch (CredentialCipher.CredentialDecryptionException e) {
            return Map.of();
        }
    }

    public Map<String, Object> updateCredentials(UUID id, Map<String, String> body)
            throws CredentialCipher.CredentialEncryptionException {
        ChannelAccountEntity entity = channelAccountMapper.selectById(id);
        if (entity == null) return null;

        Map<String, String> existing = new HashMap<>();
        String encrypted = entity.getEncryptedConfig();
        if (encrypted != null && !encrypted.isBlank() && !"{}".equals(encrypted.trim())) {
            try {
                existing.putAll(credentialCipher.decrypt(encrypted));
            } catch (CredentialCipher.CredentialDecryptionException e) {
                // start fresh if decryption fails
            }
        }
        body.forEach((key, value) -> {
            if (value == null || value.isBlank()) return;
            if (SECRET_KEYS.contains(key) && "***".equals(value.trim())) return;
            existing.put(key, value);
        });

        String newEncrypted = credentialCipher.encrypt(existing);
        channelAccountMapper.updateEncryptedConfig(id, newEncrypted);
        return Map.of("updated", existing.size());
    }

    private static boolean isFixedChatAppAccount(ChannelAccountEntity entity) {
        String channelType = trimmed(entity.getChannelType());
        return "chatapp".equalsIgnoreCase(channelType) || "whatsapp".equalsIgnoreCase(channelType);
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
