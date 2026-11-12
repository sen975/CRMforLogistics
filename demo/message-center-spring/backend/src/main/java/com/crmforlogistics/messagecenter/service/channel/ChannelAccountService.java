package com.crmforlogistics.messagecenter.service.channel;

import com.crmforlogistics.messagecenter.dto.request.CreateChannelAccountRequest;
import com.crmforlogistics.messagecenter.dto.response.ChannelAccountSummary;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 渠道账号的绑定、凭证与同步编排。
 *
 * <h2>渠道知识不在这个类里</h2>
 * 这个类只做「查表 → 按序校验 → 落库」，具体渠道知识（凭证字段、标识归一化、
 * 范围校验、同步实现）都在 {@link ChannelType} 实现里，按名字从
 * {@link ChannelTypeRegistry} 取。因此<b>新增一个渠道不需要修改本类</b> ——
 * 这正是这次改造要消掉的根因。
 *
 * <p>改造前它有四份「渠道清单」：{@code CREDENTIAL_KEYS} 凭证表、{@code switch}
 * 同步分派、{@code normalizeIdentifier} 的 if-else、以及写死的 chatapp 判断。
 * 现在这些都不在了，只剩查表。
 *
 * <p>同时删掉了一个 {@code sync(UUID)} 重载和 {@code ObjectProvider<WeComChatDataSyncService>}
 * 注入 —— 前者是 package-private 且无生产调用方的死代码（唯一的调用方是只测它的单元测试），
 * 后者是它存在的唯一理由。那也是本类唯一一处「渠道编排层 → {@code service.wecom}」依赖。
 * 企业微信的定时同步由 {@code WeComChatDataSyncRuntime} 直接驱动 {@code WeComChatDataSyncService}，
 * 不经过本类。
 */
@Service
public class ChannelAccountService {

    private final ChannelAccountMapper channelAccountMapper;
    private final ChannelTypeRegistry channelTypes;
    private final CredentialCipher credentialCipher;

    @Autowired
    ChannelAccountService(ChannelAccountMapper channelAccountMapper,
                          ChannelTypeRegistry channelTypes,
                          CredentialCipher credentialCipher) {
        this.channelAccountMapper = channelAccountMapper;
        this.channelTypes = channelTypes;
        this.credentialCipher = credentialCipher;
    }

    public java.util.List<ChannelAccountSummary> list(UUID ownerId) {
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
        ChannelType channelType = channelTypes.require(request.channelType());
        channelType.assertBindableFromSettings();
        String typeKey = channelType.key();
        if (channelAccountMapper.countActiveByOwnerAndChannel(ownerId, typeKey) > 0) {
            throw new ChannelAccountException("CHANNEL_ACCOUNT_ALREADY_EXISTS", HttpStatus.CONFLICT);
        }
        String identifier = channelType.normalizeIdentifier(request.accountIdentifier());
        Map<String, String> credentials = validatedCredentials(channelType, request.credentials());
        requireCompleteCredentials(channelType, credentials);
        channelType.assertScope(credentials);
        String encrypted = encrypt(credentials);
        ChannelAccountEntity existing = channelAccountMapper.findOwnedByIdentifier(ownerId, typeKey, identifier);
        ChannelAccountEntity entity = existing == null ? new ChannelAccountEntity() : existing;
        if (existing == null) {
            entity.setId(UUID.randomUUID());
        }
        entity.setOwnerUserId(ownerId);
        entity.setChannelType(typeKey);
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
        channelType.bindScope(entity);
        return ChannelAccountSummary.from(entity);
    }

    @Transactional
    public boolean unbind(UUID ownerId, UUID accountId) {
        requireOwned(ownerId, accountId);
        return channelAccountMapper.disableOwned(ownerId, accountId) == 1;
    }

    public Object sync(UUID ownerId, UUID id) throws Exception {
        ChannelAccountEntity entity = requireActive(ownerId, id);
        ChannelType channelType = channelTypes.require(entity.getChannelType());
        channelAccountMapper.updateSyncStatusOwned(ownerId, id, "syncing", null);
        try {
            Object result = channelType.syncAccount(ownerId, id);
            channelAccountMapper.updateSyncStatusOwned(ownerId, id, "success", Instant.now());
            return result;
        } catch (Exception e) {
            channelAccountMapper.updateSyncStatusOwned(ownerId, id, "failed", Instant.now());
            throw e;
        }
    }

    public Map<String, String> getCredentials(UUID ownerId, UUID id) {
        ChannelAccountEntity entity = requireOwned(ownerId, id);
        String encrypted = entity.getEncryptedConfig();
        if (encrypted == null || encrypted.isBlank() || "{}".equals(encrypted.trim())) {
            return Map.of();
        }
        try {
            Map<String, String> secrets = credentialCipher.decrypt(encrypted);
            Set<String> secretKeys = channelTypes.secretFields();
            Map<String, String> masked = new HashMap<>();
            secrets.forEach((k, v) -> masked.put(k, secretKeys.contains(k) ? "***" : v));
            return masked;
        } catch (CredentialCipher.CredentialDecryptionException e) {
            return Map.of();
        }
    }

    public Map<String, Object> updateCredentials(UUID ownerId, UUID id, Map<String, String> body)
            throws CredentialCipher.CredentialEncryptionException {
        ChannelAccountEntity entity = requireActive(ownerId, id);
        ChannelType channelType = channelTypes.require(entity.getChannelType());
        Map<String, String> validatedBody = validatedCredentials(channelType, body);

        Map<String, String> existing = new HashMap<>();
        String encrypted = entity.getEncryptedConfig();
        if (encrypted != null && !encrypted.isBlank() && !"{}".equals(encrypted.trim())) {
            try {
                existing.putAll(credentialCipher.decrypt(encrypted));
            } catch (CredentialCipher.CredentialDecryptionException e) {
                // start fresh if decryption fails
            }
        }
        Set<String> secretKeys = channelTypes.secretFields();
        validatedBody.forEach((key, value) -> {
            if (value == null || value.isBlank()) return;
            if (secretKeys.contains(key) && "***".equals(value.trim())) return;
            existing.put(key, value);
        });

        channelType.assertScope(existing);
        String newEncrypted = credentialCipher.encrypt(existing);
        channelAccountMapper.updateEncryptedConfigOwned(ownerId, id, newEncrypted);
        entity.setEncryptedConfig(newEncrypted);
        channelType.bindScope(entity);
        return Map.of("updated", existing.size());
    }

    private ChannelAccountEntity requireActive(UUID ownerId, UUID accountId) {
        ChannelAccountEntity entity = requireOwned(ownerId, accountId);
        if (!"active".equals(entity.getAuthStatus())) {
            throw new ChannelAccountException("CHANNEL_ACCOUNT_INACTIVE", HttpStatus.CONFLICT);
        }
        return entity;
    }

    private Map<String, String> validatedCredentials(ChannelType channelType, Map<String, String> credentials) {
        Map<String, String> values = credentials == null ? Map.of() : credentials;
        if (!channelType.credentialFields().containsAll(values.keySet())) {
            throw new ChannelAccountException("CHANNEL_ACCOUNT_INVALID_CREDENTIAL_FIELD", HttpStatus.BAD_REQUEST);
        }
        return values;
    }

    private void requireCompleteCredentials(ChannelType channelType, Map<String, String> credentials) {
        Set<String> missing = channelType.credentialFields().stream()
                .filter(key -> credentials.get(key) == null || credentials.get(key).isBlank())
                .collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            throw new ChannelAccountException("CHANNEL_ACCOUNT_INCOMPLETE_CREDENTIALS", HttpStatus.BAD_REQUEST);
        }
    }

    private String encrypt(Map<String, String> credentials) {
        try {
            return credentialCipher.encrypt(credentials);
        } catch (CredentialCipher.CredentialEncryptionException e) {
            throw new ChannelAccountException(e.code(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
