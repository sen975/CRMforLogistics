package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.crmforlogistics.messagecenter.entity.AuditLogEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
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
import com.fasterxml.jackson.core.type.TypeReference;
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
    public OperationView create(UUID accountId, TemplateCommand command, UUID actorUserId, String traceId) {
        return createInternal(null, accountId, command, actorUserId, traceId);
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView create(UUID providerScopeId, UUID accountId, TemplateCommand command,
                                UUID actorUserId, String traceId) {
        ChannelAccountEntity account = requireAccount(accountId);
        if (!Objects.equals(providerScopeId, account.getProviderScopeId())) {
            throw business("WHATSAPP_PROVIDER_SCOPE_MISMATCH", HttpStatus.CONFLICT,
                    "WhatsApp account does not belong to the shared template scope");
        }
        return createInternal(providerScopeId, accountId, command, actorUserId, traceId);
    }

    private OperationView createInternal(UUID providerScopeId, UUID accountId, TemplateCommand command,
                                         UUID actorUserId, String traceId) {
        requireAccount(accountId);
        TemplateCommand validated = validator.validate(command);
        PreparedCommand prepared = prepareMedia(accountId, validated);
        BeginOperation begun = beginOperation(accountId, validated.clientRequestId(), OperationType.CREATE,
                null, validated.language(), validated, actorUserId, traceId);
        if (begun.existing()) {
            return operationView(begun.operation());
        }

        TemplateOperationEntity operation = begun.operation();
        try {
            CreateResult result = gateway.create(accountId, prepared.providerCommand());
            TemplateSnapshot snapshot = detailAfterAcknowledgedWrite(accountId, result.templateCode(), validated.language())
                    .orElseGet(() -> unknownSnapshot(accountId, result.templateCode(), validated));
            persistSnapshot(snapshot, providerScopeId, accountId, actorUserId);
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
    public OperationView modify(UUID accountId, String templateCode, String language, TemplateCommand command,
                                UUID actorUserId, String traceId) {
        requireAccount(accountId);
        TemplateEntity current = lockTemplate(accountId, templateCode, language);
        TemplateCommand validated = validator.validate(command);
        if (!Objects.equals(current.getName(), validated.name())) {
            throw immutableName();
        }
        PreparedCommand prepared = prepareMedia(accountId, validated);
        BeginOperation begun = beginOperation(accountId, validated.clientRequestId(), OperationType.MODIFY,
                templateCode, language, validated, actorUserId, traceId);
        if (begun.existing()) {
            return operationView(begun.operation());
        }

        TemplateOperationEntity operation = begun.operation();
        try {
            ModifyResult result = gateway.modify(accountId, templateCode, language, prepared.providerCommand());
            TemplateSnapshot snapshot = detailAfterAcknowledgedWrite(accountId, templateCode, language)
                    .orElseGet(() -> unknownSnapshot(accountId, templateCode, validated));
            persistSnapshot(snapshot);
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
    public OperationView setSendPermission(UUID accountId, String templateCode, String language, boolean allowSend,
                                           String clientRequestId, UUID actorUserId, String traceId) {
        requireAccount(accountId);
        TemplateEntity current = lockTemplate(accountId, templateCode, language);
        if (allowSend && !"APPROVED".equalsIgnoreCase(current.getStatus())) {
            throw business("TEMPLATE_NOT_APPROVED", HttpStatus.CONFLICT,
                    "Only approved templates can be enabled");
        }
        boolean previousAllowSend = Boolean.TRUE.equals(current.getAllowSend());
        BeginOperation begun = beginOperation(accountId, clientRequestId, OperationType.SET_SEND_PERMISSION,
                templateCode, language, Map.of("allowSend", allowSend), actorUserId, traceId);
        if (begun.existing()) {
            return operationView(begun.operation());
        }

        TemplateOperationEntity operation = begun.operation();
        current.setDesiredAllowSend(allowSend);
        current.setPermissionSyncStatus("PENDING");
        current.setPermissionSyncAttemptCount(0);
        current.setPermissionSyncNextAttemptAt(null);
        current.setPermissionSyncErrorCode(null);
        current.setPermissionSyncErrorMessage(null);
        current.setUpdatedAt(now());
        templateMapper.updateById(current);
        try {
            PropertyResult result = gateway.setSendPermission(accountId, templateCode, language, allowSend);
            current.setAllowSend(result.allowSend());
            current.setPermissionSyncStatus(result.allowSend() == allowSend ? "IDLE" : "FAILED");
            current.setPermissionSyncAttemptCount(result.allowSend() == allowSend ? 0 : 1);
            current.setPermissionSyncNextAttemptAt(
                    result.allowSend() == allowSend ? null : now().plus(1, ChronoUnit.MINUTES));
            current.setPermissionSyncErrorCode(
                    result.allowSend() == allowSend ? null : "TEMPLATE_PERMISSION_NOT_CONFIRMED");
            current.setPermissionSyncErrorMessage(
                    result.allowSend() == allowSend ? null : "Provider did not confirm desired permission");
            current.setUpdatedAt(now());
            templateMapper.updateById(current);
            succeed(operation, templateCode, result.providerRequestId());
            audit("WHATSAPP_TEMPLATE_SEND_PERMISSION", "MESSAGE_TEMPLATE", current.getId(), actorUserId,
                    json(Map.of("allowSend", previousAllowSend)), json(Map.of("allowSend", result.allowSend())),
                    auditResult(OperationStatus.SUCCEEDED), traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException e) {
            current.setPermissionSyncStatus("FAILED");
            current.setPermissionSyncAttemptCount(1);
            current.setPermissionSyncNextAttemptAt(now().plus(1, ChronoUnit.MINUTES));
            current.setPermissionSyncErrorCode(e.code());
            current.setPermissionSyncErrorMessage(TemplatePermissionErrorSanitizer.sanitize(e.getMessage()));
            current.setUpdatedAt(now());
            templateMapper.updateById(current);
            failOperation(operation, null, e);
            auditOperation(operation, actorUserId, traceId);
            throw e;
        }
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView delete(UUID accountId, String templateCode, String language, String clientRequestId,
                                UUID actorUserId, String traceId) {
        requireAccount(accountId);
        TemplateEntity current = lockTemplate(accountId, templateCode, language);
        BeginOperation begun = beginOperation(accountId, clientRequestId, OperationType.DELETE,
                templateCode, language, Map.of("templateCode", templateCode, "language", language),
                actorUserId, traceId);
        if (begun.existing()) {
            return operationView(begun.operation());
        }

        TemplateOperationEntity operation = begun.operation();
        try {
            DeleteResult result = gateway.delete(accountId, templateCode, language);
            if (!result.success()) {
                throw providerRejected("TEMPLATE_DELETE_NOT_CONFIRMED", result.providerRequestId());
            }
            current.setDeletedAt(now());
            current.setAllowSend(false);
            current.setDesiredAllowSend(false);
            current.setPermissionSyncStatus("IDLE");
            current.setPermissionSyncAttemptCount(0);
            current.setPermissionSyncNextAttemptAt(null);
            current.setPermissionSyncErrorCode(null);
            current.setPermissionSyncErrorMessage(null);
            current.setUpdatedAt(now());
            templateMapper.updateById(current);
            succeed(operation, templateCode, result.providerRequestId());
            audit("WHATSAPP_TEMPLATE_DELETE", "MESSAGE_TEMPLATE", current.getId(), actorUserId,
                    "{}", json(Map.of("deleted", true)), auditResult(OperationStatus.SUCCEEDED), traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException e) {
            failOperation(operation, null, e);
            auditOperation(operation, actorUserId, traceId);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public TemplatePageView list(UUID accountId, int page, int size, String search, String status,
                                 String category, String language, Boolean allowSend, Boolean deleted) {
        requireAccount(accountId);
        if (page < 1) {
            throw validation("page", "must be at least 1");
        }
        if (size < 1 || size > 100) {
            throw validation("size", "must be between 1 and 100");
        }

        QueryWrapper<TemplateEntity> query = new QueryWrapper<TemplateEntity>()
                .eq("channel_account_id", accountId);
        if (search != null && !search.isBlank()) {
            String term = search.trim();
            query.and(nested -> nested.like("name", term).or().like("remark", term)
                    .or().like("provider_template_id", term));
        }
        if (status != null && !status.isBlank()) {
            query.apply("upper(status) = {0}", status.trim().toUpperCase(Locale.ROOT));
        }
        if (category != null && !category.isBlank()) {
            query.apply("upper(category) = {0}", category.trim().toUpperCase(Locale.ROOT));
        }
        if (language != null && !language.isBlank()) {
            query.eq("language_code", language.trim());
        }
        if (allowSend != null) {
            query.eq("allow_send", allowSend);
        }
        if (Boolean.TRUE.equals(deleted)) {
            query.isNotNull("deleted_at");
        } else {
            query.isNull("deleted_at");
        }
        long total = templateMapper.selectCount(query);
        List<TemplateEntity> records = List.of();
        if (total > 0) {
            long offset = (long) (page - 1) * size;
            QueryWrapper<TemplateEntity> pageQuery = query.clone()
                    .orderByDesc("updated_at")
                    .last("LIMIT " + size + " OFFSET " + offset);
            records = templateMapper.selectList(pageQuery);
        }
        return new TemplatePageView(records.stream().map(this::templateView).toList(), total, page, size);
    }

    @Transactional(readOnly = true)
    public TemplateView detail(UUID accountId, String templateCode, String language) {
        requireAccount(accountId);
        requireTemplateKey(templateCode, language);
        return templateMapper.findForDisplay(accountId, templateCode, language)
                .map(this::templateView)
                .orElseThrow(() -> business("TEMPLATE_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "WhatsApp template not found"));
    }

    @Transactional(readOnly = true)
    public List<OperationHistoryView> history(UUID accountId, String templateCode, String language) {
        requireAccount(accountId);
        requireTemplateKey(templateCode, language);
        if (templateMapper.findForDisplay(accountId, templateCode, language).isEmpty()) {
            throw business("TEMPLATE_NOT_FOUND", HttpStatus.NOT_FOUND, "WhatsApp template not found");
        }
        return operationMapper.selectList(new QueryWrapper<TemplateOperationEntity>()
                        .eq("channel_account_id", accountId)
                        .eq("provider_template_id", templateCode)
                        .eq("language_code", language)
                        .orderByDesc("started_at")
                        .last("LIMIT 100"))
                .stream().map(WhatsAppTemplateApplicationService::operationHistoryView).toList();
    }

    public void validateAccount(UUID accountId) {
        requireAccount(accountId);
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

    private static void requireTemplateKey(String templateCode, String language) {
        if (templateCode == null || templateCode.isBlank()) {
            throw validation("templateCode", "is required");
        }
        if (language == null || language.isBlank()) {
            throw validation("language", "is required");
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

    private TemplateEntity lockTemplate(UUID accountId, String templateCode, String language) {
        TemplateEntity template = templateMapper.selectOne(new QueryWrapper<TemplateEntity>()
                .eq("channel_account_id", accountId)
                .eq("provider_template_id", templateCode)
                .eq("language_code", language)
                .last("FOR UPDATE"));
        if (template == null || template.getDeletedAt() != null) {
            throw business("WHATSAPP_TEMPLATE_NOT_FOUND", HttpStatus.NOT_FOUND, "WhatsApp template not found");
        }
        return template;
    }

    private void persistSnapshot(TemplateSnapshot snapshot) {
        persistSnapshot(snapshot, null, snapshot.accountId(), null);
    }

    private void persistSnapshot(TemplateSnapshot snapshot, UUID providerScopeId,
                                 UUID credentialAccountId, UUID createdByUserId) {
        if (providerScopeId != null) {
            TemplateEntity shared = templateMapper.findBySharedIdentity(providerScopeId,
                    snapshot.templateCode(), snapshot.language()).orElseGet(TemplateEntity::new);
            boolean insert = shared.getId() == null;
            if (insert) {
                shared.setId(UUID.randomUUID());
                shared.setCreatedAt(now());
                shared.setCreatedByUserId(createdByUserId);
            }
            shared.setChannelAccountId(credentialAccountId);
            shared.setProviderScopeId(providerScopeId);
            applySnapshot(shared, snapshot);
            templateMapper.upsertShared(shared);
            return;
        }
        TemplateEntity entity = templateMapper.selectOne(new QueryWrapper<TemplateEntity>()
                .eq("channel_account_id", snapshot.accountId())
                .eq("provider_template_id", snapshot.templateCode())
                .eq("language_code", snapshot.language())
                .last("LIMIT 1"));
        boolean insert = entity == null;
        if (insert) {
            entity = new TemplateEntity();
            entity.setId(UUID.randomUUID());
            entity.setCreatedAt(now());
        }
        entity.setChannelAccountId(credentialAccountId);
        applySnapshot(entity, snapshot);
        if (insert) {
            templateMapper.insert(entity);
        } else {
            templateMapper.updateById(entity);
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
        entity.setMetadataJsonb("{}");
        entity.setLastSyncedAt(now());
        entity.setUpdatedAt(now());
        entity.setDeletedAt(snapshot.deletedAt());
    }

    private TemplateSnapshot unknownSnapshot(UUID accountId, String templateCode, TemplateCommand command) {
        return new TemplateSnapshot(accountId, templateCode, command.name(), command.language(), command.category(),
                ReviewStatus.UNKNOWN, null, null, false, command.components(), command.examples(),
                command.messageSendTtlSeconds(), now(), null);
    }

    private Optional<TemplateSnapshot> detailAfterAcknowledgedWrite(
            UUID accountId, String templateCode, String language) {
        try {
            return gateway.detail(accountId, templateCode, language);
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

    private TemplateView templateView(TemplateEntity template) {
        return new TemplateView(template.getId(), template.getChannelAccountId(), template.getProviderTemplateId(),
                template.getName(), template.getRemark(),
                TemplateDisplayName.format(template.getName(), template.getRemark()),
                template.getLanguageCode(), template.getCategory(), template.getStatus(),
                template.getProviderAuditStatus(), template.getRejectionReason(),
                Boolean.TRUE.equals(template.getAllowSend()), desiredAllowSend(template),
                permissionSyncStatus(template), template.getPermissionSyncErrorMessage(),
                readComponents(template.getComponentsJsonb()),
                readExamples(template.getExamplesJsonb()), template.getMessageSendTtlSeconds(),
                template.getQualityScore(), template.getProviderUpdatedAt(), template.getLastSyncedAt(),
                template.getDeletedAt());
    }

    private List<TemplateComponent> readComponents(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to read stored template components", e);
        }
    }

    private Map<String, List<String>> readExamples(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to read stored template examples", e);
        }
    }

    private static OperationHistoryView operationHistoryView(TemplateOperationEntity operation) {
        return new OperationHistoryView(operation.getId(), operation.getOperationType(),
                operation.getOperationStatus(), operation.getProviderTemplateId(), operation.getLanguageCode(),
                operation.getProviderRequestId(), operation.getErrorCode(), operation.getErrorMessage(),
                operation.getTraceId(), operation.getActorUserId(), operation.getStartedAt(),
                operation.getCompletedAt());
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

    static boolean desiredAllowSend(TemplateEntity template) {
        return template.getDesiredAllowSend() == null
                ? Boolean.TRUE.equals(template.getAllowSend())
                : Boolean.TRUE.equals(template.getDesiredAllowSend());
    }

    static String permissionSyncStatus(TemplateEntity template) {
        String status = template.getPermissionSyncStatus();
        return status == null || status.isBlank() ? "IDLE" : status;
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

    public record TemplatePageView(List<TemplateView> items, long total, int page, int size) {
        public TemplatePageView {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record TemplateView(UUID id, UUID accountId, String templateCode, String name, String remark,
                               String displayName, String language,
                               String category, String reviewStatus, String providerAuditStatus,
                               String rejectionReason, boolean allowSend, boolean desiredAllowSend,
                               String permissionSyncStatus, String permissionSyncError,
                               List<TemplateComponent> components,
                               Map<String, List<String>> examples, Integer messageSendTtlSeconds,
                               String qualityScore, Instant providerUpdatedAt, Instant lastSyncedAt,
                               Instant deletedAt) {
        public TemplateView {
            components = components == null ? List.of() : List.copyOf(components);
            examples = examples == null ? Map.of() : Map.copyOf(examples);
        }
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
