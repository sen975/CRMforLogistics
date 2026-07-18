package com.crmforlogistics.messagecenter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ChannelAccountRepository {
    ChannelAccount create(ChannelAccountDraft draft, String encryptedConfig) throws Exception;

    ChannelAccount update(UUID id, ChannelAccountPatch patch, String encryptedConfig) throws Exception;

    Optional<ChannelAccount> find(UUID id) throws Exception;

    Optional<ChannelAccount> findActive(UUID id) throws Exception;

    List<ChannelAccount> list() throws Exception;

    SyncCursor upsertCursor(UUID accountId, String type, String scope, String value,
                            Instant timestamp) throws Exception;

    void upsertTemplates(UUID accountId, List<MessageTemplate> templates) throws Exception;
}

interface CredentialValidator {
    ValidationResult validate(ChannelAccount account, Map<String, String> decryptedSecrets) throws Exception;
}

record ChannelAccount(UUID id, String channelType, String name, String accountIdentifier,
                      String authStatus, String syncStatus, String encryptedConfig) {}

record ChannelAccountDraft(String channelType, String name, String accountIdentifier) {}

record ChannelAccountPatch(String name, String accountIdentifier, String authStatus) {}

record SyncCursor(UUID accountId, String type, String scope, String value, Instant timestamp) {}

record MessageTemplate(String providerTemplateId, String languageCode, String name,
                       String body, String status, Instant providerUpdatedAt) {}

record ValidationResult(boolean valid, String code, String message) {}
