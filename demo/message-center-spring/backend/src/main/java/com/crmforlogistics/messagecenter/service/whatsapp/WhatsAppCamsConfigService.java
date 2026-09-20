package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WhatsAppCamsConfigService {
    private static final String PROVIDER = "ALIYUN_CAMS";

    private final AppConfig appConfig;
    private final WhatsAppProviderScopeMapper scopes;
    private final CredentialCipher cipher;
    private final WhatsAppOnboardingGateway gateway;

    public WhatsAppCamsConfigService(AppConfig appConfig, WhatsAppProviderScopeMapper scopes,
                                     CredentialCipher cipher, WhatsAppOnboardingGateway gateway) {
        this.appConfig = appConfig;
        this.scopes = scopes;
        this.cipher = cipher;
        this.gateway = gateway;
    }

    public List<ConfigView> list() {
        return scopes.findAllAdminScopes().stream().map(this::project).toList();
    }

    public ConfigView create(UUID actorId, ConfigRequest request) {
        validate(request, true);
        String custSpaceId = request.custSpaceId().trim();
        String encrypted = encrypt(values(request, null));
        String displayName = displayName(request, custSpaceId);
        if (scopes.insertAdminScope(custSpaceId, displayName, encrypted, scopeType(request), ownerOf(request)) != 1) {
            throw failure("WHATSAPP_CAMS_DUPLICATE", HttpStatus.CONFLICT);
        }
        return project(requireScope(scopes.findByProviderAndExternalScopeId(PROVIDER, custSpaceId)));
    }

    public ConfigView update(UUID actorId, UUID scopeId, ConfigRequest request) {
        validate(request, false);
        WhatsAppProviderScopeEntity existing = requireScope(scopeId);
        Map<String, String> retained = decrypt(existing);
        Map<String, String> next = values(request, retained);
        String displayName = displayName(request, existing.getExternalScopeId());
        String custSpaceId = request.custSpaceId() == null || request.custSpaceId().isBlank()
                ? existing.getExternalScopeId() : request.custSpaceId().trim();
        long expectedVersion = request.expectedVersion() == null ? version(existing) : request.expectedVersion();
        if (scopes.updateAdminScope(scopeId, custSpaceId, displayName, encrypt(next),
                scopeType(request), ownerOf(request), expectedVersion) != 1) {
            throw failure("WHATSAPP_CAMS_VERSION_CONFLICT", HttpStatus.CONFLICT);
        }
        return project(requireScope(scopeId));
    }

    public ConfigView block(UUID actorId, UUID scopeId, long expectedVersion) {
        requireScope(scopeId);
        if (scopes.blockAdminScope(scopeId, expectedVersion) != 1) {
            throw failure("WHATSAPP_CAMS_VERSION_CONFLICT", HttpStatus.CONFLICT);
        }
        return project(requireScope(scopeId));
    }

    public TestResult test(UUID scopeId) {
        WhatsAppProviderScopeEntity scope = requireReadyScope(scopeId);
        try {
            List<WhatsAppOnboardingGateway.ProviderPhone> phones = gateway.syncConfiguredPhoneNumbers(scope);
            scopes.touchTestResult(scopeId, "SUCCESS", null);
            return new TestResult(true, phones == null ? 0 : phones.size(), "CAMS 连接成功");
        } catch (RuntimeException error) {
            String code = error instanceof WhatsAppAuthorizationException authorization
                    ? authorization.code() : "WHATSAPP_CAMS_TEST_FAILED";
            scopes.touchTestResult(scopeId, "FAILED", code);
            throw failure(code, HttpStatus.BAD_GATEWAY);
        }
    }

    public WhatsAppProviderScopeEntity requireReadyScope(UUID scopeId) {
        WhatsAppProviderScopeEntity scope = requireScope(scopeId);
        if (!"READY".equalsIgnoreCase(scope.getStatus())) {
            throw failure("WHATSAPP_CAMS_SCOPE_BLOCKED", HttpStatus.CONFLICT);
        }
        return scope;
    }

    /** Compatibility projection for the pre-multi-scope settings page. */
    public ConfigView view() {
        WhatsAppProviderScopeEntity scope = currentScope();
        return scope == null ? ConfigView.unconfigured() : project(scope);
    }

    /** Compatibility save path; new callers must use create/update with a scope id. */
    public ConfigView save(UUID actorId, ConfigRequest request) {
        WhatsAppProviderScopeEntity current = currentScope();
        return current == null ? create(actorId, request) : update(actorId, current.getId(), request);
    }

    /** Compatibility test path for the old single-scope endpoint. */
    public TestResult test() {
        WhatsAppProviderScopeEntity scope = currentScope();
        if (scope == null) throw failure("WHATSAPP_CAMS_CONFIG_MISSING", HttpStatus.CONFLICT);
        return test(scope.getId());
    }

    /** Resolve the configured enterprise scope for legacy sync callers. */
    public WhatsAppProviderScopeEntity resolveScopeForSync() {
        WhatsAppProviderScopeEntity scope = currentScope();
        if (scope != null && hasEncryptedConfig(scope)) return scope;
        String custSpaceId = appConfig.custSpaceId();
        if (custSpaceId == null || custSpaceId.isBlank()) {
            throw failure("WHATSAPP_CAMS_CONFIG_MISSING", HttpStatus.CONFLICT);
        }
        String legacyConfig = gateway.encryptedProviderConfig();
        if (legacyConfig == null || legacyConfig.isBlank()) {
            throw failure("WHATSAPP_CAMS_CONFIG_MISSING", HttpStatus.CONFLICT);
        }
        scopes.upsertAdminConfigured(PROVIDER, custSpaceId.trim(), legacyConfig);
        return requireScope(scopes.findByProviderAndExternalScopeId(PROVIDER, custSpaceId.trim()));
    }

    public ConfigView project(WhatsAppProviderScopeEntity scope) {
        Map<String, String> values = decrypt(scope);
        return new ConfigView(scope.getId(), true, safe(scope.getDisplayName(), scope.getExternalScopeId()),
                scope.getExternalScopeId(), mask(values.get("accessKeyId")),
                values.getOrDefault("region", appConfig.camsRegion()),
                values.getOrDefault("endpoint", appConfig.camsEndpoint()), scope.getStatus(), version(scope),
                scope.getLastTestedAt(), scope.getLastTestStatus(), scope.getLastTestErrorCode(),
                scope.getLastSyncedAt(), scope.getLastSyncStatus(), scope.getLastSyncErrorCode(), "database",
                scopeTypeOf(scope), scope.getOwnerUserId());
    }

    private WhatsAppProviderScopeEntity currentScope() {
        String configuredId = appConfig.custSpaceId();
        if (configuredId != null && !configuredId.isBlank()) {
            WhatsAppProviderScopeEntity exact = scopes.findByProviderAndExternalScopeId(PROVIDER, configuredId.trim());
            if (exact != null) return exact;
        }
        return scopes.findEnterpriseApiScope(PROVIDER);
    }

    private WhatsAppProviderScopeEntity requireScope(UUID scopeId) {
        return requireScope(scopes.findAdminScopeById(scopeId));
    }

    private WhatsAppProviderScopeEntity requireScope(WhatsAppProviderScopeEntity scope) {
        if (scope == null || !PROVIDER.equals(scope.getProvider())) {
            throw failure("WHATSAPP_CAMS_SCOPE_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        return scope;
    }

    private static String scopeTypeOf(WhatsAppProviderScopeEntity scope) {
        return "EMPLOYEE_BUSINESS_APP".equalsIgnoreCase(scope.getScopeType())
                ? "EMPLOYEE_BUSINESS_APP" : "ENTERPRISE_API";
    }

    /** Defaults to the enterprise type so a request that omits it keeps creating what it always did. */
    private static String scopeType(ConfigRequest request) {
        return "EMPLOYEE_BUSINESS_APP".equalsIgnoreCase(request.scopeType())
                ? "EMPLOYEE_BUSINESS_APP" : "ENTERPRISE_API";
    }

    /**
     * A Business App space is the space one employee authorized; it must name that employee. An
     * enterprise space owns no person, and the database enforces exactly this pairing.
     */
    private static UUID ownerOf(ConfigRequest request) {
        return "EMPLOYEE_BUSINESS_APP".equals(scopeType(request)) ? request.ownerUserId() : null;
    }

    private Map<String, String> values(ConfigRequest request, Map<String, String> retained) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("accessKeyId", nonBlank(request.accessKeyId()) ? request.accessKeyId().trim() : value(retained, "accessKeyId"));
        values.put("accessKeySecret", nonBlank(request.accessKeySecret()) ? request.accessKeySecret().trim() : value(retained, "accessKeySecret"));
        values.put("region", nonBlank(request.region()) ? request.region().trim() : value(retained, "region"));
        values.put("endpoint", nonBlank(request.endpoint()) ? request.endpoint().trim() : value(retained, "endpoint"));
        return values;
    }

    private Map<String, String> decrypt(WhatsAppProviderScopeEntity scope) {
        if (!hasEncryptedConfig(scope)) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(cipher.decrypt(scope.getEncryptedConfig()));
        } catch (CredentialCipher.CredentialDecryptionException error) {
            throw failure("WHATSAPP_CAMS_CONFIG_DECRYPTION_FAILED", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String encrypt(Map<String, String> values) {
        try {
            return cipher.encrypt(values);
        } catch (CredentialCipher.CredentialEncryptionException error) {
            throw failure("WHATSAPP_CAMS_CONFIG_ENCRYPTION_FAILED", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private static void validate(ConfigRequest request, boolean create) {
        if (request == null || blank(request.custSpaceId()) || blank(request.region()) || blank(request.endpoint())
                || (create && (blank(request.accessKeyId()) || blank(request.accessKeySecret())))) {
            throw failure("WHATSAPP_CAMS_CONFIG_INVALID", HttpStatus.BAD_REQUEST);
        }
        if (request.expectedVersion() != null && request.expectedVersion() < 0) {
            throw failure("WHATSAPP_CAMS_CONFIG_INVALID", HttpStatus.BAD_REQUEST);
        }
        if ("EMPLOYEE_BUSINESS_APP".equals(scopeType(request)) && request.ownerUserId() == null) {
            throw failure("WHATSAPP_CAMS_OWNER_REQUIRED", HttpStatus.BAD_REQUEST);
        }
    }

    private static String displayName(ConfigRequest request, String fallback) {
        return safe(request.displayName(), fallback).trim();
    }

    private static String value(Map<String, String> values, String key) {
        return values == null ? "" : values.getOrDefault(key, "");
    }

    private static boolean hasEncryptedConfig(WhatsAppProviderScopeEntity scope) {
        return scope != null && scope.getEncryptedConfig() != null
                && !scope.getEncryptedConfig().isBlank() && !"{}".equals(scope.getEncryptedConfig());
    }

    private static long version(WhatsAppProviderScopeEntity scope) {
        return scope.getVersion() == null ? 0L : scope.getVersion();
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private static boolean blank(String value) { return value == null || value.isBlank() || value.length() > 512; }
    private static String mask(String value) {
        if (value == null || value.isBlank()) return "";
        return value.length() <= 4 ? "****" : value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    private static WhatsAppAuthorizationException failure(String code, HttpStatus status) {
        return new WhatsAppAuthorizationException(code, status);
    }

    public record ConfigRequest(String displayName, String custSpaceId, String accessKeyId,
                                String accessKeySecret, String region, String endpoint,
                                Long expectedVersion, String scopeType, UUID ownerUserId) {
        /** Fixed mask: no prefix, suffix or length of the secret is recoverable from it. */
        private static final String REDACTED_SECRET = "[REDACTED]";

        public ConfigRequest(String custSpaceId, String accessKeyId, String accessKeySecret,
                              String region, String endpoint) {
            this(custSpaceId, custSpaceId, accessKeyId, accessKeySecret, region, endpoint, null, null, null);
        }

        /** A record's generated toString() would echo accessKeySecret; keep it out of logs. */
        @Override
        public String toString() {
            return "ConfigRequest[displayName=" + displayName
                    + ", custSpaceId=" + custSpaceId
                    + ", accessKeyId=" + accessKeyId
                    + ", accessKeySecret=" + REDACTED_SECRET
                    + ", region=" + region
                    + ", endpoint=" + endpoint
                    + ", expectedVersion=" + expectedVersion
                    + ", scopeType=" + scopeType
                    + ", ownerUserId=" + ownerUserId + "]";
        }
    }

    public record ConfigView(UUID scopeId, boolean configured, String displayName, String custSpaceId,
                             String accessKeyIdMasked, String region, String endpoint, String status,
                             long version, Instant lastTestedAt, String lastTestStatus,
                             String lastTestErrorCode, Instant lastSyncedAt, String lastSyncStatus,
                             String lastSyncErrorCode, String source, String scopeType, UUID ownerUserId) {
        public static ConfigView unconfigured() {
            return new ConfigView(null, false, "", "", "", "", "", "UNCONFIGURED", 0L,
                    null, null, null, null, null, null, "environment", null, null);
        }
    }

    public record TestResult(boolean success, int phoneCount, String message) { }
}
