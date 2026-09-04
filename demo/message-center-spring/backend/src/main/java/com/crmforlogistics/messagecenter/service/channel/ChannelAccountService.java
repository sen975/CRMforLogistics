package com.crmforlogistics.messagecenter.service.channel;

import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppMessageSyncService;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppTemplateSyncService;
import com.crmforlogistics.messagecenter.channel.email.EmailSyncService;
import com.crmforlogistics.messagecenter.dto.request.CreateChannelAccountRequest;
import com.crmforlogistics.messagecenter.dto.response.ChannelAccountSummary;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.wecom.WeComChatDataSyncService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

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
    private static final Map<String, Set<String>> CREDENTIAL_KEYS = Map.of(
            "email", Set.of("smtpHost", "smtpPort", "smtpSsl", "smtpUser", "smtpPassword",
                    "imapHost", "imapPort", "imapSsl", "imapUser", "imapPassword", "provider", "mailFrom"),
            "chatapp", Set.of("accessKeyId", "accessKeySecret", "region", "endpoint",
                    "custSpaceId", "chatappFrom"));

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

    public List<ChannelAccountSummary> list(UUID ownerId) {
        return channelAccountMapper.findAllByOwner(ownerId).stream()
                .map(ChannelAccountSummary::from).toList();
    }

    public ChannelAccountEntity requireOwned(UUID ownerId, UUID accountId) {
        ChannelAccountEntity entity = channelAccountMapper.findByIdAndOwner(accountId, ownerId);
        if (entity == null) {
            throw new ChannelAccountException("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        return entity;
    }

    public ChannelAccountSummary update(UUID ownerId, UUID id, Map<String, String> body) {
        ChannelAccountEntity entity = requireOwned(ownerId, id);
        String name = body.get("name");
        String identifier = body.get("accountIdentifier");
        boolean hasName = name != null && !name.isBlank();
        boolean hasIdentifier = identifier != null && !identifier.isBlank();

        if (hasIdentifier) {
            if (!identifier.trim().equals(trimmed(entity.getAccountIdentifier()))) {
                throw new ChannelAccountException("CHANNEL_ACCOUNT_IDENTIFIER_IMMUTABLE", HttpStatus.CONFLICT);
            }
            hasIdentifier = false;
        }

        if (hasName && hasIdentifier) {
            channelAccountMapper.updateNameAndIdentifierOwned(ownerId, id, name.trim(), identifier.trim());
            entity.setName(name.trim());
            entity.setAccountIdentifier(identifier.trim());
            entity.setAccountIdentifierNormalized(identifier.trim());
        } else if (hasName) {
            channelAccountMapper.updateNameOwned(ownerId, id, name.trim());
            entity.setName(name.trim());
        } else if (hasIdentifier) {
            channelAccountMapper.updateAccountIdentifierOwned(ownerId, id, identifier.trim());
            entity.setAccountIdentifier(identifier.trim());
            entity.setAccountIdentifierNormalized(identifier.trim());
        }
        return ChannelAccountSummary.from(entity);
    }

    @Transactional
    public ChannelAccountSummary createOrBind(UUID ownerId, CreateChannelAccountRequest request) {
        String channelType = normalizeChannelType(request.channelType());
        if (channelAccountMapper.countActiveByOwnerAndChannel(ownerId, channelType) > 0) {
            throw new ChannelAccountException("CHANNEL_ACCOUNT_ALREADY_EXISTS", HttpStatus.CONFLICT);
        }
        String identifier = normalizeIdentifier(channelType, request.accountIdentifier());
        Map<String, String> credentials = validatedCredentials(channelType, request.credentials());
        String encrypted = encrypt(credentials);
        ChannelAccountEntity existing = channelAccountMapper.findOwnedByIdentifier(ownerId, channelType, identifier);
        ChannelAccountEntity entity = existing == null ? new ChannelAccountEntity() : existing;
        if (existing == null) {
            entity.setId(UUID.randomUUID());
        }
        entity.setOwnerUserId(ownerId);
        entity.setChannelType(channelType);
        entity.setName(request.name().trim());
        entity.setAccountIdentifier(request.accountIdentifier().trim());
        entity.setAccountIdentifierNormalized(identifier);
        entity.setAuthStatus("active");
        entity.setSyncStatus("idle");
        entity.setEncryptedConfig(encrypted);
        if (existing == null) {
            channelAccountMapper.insertOwned(entity, ownerId);
        } else if ("disabled".equals(existing.getAuthStatus())) {
            channelAccountMapper.rebindOwned(entity, ownerId);
        } else {
            throw new ChannelAccountException("CHANNEL_ACCOUNT_ALREADY_EXISTS", HttpStatus.CONFLICT);
        }
        return ChannelAccountSummary.from(entity);
    }

    @Transactional
    public boolean unbind(UUID ownerId, UUID accountId) {
        requireOwned(ownerId, accountId);
        return channelAccountMapper.disableOwned(ownerId, accountId) == 1;
    }

    public Object sync(UUID ownerId, UUID id) throws Exception {
        ChannelAccountEntity entity = requireActive(ownerId, id);
        channelAccountMapper.updateSyncStatusOwned(ownerId, id, "syncing", null);
        try {
            Object result = switch (entity.getChannelType().toLowerCase()) {
                case "email" -> emailSyncService.receiveLatest();
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
            channelAccountMapper.updateSyncStatusOwned(ownerId, id, "success", Instant.now());
            return result;
        } catch (Exception e) {
            channelAccountMapper.updateSyncStatusOwned(ownerId, id, "failed", Instant.now());
            throw e;
        }
    }

    Object sync(UUID id) throws Exception {
        ChannelAccountEntity entity = channelAccountMapper.selectById(id);
        if (entity == null || !"wecom".equalsIgnoreCase(entity.getChannelType())) {
            return null;
        }
        channelAccountMapper.updateSyncStatus(id, "syncing", null);
        try {
            Object result = requireWeComSync().syncSystem();
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

    public Map<String, String> getCredentials(UUID ownerId, UUID id) {
        ChannelAccountEntity entity = requireOwned(ownerId, id);
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

    public Map<String, Object> updateCredentials(UUID ownerId, UUID id, Map<String, String> body)
            throws CredentialCipher.CredentialEncryptionException {
        ChannelAccountEntity entity = requireActive(ownerId, id);
        Map<String, String> validatedBody = validatedCredentials(entity.getChannelType(), body);

        Map<String, String> existing = new HashMap<>();
        String encrypted = entity.getEncryptedConfig();
        if (encrypted != null && !encrypted.isBlank() && !"{}".equals(encrypted.trim())) {
            try {
                existing.putAll(credentialCipher.decrypt(encrypted));
            } catch (CredentialCipher.CredentialDecryptionException e) {
                // start fresh if decryption fails
            }
        }
        validatedBody.forEach((key, value) -> {
            if (value == null || value.isBlank()) return;
            if (SECRET_KEYS.contains(key) && "***".equals(value.trim())) return;
            existing.put(key, value);
        });

        String newEncrypted = credentialCipher.encrypt(existing);
        channelAccountMapper.updateEncryptedConfigOwned(ownerId, id, newEncrypted);
        return Map.of("updated", existing.size());
    }

    private ChannelAccountEntity requireActive(UUID ownerId, UUID accountId) {
        ChannelAccountEntity entity = requireOwned(ownerId, accountId);
        if (!"active".equals(entity.getAuthStatus())) {
            throw new ChannelAccountException("CHANNEL_ACCOUNT_INACTIVE", HttpStatus.CONFLICT);
        }
        return entity;
    }

    private Map<String, String> validatedCredentials(String rawChannelType, Map<String, String> credentials) {
        String channelType = normalizeChannelType(rawChannelType);
        Map<String, String> values = credentials == null ? Map.of() : credentials;
        Set<String> allowed = CREDENTIAL_KEYS.get(channelType);
        if (!allowed.containsAll(values.keySet())) {
            throw new ChannelAccountException("CHANNEL_ACCOUNT_INVALID_CREDENTIAL_FIELD", HttpStatus.BAD_REQUEST);
        }
        return values;
    }

    private String encrypt(Map<String, String> credentials) {
        try {
            return credentialCipher.encrypt(credentials);
        } catch (CredentialCipher.CredentialEncryptionException e) {
            throw new ChannelAccountException(e.code(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static String normalizeChannelType(String channelType) {
        String normalized = trimmed(channelType).toLowerCase();
        if ("whatsapp".equals(normalized)) normalized = "chatapp";
        if (!CREDENTIAL_KEYS.containsKey(normalized)) {
            throw new ChannelAccountException("CHANNEL_ACCOUNT_TYPE_UNSUPPORTED", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private static String normalizeIdentifier(String channelType, String identifier) {
        String value = trimmed(identifier);
        if ("email".equals(channelType)) return value.toLowerCase();
        return value.replaceAll("[\\s()\\-]", "");
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
