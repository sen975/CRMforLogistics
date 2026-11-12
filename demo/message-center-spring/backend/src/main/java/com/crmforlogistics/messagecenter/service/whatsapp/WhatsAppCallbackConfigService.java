package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.dto.response.WhatsAppCallbackConfigView;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppCallbackAuditEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppCallbackConfigEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.infrastructure.ContactPointUtil;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentials;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsResolver;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppCallbackAuditMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppCallbackConfigMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class WhatsAppCallbackConfigService {
    private static final Logger LOG = LoggerFactory.getLogger(WhatsAppCallbackConfigService.class);
    private static final String PROVIDER = "ALIYUN_CAMS";
    private static final String INGRESS_PATH = "/api/v1/webhooks/chatapp";

    private final WhatsAppProviderScopeMapper scopes;
    private final ChannelAccountMapper accounts;
    private final WhatsAppCallbackConfigMapper configs;
    private final WhatsAppCallbackAuditMapper audits;
    private final ChatAppAccountCredentialsResolver credentialsResolver;
    private final WhatsAppCallbackGateway gateway;

    public WhatsAppCallbackConfigService(WhatsAppProviderScopeMapper scopes,
                                         ChannelAccountMapper accounts,
                                         WhatsAppCallbackConfigMapper configs,
                                         WhatsAppCallbackAuditMapper audits,
                                         ChatAppAccountCredentialsResolver credentialsResolver,
                                         WhatsAppCallbackGateway gateway) {
        this.scopes = scopes;
        this.accounts = accounts;
        this.configs = configs;
        this.audits = audits;
        this.credentialsResolver = credentialsResolver;
        this.gateway = gateway;
    }

    public WhatsAppCallbackConfigView list(UUID scopeId) {
        WhatsAppProviderScopeEntity scope = readyScope(scopeId);
        List<WhatsAppCallbackConfigView.PhoneConfig> phones = accounts.findActiveByScope(scopeId).stream()
                .map(account -> phoneView(account, configs.findPhone(scopeId, account.getId())))
                .toList();
        return new WhatsAppCallbackConfigView(scope.getId(), phones,
                accountView(configs.findAccount(scopeId)), INGRESS_PATH);
    }

    public WhatsAppCallbackConfigView applyPhone(UUID actorId, UUID scopeId, UUID accountId,
                                                 PhoneRequest request) {
        validatePhone(request);
        WhatsAppProviderScopeEntity scope = readyScope(scopeId);
        ChannelAccountEntity account = requireAccount(scopeId, accountId);
        WhatsAppCallbackConfigEntity config = ensurePhoneConfig(scopeId, accountId);
        long expectedVersion = requireVersion(config.getVersion(), request.expectedVersion());
        UUID applyToken = UUID.randomUUID();
        if (configs.claimApply(config.getId(), expectedVersion, applyToken) != 1) {
            throw failure("WHATSAPP_CALLBACK_APPLY_IN_PROGRESS", HttpStatus.CONFLICT);
        }
        ChatAppAccountCredentials credentials;
        WhatsAppCallbackGateway.ProviderApplyResult result;
        try {
            credentials = credentialsResolver.resolveSpace(scope);
            result = gateway.updatePhone(
                    new WhatsAppCallbackGateway.PhoneUpdate(
                            scope.getExternalScopeId(), normalizedPhone(account), request.upCallbackUrl(),
                    request.statusCallbackUrl(), flag(request.httpFlag(), "Y"),
                            flag(request.queueFlag(), "N")), credentials);
        } catch (WhatsAppAuthorizationException error) {
            String status = isSubmissionUnknown(error) ? "SUBMISSION_UNKNOWN" : "FAILED";
            persistProviderFailure(config.getId(), expectedVersion + 1, applyToken, status, null, error.code());
            auditSafely(actorId, scopeId, accountId, "PHONE", "APPLY", request.upCallbackUrl(), expectedVersion,
                    status, error.code(), null);
            throw error;
        } catch (RuntimeException error) {
            persistProviderFailure(config.getId(), expectedVersion + 1, applyToken,
                    "FAILED", null, "WHATSAPP_CALLBACK_PROVIDER_FAILED");
            auditSafely(actorId, scopeId, accountId, "PHONE", "APPLY", request.upCallbackUrl(), expectedVersion,
                    "FAILED", "WHATSAPP_CALLBACK_PROVIDER_FAILED", null);
            throw error;
        }
        String upUrl = blankToNull(request.upCallbackUrl());
        String statusUrl = blankToNull(request.statusCallbackUrl());
        String action = upUrl == null && statusUrl == null ? "CLEAR" : "APPLY";
        if (configs.updatePhoneDesiredAndApplySuccess(config.getId(), expectedVersion + 1,
                upUrl, statusUrl, flag(request.httpFlag(), "Y"), flag(request.queueFlag(), "N"),
                result.requestId(), applyToken) != 1) {
            persistProviderFailure(config.getId(), expectedVersion + 1, applyToken,
                    "SUBMISSION_UNKNOWN", result.requestId(), "WHATSAPP_CALLBACK_VERSION_CONFLICT");
            auditSafely(actorId, scopeId, accountId, "PHONE", action, request.upCallbackUrl(), expectedVersion,
                    "SUBMISSION_UNKNOWN", "WHATSAPP_CALLBACK_VERSION_CONFLICT", result.requestId());
            throw failure("WHATSAPP_CALLBACK_VERSION_CONFLICT", HttpStatus.CONFLICT);
        }
        auditSafely(actorId, scopeId, accountId, "PHONE", action, request.upCallbackUrl(), expectedVersion,
                "SUCCEEDED", null, result.requestId());
        return list(scopeId);
    }

    public WhatsAppCallbackConfigView applyAccount(UUID actorId, UUID scopeId, AccountRequest request) {
        validateAccount(request);
        WhatsAppProviderScopeEntity scope = readyScope(scopeId);
        WhatsAppCallbackConfigEntity config = ensureAccountConfig(scopeId);
        long expectedVersion = requireVersion(config.getVersion(), request.expectedVersion());
        UUID applyToken = UUID.randomUUID();
        if (configs.claimApply(config.getId(), expectedVersion, applyToken) != 1) {
            throw failure("WHATSAPP_CALLBACK_APPLY_IN_PROGRESS", HttpStatus.CONFLICT);
        }
        ChatAppAccountCredentials credentials;
        WhatsAppCallbackGateway.ProviderApplyResult result;
        try {
            credentials = credentialsResolver.resolveSpace(scope);
            result = gateway.updateAccount(
                    new WhatsAppCallbackGateway.AccountUpdate(scope.getExternalScopeId(),
                            request.statusCallbackUrl(), flag(request.httpFlag(), "Y"),
                            flag(request.queueFlag(), "N")), credentials);
        } catch (WhatsAppAuthorizationException error) {
            String status = isSubmissionUnknown(error) ? "SUBMISSION_UNKNOWN" : "FAILED";
            persistProviderFailure(config.getId(), expectedVersion + 1, applyToken, status, null, error.code());
            auditSafely(actorId, scopeId, null, "ACCOUNT", "APPLY", request.statusCallbackUrl(), expectedVersion,
                    status, error.code(), null);
            throw error;
        } catch (RuntimeException error) {
            persistProviderFailure(config.getId(), expectedVersion + 1, applyToken,
                    "FAILED", null, "WHATSAPP_CALLBACK_PROVIDER_FAILED");
            auditSafely(actorId, scopeId, null, "ACCOUNT", "APPLY", request.statusCallbackUrl(), expectedVersion,
                    "FAILED", "WHATSAPP_CALLBACK_PROVIDER_FAILED", null);
            throw error;
        }
        if (configs.updateAccountDesiredAndApplySuccess(config.getId(), expectedVersion + 1,
                blankToNull(request.statusCallbackUrl()), flag(request.httpFlag(), "Y"),
                flag(request.queueFlag(), "N"), result.requestId(), applyToken) != 1) {
            persistProviderFailure(config.getId(), expectedVersion + 1, applyToken,
                    "SUBMISSION_UNKNOWN", result.requestId(), "WHATSAPP_CALLBACK_VERSION_CONFLICT");
            auditSafely(actorId, scopeId, null, "ACCOUNT", blankToNull(request.statusCallbackUrl()) == null ? "CLEAR" : "APPLY", request.statusCallbackUrl(), expectedVersion,
                    "SUBMISSION_UNKNOWN", "WHATSAPP_CALLBACK_VERSION_CONFLICT", result.requestId());
            throw failure("WHATSAPP_CALLBACK_VERSION_CONFLICT", HttpStatus.CONFLICT);
        }
        auditSafely(actorId, scopeId, null, "ACCOUNT", blankToNull(request.statusCallbackUrl()) == null ? "CLEAR" : "APPLY", request.statusCallbackUrl(), expectedVersion,
                "SUCCEEDED", null, result.requestId());
        return list(scopeId);
    }

    private void persistProviderFailure(UUID configId, long expectedVersion, UUID applyToken,
                                        String status, String requestId, String errorCode) {
        int updated = "SUBMISSION_UNKNOWN".equals(status)
                ? configs.updateApplyUnknown(configId, expectedVersion, requestId, errorCode, applyToken)
                : configs.updateApplyFailure(configId, expectedVersion, requestId, errorCode, applyToken);
        if (updated != 1) {
            LOG.warn("callback apply result CAS lost configId={} expectedVersion={} status={} errorCode={}",
                    configId, expectedVersion, status, errorCode);
        }
    }

    private void auditSafely(UUID actorId, UUID scopeId, UUID accountId, String level, String action, String url,
                             long expectedVersion, String result, String errorCode, String requestId) {
        try {
            audit(actorId, scopeId, accountId, level, action, url, expectedVersion, result, errorCode, requestId);
        } catch (RuntimeException error) {
            LOG.error("callback audit write failed scopeId={} accountId={} level={} result={}",
                    scopeId, accountId, level, result, error);
        }
    }

    private static boolean isSubmissionUnknown(WhatsAppAuthorizationException error) {
        return "WHATSAPP_CALLBACK_PROVIDER_TIMEOUT".equals(error.code())
                || "WHATSAPP_CALLBACK_VERSION_CONFLICT".equals(error.code());
    }

    private WhatsAppProviderScopeEntity readyScope(UUID scopeId) {
        WhatsAppProviderScopeEntity scope = scopes.findAdminScopeById(scopeId);
        if (scope == null || !PROVIDER.equals(scope.getProvider())) {
            throw failure("WHATSAPP_CALLBACK_SCOPE_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        if (!"READY".equalsIgnoreCase(scope.getStatus())) {
            throw failure("WHATSAPP_CALLBACK_SCOPE_NOT_READY", HttpStatus.CONFLICT);
        }
        return scope;
    }

    private ChannelAccountEntity requireAccount(UUID scopeId, UUID accountId) {
        ChannelAccountEntity account = accounts.selectById(accountId);
        if (account == null || account.getDeletedAt() != null
                || !scopeId.equals(account.getProviderScopeId())
                || !("chatapp".equalsIgnoreCase(account.getChannelType())
                || "whatsapp".equalsIgnoreCase(account.getChannelType()))) {
            throw failure("WHATSAPP_CALLBACK_ACCOUNT_NOT_IN_SCOPE", HttpStatus.NOT_FOUND);
        }
        return account;
    }

    private WhatsAppCallbackConfigEntity ensurePhoneConfig(UUID scopeId, UUID accountId) {
        WhatsAppCallbackConfigEntity current = configs.findPhone(scopeId, accountId);
        if (current != null) return current;
        configs.insertPhone(scopeId, accountId, null, null, "Y", "N");
        return configs.findPhone(scopeId, accountId);
    }

    private WhatsAppCallbackConfigEntity ensureAccountConfig(UUID scopeId) {
        WhatsAppCallbackConfigEntity current = configs.findAccount(scopeId);
        if (current != null) return current;
        configs.insertAccount(scopeId, null, "Y", "N");
        return configs.findAccount(scopeId);
    }

    private static WhatsAppCallbackConfigView.PhoneConfig phoneView(ChannelAccountEntity account,
                                                                      WhatsAppCallbackConfigEntity config) {
        return new WhatsAppCallbackConfigView.PhoneConfig(account.getId(), mask(normalizedPhone(account)),
                config == null ? null : config.getDesiredUpCallbackUrl(),
                config == null ? null : config.getDesiredStatusCallbackUrl(),
                config == null ? "Y" : config.getHttpFlag(), config == null ? "N" : config.getQueueFlag(),
                config == null ? "UNKNOWN" : config.getProviderState(),
                config == null ? "NEVER_APPLIED" : config.getLastApplyStatus(),
                config == null ? null : config.getLastErrorCode(),
                config == null ? null : config.getLastAppliedAt(), config == null ? 0 : config.getVersion());
    }

    private static WhatsAppCallbackConfigView.AccountConfig accountView(WhatsAppCallbackConfigEntity config) {
        return config == null ? null : new WhatsAppCallbackConfigView.AccountConfig(
                config.getDesiredStatusCallbackUrl(), config.getHttpFlag(), config.getQueueFlag(),
                config.getProviderState(), config.getLastApplyStatus(), config.getLastErrorCode(),
                config.getLastAppliedAt(), config.getVersion());
    }

    private void audit(UUID actorId, UUID scopeId, UUID accountId, String level, String action, String url,
                       long expectedVersion, String result, String errorCode, String requestId) {
        WhatsAppCallbackAuditEntity audit = new WhatsAppCallbackAuditEntity();
        audit.setId(UUID.randomUUID());
        audit.setProviderScopeId(scopeId);
        audit.setChannelAccountId(accountId);
        audit.setActorUserId(actorId);
        audit.setLevel(level);
        audit.setAction(action);
        audit.setDesiredUrlHostHash(hashPart(url, true));
        audit.setDesiredUrlPathHash(hashPart(url, false));
        audit.setDesiredUrlHttps(isHttps(url));
        audit.setExpectedVersion(expectedVersion);
        audit.setResult(result);
        audit.setErrorCode(errorCode);
        audit.setProviderRequestId(requestId);
        audits.insert(audit);
    }

    private static void validatePhone(PhoneRequest request) {
        if (request == null) throw failure("WHATSAPP_CALLBACK_URL_INVALID", HttpStatus.BAD_REQUEST);
        validateUrl(request.upCallbackUrl());
        validateUrl(request.statusCallbackUrl());
        validateFlags(request.httpFlag(), request.queueFlag());
        if ("Y".equals(flag(request.httpFlag(), "Y"))
                && (request.upCallbackUrl() == null || request.upCallbackUrl().isBlank())
                && (request.statusCallbackUrl() == null || request.statusCallbackUrl().isBlank())) {
            throw failure("WHATSAPP_CALLBACK_URL_REQUIRED_WHEN_HTTP_ENABLED", HttpStatus.BAD_REQUEST);
        }
    }

    private static void validateAccount(AccountRequest request) {
        if (request == null) throw failure("WHATSAPP_CALLBACK_URL_INVALID", HttpStatus.BAD_REQUEST);
        validateUrl(request.statusCallbackUrl());
        validateFlags(request.httpFlag(), request.queueFlag());
        if ("Y".equals(flag(request.httpFlag(), "Y"))
                && (request.statusCallbackUrl() == null || request.statusCallbackUrl().isBlank())) {
            throw failure("WHATSAPP_CALLBACK_URL_REQUIRED_WHEN_HTTP_ENABLED", HttpStatus.BAD_REQUEST);
        }
    }

    private static void validateUrl(String value) {
        if (value == null || value.isBlank()) return;
        if (value.length() > 2048) throw failure("WHATSAPP_CALLBACK_URL_INVALID", HttpStatus.BAD_REQUEST);
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (Exception error) {
            throw failure("WHATSAPP_CALLBACK_URL_INVALID", HttpStatus.BAD_REQUEST);
        }
    }

    private static void validateFlags(String httpFlag, String queueFlag) {
        if (!List.of("Y", "N").contains(flag(httpFlag, "Y"))
                || !List.of("Y", "N").contains(flag(queueFlag, "N"))) {
            throw failure("WHATSAPP_CALLBACK_LEVEL_FIELD_INVALID", HttpStatus.BAD_REQUEST);
        }
    }

    private static long requireVersion(long current, Long expected) {
        if (expected != null && expected != current) {
            throw failure("WHATSAPP_CALLBACK_VERSION_CONFLICT", HttpStatus.CONFLICT);
        }
        return current;
    }

    private static String flag(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
    }

    private static String normalizedPhone(ChannelAccountEntity account) {
        String value = account.getAccountIdentifierNormalized();
        return value == null || value.isBlank() ? ContactPointUtil.normalizePhone(account.getAccountIdentifier()) : value;
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) return "";
        return value.length() <= 4 ? "****" : "********" + value.substring(value.length() - 4);
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static boolean isHttps(String value) { return value != null && value.trim().toLowerCase().startsWith("https://"); }

    private static String hashPart(String value, boolean host) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            String part = host ? uri.getHost() : uri.getRawPath();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((part == null ? "" : part).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { return null; }
    }

    private static WhatsAppAuthorizationException failure(String code, HttpStatus status) {
        return new WhatsAppAuthorizationException(code, status);
    }

    public record PhoneRequest(String upCallbackUrl, String statusCallbackUrl,
                                String httpFlag, String queueFlag, long expectedVersion) { }
    public record AccountRequest(String statusCallbackUrl, String httpFlag,
                                 String queueFlag, long expectedVersion) { }
}
