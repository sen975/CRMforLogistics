package com.crmforlogistics.messagecenter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcChannelAccountRepository implements ChannelAccountRepository {
    private final Database database;
    private final CredentialCipher credentialCipher;
    private final AuditService audit;

    public JdbcChannelAccountRepository(Database database, CredentialCipher credentialCipher) {
        this.database = Objects.requireNonNull(database, "database");
        this.credentialCipher = Objects.requireNonNull(credentialCipher, "credentialCipher");
        this.audit = new AuditService(database);
    }

    @Override
    public ChannelAccount create(ChannelAccountDraft draft, String encryptedConfig) throws Exception {
        ValidatedDraft valid = validateDraft(draft);
        String config = validateEncryptedConfig(encryptedConfig);
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into channel_accounts
                        (channel_type, name, account_identifier, account_identifier_normalized,
                         encrypted_config)
                    values (?, ?, ?, ?, ?::jsonb)
                    returning id, channel_type, name, account_identifier, auth_status, sync_status,
                              encrypted_config::text
                    """)) {
                statement.setString(1, valid.channelType());
                statement.setString(2, valid.name());
                statement.setString(3, valid.accountIdentifier());
                statement.setString(4, normalizedIdentifier(valid.accountIdentifier()));
                statement.setString(5, config);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    ChannelAccount account = account(result);
                    audit.record(connection, null, "channel_account.create", "channel_account", account.id(),
                            Map.of(), channelAuditSummary(account), "success");
                    return account;
                }
            }
        });
    }

    @Override
    public ChannelAccount update(UUID id, ChannelAccountPatch patch, String encryptedConfig) throws Exception {
        Objects.requireNonNull(id, "id");
        ValidatedPatch valid = validatePatch(patch);
        String config = validateEncryptedConfig(encryptedConfig);
        return database.transaction(connection -> {
            Map<String, Object> before = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    select name,account_identifier,auth_status,encrypted_config::text
                    from channel_accounts where id=? and deleted_at is null for update
                    """)) {
                statement.setObject(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new ChannelAccountNotFoundException();
                    before.put("name", result.getString(1));
                    before.put("accountIdentifier", result.getString(2));
                    before.put("authStatus", result.getString(3));
                    before.put("encryptedConfig", result.getString(4));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    update channel_accounts
                    set name = ?, account_identifier = ?, account_identifier_normalized = ?,
                        auth_status = ?, encrypted_config = ?::jsonb,
                        updated_at = now(), version = version + 1
                    where id = ? and deleted_at is null
                    returning id, channel_type, name, account_identifier, auth_status, sync_status,
                              encrypted_config::text
                    """)) {
                statement.setString(1, valid.name());
                statement.setString(2, valid.accountIdentifier());
                statement.setString(3, normalizedIdentifier(valid.accountIdentifier()));
                statement.setString(4, valid.authStatus());
                statement.setString(5, config);
                statement.setObject(6, id);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new ChannelAccountNotFoundException();
                    }
                    ChannelAccount account = account(result);
                    audit.record(connection, null, "channel_account.update", "channel_account", account.id(),
                            before, channelAuditSummary(account), "success");
                    return account;
                }
            }
        });
    }

    @Override
    public Optional<ChannelAccount> find(UUID id) throws Exception {
        Objects.requireNonNull(id, "id");
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select id, channel_type, name, account_identifier, auth_status, sync_status,
                           encrypted_config::text
                    from channel_accounts
                    where id = ? and deleted_at is null
                    """)) {
                statement.setObject(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(account(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Optional<ChannelAccount> findActive(UUID id) throws Exception {
        Objects.requireNonNull(id, "id");
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select id, channel_type, name, account_identifier, auth_status, sync_status,
                           encrypted_config::text
                    from channel_accounts
                    where id = ? and auth_status = 'active' and deleted_at is null
                    """)) {
                statement.setObject(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(account(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<ChannelAccount> list() throws Exception {
        return database.read(connection -> {
            List<ChannelAccount> accounts = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    select id, channel_type, name, account_identifier, auth_status, sync_status,
                           encrypted_config::text
                    from channel_accounts
                    where deleted_at is null
                    order by lower(name), id
                    """)) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        accounts.add(account(result));
                    }
                }
            }
            return List.copyOf(accounts);
        });
    }

    @Override
    public SyncCursor upsertCursor(UUID accountId, String type, String scope, String value,
                                   Instant timestamp) throws Exception {
        Objects.requireNonNull(accountId, "accountId");
        String cursorType = required(type, "cursor type", 50);
        String scopeKey = required(scope, "cursor scope", 255);
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(timestamp, "timestamp");
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into channel_sync_cursors
                        (channel_account_id, cursor_type, scope_key, cursor_value, cursor_timestamp)
                    values (?, ?, ?, ?, ?)
                    on conflict (channel_account_id, cursor_type, scope_key) do update
                    set cursor_value = excluded.cursor_value,
                        cursor_timestamp = excluded.cursor_timestamp,
                        updated_at = now(), version = channel_sync_cursors.version + 1
                    returning channel_account_id, cursor_type, scope_key, cursor_value, cursor_timestamp
                    """)) {
                statement.setObject(1, accountId);
                statement.setString(2, cursorType);
                statement.setString(3, scopeKey);
                statement.setString(4, value);
                statement.setTimestamp(5, Timestamp.from(timestamp));
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return new SyncCursor(
                            result.getObject("channel_account_id", UUID.class),
                            result.getString("cursor_type"),
                            result.getString("scope_key"),
                            result.getString("cursor_value"),
                            result.getTimestamp("cursor_timestamp").toInstant());
                }
            }
        });
    }

    @Override
    public void upsertTemplates(UUID accountId, List<MessageTemplate> templates) throws Exception {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(templates, "templates");
        if (templates.isEmpty()) {
            return;
        }
        List<MessageTemplate> validated = templates.stream().map(this::validateTemplate).toList();
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into message_templates
                        (channel_account_id, provider_template_id, language_code, name, body, status,
                         provider_updated_at, last_synced_at)
                    values (?, ?, ?, ?, ?, ?, ?, now())
                    on conflict (channel_account_id, provider_template_id, language_code) do update
                    set name = excluded.name, body = excluded.body, status = excluded.status,
                        provider_updated_at = excluded.provider_updated_at,
                        last_synced_at = now(), updated_at = now()
                    """)) {
                for (MessageTemplate template : validated) {
                    statement.setObject(1, accountId);
                    statement.setString(2, template.providerTemplateId());
                    statement.setString(3, template.languageCode());
                    statement.setString(4, template.name());
                    statement.setString(5, template.body());
                    statement.setString(6, template.status());
                    if (template.providerUpdatedAt() == null) {
                        statement.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE);
                    } else {
                        statement.setTimestamp(7, Timestamp.from(template.providerUpdatedAt()));
                    }
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    private static ChannelAccount account(ResultSet result) throws Exception {
        return new ChannelAccount(
                result.getObject("id", UUID.class),
                result.getString("channel_type"),
                result.getString("name"),
                result.getString("account_identifier"),
                result.getString("auth_status"),
                result.getString("sync_status"),
                result.getString("encrypted_config"));
    }

    private static ValidatedDraft validateDraft(ChannelAccountDraft draft) {
        Objects.requireNonNull(draft, "draft");
        return new ValidatedDraft(
                normalizedType(draft.channelType()),
                required(draft.name(), "account name", 100),
                required(draft.accountIdentifier(), "account identifier", 255));
    }

    private static ValidatedPatch validatePatch(ChannelAccountPatch patch) {
        Objects.requireNonNull(patch, "patch");
        String authStatus = required(patch.authStatus(), "authentication status", 30);
        if (!List.of("unbound", "active", "expired", "failed", "disabled").contains(authStatus)) {
            throw new IllegalArgumentException("Unsupported authentication status");
        }
        return new ValidatedPatch(
                required(patch.name(), "account name", 100),
                required(patch.accountIdentifier(), "account identifier", 255),
                authStatus);
    }

    private MessageTemplate validateTemplate(MessageTemplate template) {
        Objects.requireNonNull(template, "template");
        return new MessageTemplate(
                required(template.providerTemplateId(), "provider template ID", 255),
                required(template.languageCode(), "template language", 30),
                required(template.name(), "template name", 255),
                Objects.requireNonNull(template.body(), "template body"),
                required(template.status(), "template status", 30),
                template.providerUpdatedAt());
    }

    private String validateEncryptedConfig(String encryptedConfig) {
        Map<String, String> decrypted = null;
        try {
            CredentialCipher.requireEnvelope(encryptedConfig);
            decrypted = credentialCipher.decrypt(encryptedConfig);
            return encryptedConfig;
        } catch (CredentialCipher.CredentialDecryptionException exception) {
            throw new IllegalArgumentException("Encrypted channel configuration cannot be authenticated");
        } finally {
            if (decrypted != null) {
                decrypted.replaceAll((key, value) -> "");
                decrypted.clear();
            }
        }
    }

    private static Map<String, Object> channelAuditSummary(ChannelAccount account) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", account.name());
        summary.put("accountIdentifier", account.accountIdentifier());
        summary.put("authStatus", account.authStatus());
        summary.put("encryptedConfig", account.encryptedConfig());
        return summary;
    }

    private static String normalizedType(String channelType) {
        return required(channelType, "channel type", 30).toLowerCase(Locale.ROOT);
    }

    private static String normalizedIdentifier(String identifier) {
        return Normalizer.normalize(identifier, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String required(String value, String label, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maximumLength) {
            throw new IllegalArgumentException(label + " exceeds " + maximumLength + " characters");
        }
        return trimmed;
    }

    private record ValidatedDraft(String channelType, String name, String accountIdentifier) {}

    private record ValidatedPatch(String name, String accountIdentifier, String authStatus) {}

    public static final class ChannelAccountNotFoundException extends Exception {
        ChannelAccountNotFoundException() {
            super("Channel account not found");
        }
    }
}
