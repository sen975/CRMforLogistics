package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.AuditLogEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAccountMode;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import com.crmforlogistics.messagecenter.entity.TemplateOperationEntity;
import com.crmforlogistics.messagecenter.mapper.AuditLogMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateOperationMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.CreateResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.DeleteResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ModifyResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.OperationStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.OperationType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.PropertyResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ReviewStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class WhatsAppTemplateApplicationService {
    private final ChannelAccountMapper accountMapper;
    private final TemplateOperationMapper operationMapper;
    private final TemplateMediaAssetMapper mediaMapper;
    private final TemplateMapper templateMapper;
    private final AuditLogMapper auditLogMapper;
    private final WhatsAppTemplateGateway gateway;
    private final WhatsAppTemplateValidator validator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WhatsAppTemplateApplicationService(
            ChannelAccountMapper accountMapper,
            TemplateOperationMapper operationMapper,
            TemplateMediaAssetMapper mediaMapper,
            TemplateMapper templateMapper,
            AuditLogMapper auditLogMapper,
            WhatsAppTemplateGateway gateway,
            WhatsAppTemplateValidator validator,
            ObjectMapper objectMapper,
            Clock clock) {
        this.accountMapper = Objects.requireNonNull(accountMapper);
        this.operationMapper = Objects.requireNonNull(operationMapper);
        this.mediaMapper = Objects.requireNonNull(mediaMapper);
        this.templateMapper = Objects.requireNonNull(templateMapper);
        this.auditLogMapper = Objects.requireNonNull(auditLogMapper);
        this.gateway = Objects.requireNonNull(gateway);
        this.validator = Objects.requireNonNull(validator);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView create(UUID providerScopeId, UUID accountId, TemplateCommand command,
                                UUID actorUserId, String traceId) {
        ChannelAccountEntity account = requireAccount(accountId);
        requireEnterpriseApiAccount(account);
        if (!Objects.equals(providerScopeId, account.getProviderScopeId())) {
            throw business("WHATSAPP_PROVIDER_SCOPE_MISMATCH", HttpStatus.CONFLICT,
                    "WhatsApp account does not belong to the shared template scope");
        }
        return createInternal(providerScopeId, accountId, command, actorUserId, traceId, false);
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView createPrivate(UUID accountId, TemplateCommand command,
                                       UUID actorUserId, String traceId) {
        ChannelAccountEntity account = requireOwnedBusinessAppAccount(accountId, actorUserId);
        if (account.getProviderScopeId() == null) {
            throw business("WHATSAPP_PROVIDER_SCOPE_REQUIRED", HttpStatus.CONFLICT,
                    "Business App 账号尚未完成模板空间绑定");
        }
        return createInternal(account.getProviderScopeId(), accountId, command, actorUserId, traceId, true);
    }

    private OperationView createInternal(UUID providerScopeId, UUID accountId, TemplateCommand command,
                                         UUID actorUserId, String traceId, boolean privateDomain) {
        requireAccount(accountId);
        TemplateCommand validated = validator.validate(command);
        PreparedCommand prepared = prepareMedia(accountId, validated);
        BeginOperation begun = beginOperation(accountId, validated.clientRequestId(), OperationType.CREATE,
                null, validated.language(), validated, actorUserId, traceId);
        if (begun.existing()) {
            return operationView(begun.operation());
        }

        TemplateOperationEntity operation = begun.operation();
        TemplateCredentialSource source = privateDomain
                ? TemplateCredentialSource.account(accountId)
                : TemplateCredentialSource.space(providerScopeId);
        try {
            CreateResult result = gateway.create(source, prepared.providerCommand());
            TemplateSnapshot snapshot = detailAfterAcknowledgedWrite(source, result.templateCode(), validated.language())
                    .orElseGet(() -> unknownSnapshot(result.templateCode(), validated));
            persistSnapshot(snapshot, providerScopeId, accountId, actorUserId, privateDomain);
            attach(prepared.asset());
            succeed(operation, result.templateCode(), result.providerRequestId());
            auditOperation(operation, actorUserId, traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException e) {
            failOperation(operation, prepared.asset(), e);
            auditOperation(operation, actorUserId, traceId);
            throw e;
        }
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView modifyShared(UUID providerScopeId, UUID credentialAccountId, UUID templateId,
                                      TemplateCommand command, UUID actorUserId, UUID changeRequestId,
                                      String traceId) {
        TemplateEntity current = lockSharedTemplate(providerScopeId, credentialAccountId, templateId);
        requireEnterpriseApiAccount(requireAccount(credentialAccountId));
        TemplateCommand requested = validator.validate(command);
        final TemplateCommand validated = new TemplateCommand(requested.name(), current.getLanguageCode(), requested.category(),
                requested.components(), requested.examples(), requested.messageSendTtlSeconds(),
                requested.clientRequestId());
        if (!Objects.equals(current.getName(), validated.name())) {
            throw immutableName();
        }
        PreparedCommand prepared = prepareMedia(credentialAccountId, validated);
        BeginOperation begun = beginOperation(credentialAccountId, validated.clientRequestId(), OperationType.MODIFY,
                current.getProviderTemplateId(), current.getLanguageCode(), validated, actorUserId, traceId,
                current.getId(), changeRequestId);
        if (begun.existing()) {
            return operationView(begun.operation());
        }

        TemplateOperationEntity operation = begun.operation();
        TemplateCredentialSource source = TemplateCredentialSource.space(providerScopeId);
        try {
            ModifyResult result = gateway.modify(source, current.getProviderTemplateId(),
                    current.getLanguageCode(), prepared.providerCommand());
            TemplateSnapshot snapshot = detailAfterAcknowledgedWrite(source,
                    current.getProviderTemplateId(), current.getLanguageCode()).orElseGet(() -> unknownSnapshot(
                    current.getProviderTemplateId(), validated));
            persistSnapshot(snapshot, providerScopeId, credentialAccountId, actorUserId);
            attach(prepared.asset());
            succeed(operation, result.templateCode(), result.providerRequestId());
            audit("WHATSAPP_TEMPLATE_MODIFY", "MESSAGE_TEMPLATE", current.getId(), actorUserId,
                    json(templateSummary(current)), json(snapshot), auditResult(OperationStatus.SUCCEEDED), traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException e) {
            failOperation(operation, prepared.asset(), e);
            auditOperation(operation, actorUserId, traceId);
            throw e;
        }
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView modifyPrivate(UUID accountId, UUID actorUserId, UUID templateId,
                                       TemplateCommand command, String traceId) {
        ChannelAccountEntity account = requireOwnedBusinessAppAccount(accountId, actorUserId);
        TemplateEntity current = lockPrivateTemplate(accountId, templateId);
        TemplateCommand requested = validator.validate(command);
        final TemplateCommand validated = new TemplateCommand(requested.name(), current.getLanguageCode(), requested.category(),
                requested.components(), requested.examples(), requested.messageSendTtlSeconds(),
                requested.clientRequestId());
        if (!Objects.equals(current.getName(), validated.name())) {
            throw immutableName();
        }
        PreparedCommand prepared = prepareMedia(accountId, validated);
        BeginOperation begun = beginOperation(accountId, validated.clientRequestId(), OperationType.MODIFY,
                current.getProviderTemplateId(), current.getLanguageCode(), validated, actorUserId, traceId,
                current.getId(), null);
        if (begun.existing()) return operationView(begun.operation());
        TemplateOperationEntity operation = begun.operation();
        TemplateCredentialSource source = TemplateCredentialSource.account(accountId);
        try {
            ModifyResult result = gateway.modify(source, current.getProviderTemplateId(),
                    current.getLanguageCode(), prepared.providerCommand());
            TemplateSnapshot snapshot = detailAfterAcknowledgedWrite(source, current.getProviderTemplateId(),
                    current.getLanguageCode()).orElseGet(() -> unknownSnapshot(
                    current.getProviderTemplateId(), validated));
            persistSnapshot(snapshot, account.getProviderScopeId(), accountId, actorUserId, true);
            attach(prepared.asset());
            succeed(operation, result.templateCode(), result.providerRequestId());
            audit("WHATSAPP_PRIVATE_TEMPLATE_MODIFY", "MESSAGE_TEMPLATE", current.getId(), actorUserId,
                    json(templateSummary(current)), json(snapshot), auditResult(OperationStatus.SUCCEEDED), traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException error) {
            failOperation(operation, prepared.asset(), error);
            auditOperation(operation, actorUserId, traceId);
            throw error;
        }
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView setSendPermissionShared(UUID providerScopeId, UUID credentialAccountId, UUID templateId,
                                                 boolean allowSend, String clientRequestId, UUID actorUserId,
                                                 UUID changeRequestId, String traceId) {
        TemplateEntity current = lockSharedTemplate(providerScopeId, credentialAccountId, templateId);
        requireEnterpriseApiAccount(requireAccount(credentialAccountId));
        if (allowSend && !"APPROVED".equalsIgnoreCase(current.getStatus())) {
            throw business("TEMPLATE_NOT_APPROVED", HttpStatus.CONFLICT,
                    "Only approved templates can be enabled");
        }
        boolean previousAllowSend = Boolean.TRUE.equals(current.getAllowSend());
        BeginOperation begun = beginOperation(credentialAccountId, clientRequestId, OperationType.SET_SEND_PERMISSION,
                current.getProviderTemplateId(), current.getLanguageCode(), Map.of("allowSend", allowSend),
                actorUserId, traceId, current.getId(), changeRequestId);
        if (begun.existing()) {
            return operationView(begun.operation());
        }

        TemplateOperationEntity operation = begun.operation();
        try {
            PropertyResult result = gateway.setSendPermission(TemplateCredentialSource.space(providerScopeId),
                    current.getProviderTemplateId(), current.getLanguageCode(), allowSend);
            current.setAllowSend(result.allowSend());
            if (result.allowSend() != allowSend) {
                throw business("TEMPLATE_PERMISSION_NOT_CONFIRMED", HttpStatus.BAD_GATEWAY,
                        "Provider did not confirm the requested permission");
            }
            current.setUpdatedAt(now());
            templateMapper.updateById(current);
            succeed(operation, current.getProviderTemplateId(), result.providerRequestId());
            audit("WHATSAPP_TEMPLATE_SEND_PERMISSION", "MESSAGE_TEMPLATE", current.getId(), actorUserId,
                    json(Map.of("allowSend", previousAllowSend)), json(Map.of("allowSend", result.allowSend())),
                    auditResult(OperationStatus.SUCCEEDED), traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException e) {
            failOperation(operation, null, e);
            auditOperation(operation, actorUserId, traceId);
            throw e;
        }
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView setSendPermissionPrivate(UUID accountId, UUID actorUserId, UUID templateId,
                                                  boolean allowSend, String clientRequestId, String traceId) {
        requireOwnedBusinessAppAccount(accountId, actorUserId);
        TemplateEntity current = lockPrivateTemplate(accountId, templateId);
        boolean previousAllowSend = Boolean.TRUE.equals(current.getAllowSend());
        BeginOperation begun = beginOperation(accountId, clientRequestId, OperationType.SET_SEND_PERMISSION,
                current.getProviderTemplateId(), current.getLanguageCode(), Map.of("allowSend", allowSend),
                actorUserId, traceId, current.getId(), null);
        if (begun.existing()) return operationView(begun.operation());
        TemplateOperationEntity operation = begun.operation();
        try {
            PropertyResult result = gateway.setSendPermission(TemplateCredentialSource.account(accountId),
                    current.getProviderTemplateId(), current.getLanguageCode(), allowSend);
            if (result.allowSend() != allowSend) {
                throw business("TEMPLATE_PERMISSION_NOT_CONFIRMED", HttpStatus.BAD_GATEWAY,
                        "Provider did not confirm the requested permission");
            }
            current.setAllowSend(result.allowSend());
            current.setUpdatedAt(now());
            templateMapper.updateById(current);
            succeed(operation, current.getProviderTemplateId(), result.providerRequestId());
            audit("WHATSAPP_PRIVATE_TEMPLATE_SEND_PERMISSION", "MESSAGE_TEMPLATE", current.getId(), actorUserId,
                    json(Map.of("allowSend", previousAllowSend)), json(Map.of("allowSend", result.allowSend())),
                    auditResult(OperationStatus.SUCCEEDED), traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException error) {
            failOperation(operation, null, error);
            auditOperation(operation, actorUserId, traceId);
            throw error;
        }
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView deleteShared(UUID providerScopeId, UUID credentialAccountId, UUID templateId,
                                      String clientRequestId, UUID actorUserId, UUID changeRequestId,
                                      String traceId) {
        TemplateEntity current = lockSharedTemplate(providerScopeId, credentialAccountId, templateId);
        requireEnterpriseApiAccount(requireAccount(credentialAccountId));
        BeginOperation begun = beginOperation(credentialAccountId, clientRequestId, OperationType.DELETE,
                current.getProviderTemplateId(), current.getLanguageCode(),
                Map.of("templateCode", current.getProviderTemplateId(), "language", current.getLanguageCode()),
                actorUserId, traceId, current.getId(), changeRequestId);
        if (begun.existing()) {
            return operationView(begun.operation());
        }

        TemplateOperationEntity operation = begun.operation();
        try {
            DeleteResult result = gateway.delete(TemplateCredentialSource.space(providerScopeId),
                    current.getProviderTemplateId(), current.getLanguageCode());
            if (!result.success()) {
                throw providerRejected("TEMPLATE_DELETE_NOT_CONFIRMED", result.providerRequestId());
            }
            current.setDeletedAt(now());
            current.setAllowSend(false);
            current.setUpdatedAt(now());
            templateMapper.updateById(current);
            succeed(operation, current.getProviderTemplateId(), result.providerRequestId());
            audit("WHATSAPP_TEMPLATE_DELETE", "MESSAGE_TEMPLATE", current.getId(), actorUserId,
                    "{}", json(Map.of("deleted", true)), auditResult(OperationStatus.SUCCEEDED), traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException e) {
            failOperation(operation, null, e);
            auditOperation(operation, actorUserId, traceId);
            throw e;
        }
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView deletePrivate(UUID accountId, UUID actorUserId, UUID templateId,
                                       String clientRequestId, String traceId) {
        requireOwnedBusinessAppAccount(accountId, actorUserId);
        TemplateEntity current = lockPrivateTemplate(accountId, templateId);
        BeginOperation begun = beginOperation(accountId, clientRequestId, OperationType.DELETE,
                current.getProviderTemplateId(), current.getLanguageCode(),
                Map.of("templateCode", current.getProviderTemplateId(), "language", current.getLanguageCode()),
                actorUserId, traceId, current.getId(), null);
        if (begun.existing()) return operationView(begun.operation());
        TemplateOperationEntity operation = begun.operation();
        try {
            DeleteResult result = gateway.delete(TemplateCredentialSource.account(accountId),
                    current.getProviderTemplateId(), current.getLanguageCode());
            if (!result.success()) throw providerRejected("TEMPLATE_DELETE_NOT_CONFIRMED", result.providerRequestId());
            current.setDeletedAt(now());
            current.setAllowSend(false);
            current.setUpdatedAt(now());
            templateMapper.updateById(current);
            succeed(operation, current.getProviderTemplateId(), result.providerRequestId());
            audit("WHATSAPP_PRIVATE_TEMPLATE_DELETE", "MESSAGE_TEMPLATE", current.getId(), actorUserId,
                    "{}", json(Map.of("deleted", true)), auditResult(OperationStatus.SUCCEEDED), traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException error) {
            failOperation(operation, null, error);
            auditOperation(operation, actorUserId, traceId);
            throw error;
        }
    }

    private ChannelAccountEntity requireAccount(UUID accountId) {
        ChannelAccountEntity account = accountId == null ? null : accountMapper.selectById(accountId);
        if (account == null || account.getDeletedAt() != null) {
            throw business("WHATSAPP_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND, "WhatsApp account not found");
        }
        String type = normalized(account.getChannelType());
        if (!"whatsapp".equals(type) && !"chatapp".equals(type)) {
            throw business("WHATSAPP_ACCOUNT_TYPE_INVALID", HttpStatus.BAD_REQUEST,
                    "Channel account is not a WhatsApp account");
        }
        if (!"active".equals(normalized(account.getAuthStatus()))) {
            throw business("WHATSAPP_ACCOUNT_NOT_ACTIVE", HttpStatus.CONFLICT, "WhatsApp account is not active");
        }
        return account;
    }

    public ChannelAccountEntity requireOwnedBusinessAppAccount(UUID accountId, UUID actorUserId) {
        if (actorUserId == null) {
            throw business("WHATSAPP_ACCOUNT_OWNER_REQUIRED", HttpStatus.UNAUTHORIZED,
                    "WhatsApp 账号 owner 未登录");
        }
        ChannelAccountEntity account = accountId == null ? null : accountMapper.findByIdAndOwner(accountId, actorUserId);
        if (account == null) {
            throw business("WHATSAPP_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND,
                    "WhatsApp 账号不存在或不属于当前用户");
        }
        if (!WhatsAppAccountMode.isBusinessApp(account.getOnboardingMode())) {
            throw business("WHATSAPP_TEMPLATE_DOMAIN_MISMATCH", HttpStatus.CONFLICT,
                    "当前 WhatsApp 账号不是 Business App 私有模板账号");
        }
        return account;
    }

    private static void requireEnterpriseApiAccount(ChannelAccountEntity account) {
        if (WhatsAppAccountMode.isBusinessApp(account.getOnboardingMode())) {
            throw business("WHATSAPP_TEMPLATE_DOMAIN_MISMATCH", HttpStatus.CONFLICT,
                    "Business App 账号只能使用自己的私有模板");
        }
    }

    private PreparedCommand prepareMedia(UUID accountId, TemplateCommand command) {
        TemplateMediaAssetEntity selected = null;
        List<TemplateComponent> components = new ArrayList<>(command.components().size());
        for (TemplateComponent component : command.components()) {
            if (component.type() == ComponentType.HEADER && component.headerFormat() != null
                    && component.headerFormat() != HeaderFormat.TEXT) {
                UUID assetId;
                try {
                    assetId = UUID.fromString(component.mediaAssetId());
                } catch (RuntimeException e) {
                    throw validation("header.mediaAssetId", "must be a valid internal media asset ID");
                }
                TemplateMediaAssetEntity asset = mediaMapper.findByIdAndChannelAccountId(assetId, accountId)
                        .orElseThrow(() -> validation("header.mediaAssetId", "does not belong to this account"));
                if (!"UPLOADED".equals(asset.getAssetStatus())) {
                    throw validation("header.mediaAssetId", "must reference an UPLOADED asset");
                }
                if (!component.headerFormat().name().equals(asset.getMediaFormat())) {
                    throw validation("header.mediaAssetId", "media format does not match the header format");
                }
                selected = asset;
                components.add(new TemplateComponent(component.type(), component.headerFormat(), component.text(),
                        asset.getProviderUrl(), component.buttons()));
            } else {
                components.add(component);
            }
        }
        return new PreparedCommand(new TemplateCommand(command.name(), command.language(), command.category(),
                components, command.examples(), command.messageSendTtlSeconds(), command.clientRequestId()), selected);
    }

    private BeginOperation beginOperation(UUID accountId, String idempotencyKey, OperationType type,
                                          String templateCode, String language, Object request,
                                          UUID actorUserId, String traceId) {
        return beginOperation(accountId, idempotencyKey, type, templateCode, language, request,
                actorUserId, traceId, null, null);
    }

    private BeginOperation beginOperation(UUID accountId, String idempotencyKey, OperationType type,
                                          String templateCode, String language, Object request,
                                          UUID actorUserId, String traceId, UUID templateId,
                                          UUID changeRequestId) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw validation("clientRequestId", "must contain 1 to 255 characters");
        }
        Optional<TemplateOperationEntity> existing = operationMapper.findByIdempotency(accountId, idempotencyKey);
        if (existing.isPresent()) {
            return new BeginOperation(existing.orElseThrow(), true);
        }

        TemplateOperationEntity operation = new TemplateOperationEntity();
        operation.setId(UUID.randomUUID());
        operation.setChannelAccountId(accountId);
        operation.setTemplateId(templateId);
        operation.setChangeRequestId(changeRequestId);
        operation.setIdempotencyKey(idempotencyKey);
        operation.setOperationType(type.name());
        operation.setProviderTemplateId(templateCode);
        operation.setLanguageCode(language);
        operation.setRequestedSnapshotJsonb(json(request));
        operation.setOperationStatus(OperationStatus.PROCESSING.name());
        operation.setReconcileAttemptCount(0);
        operation.setActorUserId(actorUserId);
        operation.setTraceId(traceId);
        operation.setStartedAt(now());
        if (operationMapper.insertIgnore(operation) == 0) {
            return new BeginOperation(operationMapper.findByIdempotency(accountId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Idempotent operation disappeared")), true);
        }
        TemplateOperationEntity locked = operationMapper.findByIdForUpdate(operation.getId()).orElse(operation);
        return new BeginOperation(locked, !OperationStatus.PROCESSING.name().equals(locked.getOperationStatus()));
    }

    private TemplateEntity lockSharedTemplate(UUID providerScopeId, UUID credentialAccountId, UUID templateId) {
        ChannelAccountEntity account = requireAccount(credentialAccountId);
        if (!Objects.equals(providerScopeId, account.getProviderScopeId())) {
            throw business("WHATSAPP_PROVIDER_SCOPE_MISMATCH", HttpStatus.CONFLICT,
                    "WhatsApp account does not belong to the shared template scope");
        }
        TemplateEntity template = templateId == null ? null : templateMapper.findSharedForUpdate(templateId).orElse(null);
        if (template == null || template.getDeletedAt() != null
                || "EMPLOYEE_BUSINESS_APP".equalsIgnoreCase(template.getTemplateDomain())) {
            throw business("WHATSAPP_TEMPLATE_NOT_FOUND", HttpStatus.NOT_FOUND, "WhatsApp template not found");
        }
        if (!Objects.equals(providerScopeId, template.getProviderScopeId())) {
            throw business("WHATSAPP_PROVIDER_SCOPE_MISMATCH", HttpStatus.CONFLICT,
                    "WhatsApp template does not belong to the requested shared template scope");
        }
        return template;
    }

    private TemplateEntity lockPrivateTemplate(UUID accountId, UUID templateId) {
        TemplateEntity template = templateId == null ? null
                : templateMapper.findPrivateForUpdate(templateId, accountId).orElse(null);
        if (template == null || template.getDeletedAt() != null) {
            throw business("WHATSAPP_TEMPLATE_NOT_FOUND", HttpStatus.NOT_FOUND, "WhatsApp 私有模板不存在");
        }
        return template;
    }

    private void persistSnapshot(TemplateSnapshot snapshot, UUID providerScopeId,
                                 UUID credentialAccountId, UUID createdByUserId) {
        persistSnapshot(snapshot, providerScopeId, credentialAccountId, createdByUserId, false);
    }

    private void persistSnapshot(TemplateSnapshot snapshot, UUID providerScopeId,
                                 UUID credentialAccountId, UUID createdByUserId, boolean privateDomain) {
        if (providerScopeId == null) {
            throw new IllegalArgumentException("providerScopeId is required");
        }
        TemplateEntity shared = privateDomain
                ? templateMapper.findByPrivateIdentity(credentialAccountId, snapshot.templateCode(), snapshot.language())
                .orElseGet(TemplateEntity::new)
                : templateMapper.findBySharedIdentity(providerScopeId, snapshot.templateCode(), snapshot.language())
                .orElseGet(TemplateEntity::new);
        if (shared.getId() == null) {
            shared.setId(UUID.randomUUID());
            shared.setCreatedAt(now());
            shared.setCreatedByUserId(createdByUserId);
        }
        shared.setChannelAccountId(privateDomain ? credentialAccountId : null);
        shared.setProviderScopeId(providerScopeId);
        shared.setTemplateDomain(privateDomain ? "EMPLOYEE_BUSINESS_APP" : "ENTERPRISE_API");
        applySnapshot(shared, snapshot);
        if (privateDomain) {
            templateMapper.upsertPrivate(shared);
        } else {
            templateMapper.upsertShared(shared);
        }
    }

    private void applySnapshot(TemplateEntity entity, TemplateSnapshot snapshot) {
        entity.setProviderTemplateId(snapshot.templateCode());
        entity.setLanguageCode(snapshot.language());
        entity.setName(snapshot.templateName());
        entity.setBody(snapshot.components().stream()
                .filter(component -> component.type() == ComponentType.BODY)
                .map(TemplateComponent::text).findFirst().orElse(""));
        entity.setStatus(snapshot.reviewStatus().name());
        entity.setCategory(snapshot.category());
        entity.setTemplateType("WHATSAPP");
        entity.setComponentsJsonb(json(snapshot.components()));
        entity.setExamplesJsonb(json(snapshot.examples()));
        entity.setMessageSendTtlSeconds(snapshot.messageSendTtlSeconds());
        entity.setAllowSend(snapshot.allowSend());
        entity.setProviderAuditStatus(snapshot.rawAuditStatus());
        entity.setRejectionReason(snapshot.rejectionReason());
        entity.setProviderUpdatedAt(snapshot.providerUpdatedAt());
        entity.setLastSyncedAt(now());
        entity.setUpdatedAt(now());
        entity.setDeletedAt(snapshot.deletedAt());
    }

    private TemplateSnapshot unknownSnapshot(String templateCode, TemplateCommand command) {
        return new TemplateSnapshot(templateCode, command.name(), command.language(), command.category(),
                ReviewStatus.UNKNOWN, null, null, false, command.components(), command.examples(),
                command.messageSendTtlSeconds(), now(), null);
    }

    private Optional<TemplateSnapshot> detailAfterAcknowledgedWrite(
            TemplateCredentialSource source, String templateCode, String language) {
        try {
            return gateway.detail(source, templateCode, language);
        } catch (WhatsAppTemplateException ignored) {
            return Optional.empty();
        }
    }

    private void failOperation(TemplateOperationEntity operation, TemplateMediaAssetEntity asset,
                               WhatsAppTemplateException error) {
        if (error.retryable()) {
            operationMapper.markUnknown(operation.getId(), error.code(), error.getMessage(),
                    now().plus(1, ChronoUnit.MINUTES));
            operation.setOperationStatus(OperationStatus.SUBMISSION_UNKNOWN.name());
            operation.setErrorCode(error.code());
            if (asset != null) {
                asset.setAssetStatus("ATTACHMENT_UNKNOWN");
                mediaMapper.updateById(asset);
            }
        } else {
            operationMapper.markFailed(operation.getId(), error.providerRequestId(), error.code(),
                    error.getMessage(), now());
            operation.setOperationStatus(OperationStatus.FAILED.name());
            operation.setProviderRequestId(error.providerRequestId());
            operation.setErrorCode(error.code());
            if (asset != null) {
                mediaMapper.markOrphaned(asset.getId());
            }
        }
    }

    private void succeed(TemplateOperationEntity operation, String templateCode, String requestId) {
        operationMapper.markSucceeded(operation.getId(), templateCode, requestId, now());
        operation.setProviderTemplateId(templateCode);
        operation.setProviderRequestId(requestId);
        operation.setOperationStatus(OperationStatus.SUCCEEDED.name());
        operation.setCompletedAt(now());
    }

    private void attach(TemplateMediaAssetEntity asset) {
        if (asset != null) {
            mediaMapper.markAttached(asset.getId(), now());
            asset.setAssetStatus("ATTACHED");
            asset.setAttachedAt(now());
        }
    }

    private void auditOperation(TemplateOperationEntity operation, UUID actorUserId, String traceId) {
        audit("WHATSAPP_TEMPLATE_" + operation.getOperationType(), "TEMPLATE_OPERATION", operation.getId(),
                actorUserId, "{}", json(operationView(operation)),
                auditResult(OperationStatus.valueOf(operation.getOperationStatus())), traceId);
    }

    private void audit(String action, String resourceType, UUID resourceId, UUID actorUserId,
                       String before, String after, String result, String traceId) {
        AuditLogEntity audit = new AuditLogEntity();
        audit.setId(UUID.randomUUID());
        audit.setActorUserId(actorUserId);
        audit.setAction(action);
        audit.setResourceType(resourceType);
        audit.setResourceId(resourceId);
        audit.setBeforeSummaryJsonb(before);
        audit.setAfterSummaryJsonb(after);
        audit.setResult(result);
        audit.setTraceId(traceId);
        audit.setOccurredAt(now());
        auditLogMapper.insert(audit);
    }

    private OperationView operationView(TemplateOperationEntity operation) {
        return new OperationView(operation.getId(), OperationType.valueOf(operation.getOperationType()),
                OperationStatus.valueOf(operation.getOperationStatus()), operation.getProviderTemplateId(),
                operation.getProviderRequestId(), operation.getErrorCode());
    }

    private static Map<String, Object> templateSummary(TemplateEntity entity) {
        return Map.of("templateCode", entity.getProviderTemplateId(), "language", entity.getLanguageCode(),
                "status", entity.getStatus() == null ? "" : entity.getStatus(),
                "allowSend", Boolean.TRUE.equals(entity.getAllowSend()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize template lifecycle state", e);
        }
    }

    private Instant now() {
        return clock.instant();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String auditResult(OperationStatus status) {
        return switch (status) {
            case SUCCEEDED -> "success";
            case FAILED -> "failed";
            case SUBMISSION_UNKNOWN, PROCESSING -> "unknown";
        };
    }

    private static WhatsAppTemplateException validation(String field, String message) {
        return WhatsAppTemplateException.validation(Map.of(field, message));
    }

    private static WhatsAppTemplateException immutableName() {
        return new WhatsAppTemplateException("TEMPLATE_NAME_IMMUTABLE", HttpStatus.BAD_REQUEST,
                "WhatsApp template name cannot be changed", Map.of("name", "cannot be changed after template creation"),
                null, false);
    }

    private static WhatsAppTemplateException business(String code, HttpStatus status, String message) {
        return new WhatsAppTemplateException(code, status, message, Map.of(), null, false);
    }

    private static WhatsAppTemplateException providerRejected(String code, String requestId) {
        return new WhatsAppTemplateException(code, HttpStatus.BAD_GATEWAY,
                "Provider did not confirm the operation", Map.of(), requestId, false);
    }

    public record OperationView(UUID operationId, OperationType operationType, OperationStatus operationStatus,
                                String templateCode, String providerRequestId, String errorCode) {
    }

    public record OperationHistoryView(UUID operationId, String operationType, String operationStatus,
                                       String templateCode, String language, String providerRequestId,
                                       String errorCode, String errorMessage, String traceId, UUID actorUserId,
                                       Instant startedAt, Instant completedAt) {
    }

    private record PreparedCommand(TemplateCommand providerCommand, TemplateMediaAssetEntity asset) {
    }

    private record BeginOperation(TemplateOperationEntity operation, boolean existing) {
    }
}
