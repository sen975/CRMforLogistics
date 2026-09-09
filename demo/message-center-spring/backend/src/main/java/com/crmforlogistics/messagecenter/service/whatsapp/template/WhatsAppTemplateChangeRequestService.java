package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.dto.response.TemplateChangeRequestResponse;
import com.crmforlogistics.messagecenter.dto.response.TemplateChangeRequestResponse.FieldDiff;
import com.crmforlogistics.messagecenter.entity.TemplateChangeRequestEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import com.crmforlogistics.messagecenter.entity.UserEntity;
import com.crmforlogistics.messagecenter.mapper.TemplateChangeRequestMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.mapper.UserMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ChangeCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ChangeMode;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ChangeRequestStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ChangeType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateDraft;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class WhatsAppTemplateChangeRequestService {
    private static final int MAX_PAYLOAD_BYTES = 65_536;
    private static final int MAX_PAGE_SIZE = 100;

    private final TemplateMapper templateMapper;
    private final TemplateChangeRequestMapper changeRequestMapper;
    private final TemplateMediaAssetMapper mediaAssetMapper;
    private final WhatsAppProviderScopeService providerScopeService;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final WhatsAppTemplateApplicationService applicationService;
    private final WhatsAppTemplateValidator validator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WhatsAppTemplateChangeRequestService(TemplateMapper templateMapper,
                                                TemplateChangeRequestMapper changeRequestMapper,
                                                TemplateMediaAssetMapper mediaAssetMapper,
                                                WhatsAppProviderScopeService providerScopeService,
                                                UserMapper userMapper,
                                                RoleMapper roleMapper,
                                                WhatsAppTemplateApplicationService applicationService,
                                                WhatsAppTemplateValidator validator,
                                                ObjectMapper objectMapper,
                                                Clock clock) {
        this.templateMapper = templateMapper;
        this.changeRequestMapper = changeRequestMapper;
        this.mediaAssetMapper = mediaAssetMapper;
        this.providerScopeService = providerScopeService;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.applicationService = applicationService;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ChangeOutcome submit(UUID userId, UUID templateId, ChangeCommand command, String traceId) {
        requireUser(userId);
        requireTemplateId(templateId);
        validateCommand(command);
        TemplateEntity template = templateMapper.findSharedForUpdate(templateId)
                .orElseThrow(() -> failure("TEMPLATE_NOT_FOUND", HttpStatus.NOT_FOUND, "WhatsApp template not found"));
        if ("EMPLOYEE_BUSINESS_APP".equalsIgnoreCase(template.getTemplateDomain())) {
            throw failure("WHATSAPP_TEMPLATE_DIRECT_OPERATION_REQUIRED", HttpStatus.CONFLICT,
                    "Business App 私有模板必须由账号 owner 直接操作");
        }
        WhatsAppProviderScopeService.ScopeAccount scopeAccount = providerScopeService.requireOwnedActive(userId);
        if (template.getProviderScopeId() == null || !template.getProviderScopeId().equals(scopeAccount.scope().getId())) {
            throw failure("WHATSAPP_PROVIDER_SCOPE_MISMATCH", HttpStatus.CONFLICT,
                    "WhatsApp account does not belong to the template scope");
        }

        Payload payload = new Payload(command.changeType(), command.expectedVersion(), command.clientRequestId(),
                command.template(), command.allowSend(), command.remark());
        if (isAdmin(userId)) {
            validateDraftAndMedia(command, scopeAccount.account().getId(), template);
            WhatsAppTemplateApplicationService.OperationView operation = execute(scopeAccount.scope().getId(),
                    scopeAccount.account().getId(), template, payload, command.clientRequestId(), userId, null, traceId);
            if (operation.operationStatus() == WhatsAppTemplateModels.OperationStatus.SUCCEEDED) {
                applyRemarkIfPresent(template.getId(), payload.remark());
            }
            return new ChangeOutcome(ChangeMode.DIRECT, null, operation);
        }
        String serializedPayload = serialize(payload);
        Optional<TemplateChangeRequestEntity> existing = changeRequestMapper
                .findByRequesterAndIdempotency(userId, command.clientRequestId());
        if (existing.isPresent()) {
            ensureSamePayload(existing.orElseThrow().getRequestedPayloadJsonb(), serializedPayload);
            return new ChangeOutcome(ChangeMode.APPROVAL_REQUIRED, view(existing.orElseThrow(), template), null);
        }

        validateDraftAndMedia(command, scopeAccount.account().getId(), template);
        Instant now = clock.instant();
        TemplateChangeRequestEntity request = new TemplateChangeRequestEntity();
        request.setId(UUID.randomUUID());
        request.setTemplateId(template.getId());
        request.setChangeType(command.changeType().name());
        request.setRequestedPayloadJsonb(serializedPayload);
        request.setBaseVersion(command.expectedVersion());
        request.setRequestedByUserId(userId);
        request.setRequestedViaAccountId(scopeAccount.account().getId());
        request.setStatus(template.getVersion() == null || template.getVersion() != command.expectedVersion()
                ? ChangeRequestStatus.STALE.name() : ChangeRequestStatus.PENDING_APPROVAL.name());
        request.setIdempotencyKey(command.clientRequestId());
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        if (changeRequestMapper.insertIgnore(request) == 0) {
            TemplateChangeRequestEntity replay = changeRequestMapper
                    .findByRequesterAndIdempotency(userId, command.clientRequestId())
                    .orElseThrow(() -> new IllegalStateException("Template change request disappeared"));
            ensureSamePayload(replay.getRequestedPayloadJsonb(), serializedPayload);
            return new ChangeOutcome(ChangeMode.APPROVAL_REQUIRED, view(replay, template), null);
        }
        return new ChangeOutcome(ChangeMode.APPROVAL_REQUIRED, view(request, template), null);
    }

    @Transactional
    public TemplateChangeRequestResponse.Page listMine(UUID userId, int page, int size) {
        requireUser(userId);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw WhatsAppTemplateException.validation(Map.of("page", "must be at least 1", "size", "must be between 1 and 100"));
        }
        long offset = (long) (page - 1) * size;
        List<TemplateChangeRequestResponse> items = changeRequestMapper.listByRequester(userId, offset, size).stream()
                .map(request -> view(request, templateMapper.findSharedForUpdate(request.getTemplateId()).orElse(null)))
                .toList();
        return new TemplateChangeRequestResponse.Page(items, changeRequestMapper.countByRequester(userId), page, size);
    }

    public TemplateChangeRequestResponse.Page listForReview(UUID reviewerId, int page, int size) {
        requireAdmin(reviewerId);
        validatePage(page, size);
        long total = changeRequestMapper.countForReview();
        List<TemplateChangeRequestEntity> records = total == 0 ? List.of()
                : changeRequestMapper.listForReview((long) (page - 1) * size, size);
        return new TemplateChangeRequestResponse.Page(records.stream().map(request -> view(request,
                templateMapper.findSharedForUpdate(request.getTemplateId()).orElse(null))).toList(), total, page, size);
    }

    public ChangeOutcome approve(UUID reviewerId, UUID requestId, String clientRequestId, String traceId) {
        requireAdmin(reviewerId);
        requireReviewClientRequestId(clientRequestId);
        TemplateChangeRequestEntity request = loadRequest(requestId);
        if (!ChangeRequestStatus.PENDING_APPROVAL.name().equals(request.getStatus())) {
            throw stateConflict("Only pending requests can be approved");
        }
        TemplateEntity template = templateMapper.findSharedForUpdate(request.getTemplateId())
                .orElseThrow(() -> failure("TEMPLATE_NOT_FOUND", HttpStatus.NOT_FOUND, "WhatsApp template not found"));
        Instant at = now();
        if (!java.util.Objects.equals(template.getVersion(), request.getBaseVersion())) {
            changeRequestMapper.markStale(requestId, reviewerId, at);
            request.setStatus(ChangeRequestStatus.STALE.name());
            request.setReviewedByUserId(reviewerId);
            request.setReviewedAt(at);
            request.setExecutionCompletedAt(at);
            return new ChangeOutcome(ChangeMode.APPROVAL_REQUIRED, view(request, template), null);
        }
        if (changeRequestMapper.claimApproval(requestId, reviewerId, at) != 1) {
            throw stateConflict("The request was already reviewed or the template changed");
        }
        request.setStatus(ChangeRequestStatus.EXECUTING.name());
        request.setReviewedByUserId(reviewerId);
        request.setReviewedAt(at);
        request.setExecutionStartedAt(at);
        try {
            WhatsAppProviderScopeService.ScopeAccount requestedAccount = providerScopeService.requireAccount(
                    request.getRequestedViaAccountId());
            if (!java.util.Objects.equals(template.getProviderScopeId(), requestedAccount.scope().getId())) {
                throw failure("WHATSAPP_PROVIDER_SCOPE_MISMATCH", HttpStatus.CONFLICT,
                        "The requested WhatsApp account no longer belongs to the template scope");
            }
            Payload payload = parse(request.getRequestedPayloadJsonb());
            WhatsAppTemplateApplicationService.OperationView operation = execute(template.getProviderScopeId(),
                    requestedAccount.account().getId(), template, payload,
                    clientRequestId, reviewerId, requestId, traceId);
            if (operation.operationStatus() == WhatsAppTemplateModels.OperationStatus.SUBMISSION_UNKNOWN) {
                return new ChangeOutcome(ChangeMode.APPROVAL_REQUIRED, view(request, template), operation);
            }
            if (operation.operationStatus() == WhatsAppTemplateModels.OperationStatus.SUCCEEDED) {
                applyRemarkIfPresent(template.getId(), payload.remark());
            }
            changeRequestMapper.markSucceeded(requestId, operation.providerRequestId(), now());
            request.setStatus(ChangeRequestStatus.SUCCEEDED.name());
            request.setProviderRequestId(operation.providerRequestId());
            request.setExecutionCompletedAt(now());
            return new ChangeOutcome(ChangeMode.APPROVAL_REQUIRED, view(request, template), operation);
        } catch (WhatsAppTemplateException error) {
            if (error.retryable()) {
                return new ChangeOutcome(ChangeMode.APPROVAL_REQUIRED, view(request, template), null);
            }
            changeRequestMapper.markExecutionFailed(requestId, error.code(), error.getMessage(), now());
            request.setStatus(ChangeRequestStatus.EXECUTION_FAILED.name());
            request.setExecutionErrorCode(error.code());
            request.setExecutionErrorMessage(error.getMessage());
            request.setExecutionCompletedAt(now());
            return new ChangeOutcome(ChangeMode.APPROVAL_REQUIRED, view(request, template), null);
        }
    }

    public TemplateChangeRequestResponse reject(UUID reviewerId, UUID requestId, String reason) {
        requireAdmin(reviewerId);
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw WhatsAppTemplateException.validation(Map.of("reason", "must contain 1 to 500 characters"));
        }
        TemplateChangeRequestEntity request = loadRequest(requestId);
        TemplateEntity template = templateMapper.findSharedForUpdate(request.getTemplateId()).orElse(null);
        Instant at = now();
        if (changeRequestMapper.reject(requestId, reviewerId, reason.trim(), at) != 1) {
            throw stateConflict("Only pending requests can be rejected");
        }
        request.setStatus(ChangeRequestStatus.REJECTED.name());
        request.setReviewedByUserId(reviewerId);
        request.setReviewReason(reason.trim());
        request.setReviewedAt(at);
        return view(request, template);
    }

    public ChangeOutcome retry(UUID reviewerId, UUID requestId, String clientRequestId, String traceId) {
        requireAdmin(reviewerId);
        requireReviewClientRequestId(clientRequestId);
        TemplateChangeRequestEntity request = loadRequest(requestId);
        if (changeRequestMapper.retry(requestId, now()) != 1) {
            throw stateConflict("Only execution failures can be retried");
        }
        request.setStatus(ChangeRequestStatus.PENDING_APPROVAL.name());
        return approve(reviewerId, requestId, clientRequestId, traceId);
    }

    private void validateDraftAndMedia(ChangeCommand command, UUID accountId, TemplateEntity current) {
        if (command.changeType() == ChangeType.MODIFY || command.changeType() == ChangeType.BIND_MEDIA) {
            TemplateDraft draft = command.template();
            TemplateCommand providerCommand = new TemplateCommand(draft.name(), current.getLanguageCode(), draft.category(),
                    draft.components(), draft.examples(), draft.messageSendTtlSeconds(), command.clientRequestId());
            validator.validate(providerCommand);
            for (TemplateComponent component : draft.components()) {
                if (component.type() == ComponentType.HEADER && component.headerFormat() != null
                        && component.headerFormat() != HeaderFormat.TEXT) {
                    UUID assetId;
                    try {
                        assetId = UUID.fromString(component.mediaAssetId());
                    } catch (RuntimeException exception) {
                        throw WhatsAppTemplateException.validation(Map.of("header.mediaAssetId", "must be a valid internal media asset ID"));
                    }
                    TemplateMediaAssetEntity asset = mediaAssetMapper.findByIdAndChannelAccountId(assetId, accountId)
                            .orElseThrow(() -> WhatsAppTemplateException.validation(
                                    Map.of("header.mediaAssetId", "must belong to the request account")));
                    if (!"UPLOADED".equals(asset.getAssetStatus())) {
                        throw WhatsAppTemplateException.validation(Map.of("header.mediaAssetId", "must reference an UPLOADED asset"));
                    }
                    if (!component.headerFormat().name().equals(asset.getMediaFormat())) {
                        throw WhatsAppTemplateException.validation(Map.of("header.mediaAssetId", "media format does not match the header format"));
                    }
                }
            }
        }
    }

    private void validateCommand(ChangeCommand command) {
        if (command == null || command.changeType() == null) {
            throw WhatsAppTemplateException.validation(Map.of("changeType", "is required"));
        }
        if (command.clientRequestId() == null || command.clientRequestId().isBlank() || command.clientRequestId().length() > 255) {
            throw WhatsAppTemplateException.validation(Map.of("clientRequestId", "must contain 1 to 255 characters"));
        }
        if (command.expectedVersion() < 0) {
            throw WhatsAppTemplateException.validation(Map.of("expectedVersion", "must not be negative"));
        }
        switch (command.changeType()) {
            case MODIFY, BIND_MEDIA -> {
                if (command.template() == null || command.allowSend() != null) {
                    throw WhatsAppTemplateException.validation(Map.of("template", "is required for this change type"));
                }
            }
            case SET_SEND_PERMISSION -> {
                if (command.allowSend() == null || command.template() != null || command.remark() != null) {
                    throw WhatsAppTemplateException.validation(Map.of("allowSend", "is required only for send permission changes"));
                }
            }
            case DELETE -> {
                if (command.template() != null || command.allowSend() != null || command.remark() != null) {
                    throw WhatsAppTemplateException.validation(Map.of("changeType", "DELETE does not accept template content"));
                }
            }
        }
    }

    private WhatsAppTemplateApplicationService.OperationView execute(UUID providerScopeId, UUID accountId,
                                                                       TemplateEntity template, Payload payload,
                                                                       String operationRequestId, UUID actorUserId,
                                                                       UUID changeRequestId, String traceId) {
        if (payload == null || payload.changeType() == null) {
            throw failure("TEMPLATE_CHANGE_PAYLOAD_INVALID", HttpStatus.CONFLICT, "Stored template change payload is invalid");
        }
        return switch (payload.changeType()) {
            case SET_SEND_PERMISSION -> applicationService.setSendPermissionShared(providerScopeId, accountId,
                    template.getId(), Boolean.TRUE.equals(payload.allowSend()), operationRequestId, actorUserId,
                    changeRequestId, traceId);
            case DELETE -> applicationService.deleteShared(providerScopeId, accountId, template.getId(),
                    operationRequestId, actorUserId, changeRequestId, traceId);
            case MODIFY, BIND_MEDIA -> applicationService.modifyShared(providerScopeId, accountId, template.getId(),
                    providerCommand(template, payload, operationRequestId), actorUserId, changeRequestId, traceId);
        };
    }

    private void applyRemarkIfPresent(UUID templateId, String remark) {
        if (remark != null) {
            templateMapper.updateRemark(templateId, remark.isBlank() ? null : remark.trim());
        }
    }

    private TemplateCommand providerCommand(TemplateEntity current, Payload payload, String operationRequestId) {
        TemplateDraft draft = payload.template();
        if (draft == null) {
            return new TemplateCommand(current.getName(), current.getLanguageCode(), current.getCategory(),
                    readComponents(current.getComponentsJsonb()), readExamples(current.getExamplesJsonb()),
                    current.getMessageSendTtlSeconds(), operationRequestId);
        }
        return new TemplateCommand(current.getName(), current.getLanguageCode(), draft.category(), draft.components(),
                draft.examples(), draft.messageSendTtlSeconds(), operationRequestId);
    }

    private List<TemplateComponent> readComponents(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException error) {
            throw failure("TEMPLATE_CHANGE_PAYLOAD_INVALID", HttpStatus.CONFLICT, "Stored template content is invalid");
        }
    }

    private Map<String, List<String>> readExamples(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException error) {
            throw failure("TEMPLATE_CHANGE_PAYLOAD_INVALID", HttpStatus.CONFLICT, "Stored template examples are invalid");
        }
    }

    private TemplateChangeRequestEntity loadRequest(UUID requestId) {
        if (requestId == null) {
            throw WhatsAppTemplateException.validation(Map.of("requestId", "is required"));
        }
        return changeRequestMapper.findByIdForUpdate(requestId)
                .orElseThrow(() -> failure("TEMPLATE_CHANGE_REQUEST_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "Template change request not found"));
    }

    private void requireAdmin(UUID userId) {
        requireUser(userId);
        if (!isAdmin(userId)) {
            throw failure("WHATSAPP_TEMPLATE_ADMIN_REQUIRED", HttpStatus.FORBIDDEN, "Administrator role is required");
        }
    }

    private boolean isAdmin(UUID userId) {
        return roleMapper.userHasRole(userId, "admin");
    }

    private void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw WhatsAppTemplateException.validation(Map.of("page", "must be at least 1", "size", "must be between 1 and 100"));
        }
    }

    private static void requireReviewClientRequestId(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 255) {
            throw WhatsAppTemplateException.validation(Map.of("clientRequestId", "must contain 1 to 255 characters"));
        }
    }

    private static WhatsAppTemplateException stateConflict(String message) {
        return failure("TEMPLATE_CHANGE_REQUEST_STATE_CONFLICT", HttpStatus.CONFLICT, message);
    }

    private TemplateChangeRequestResponse view(TemplateChangeRequestEntity request, TemplateEntity template) {
        Payload payload = parse(request.getRequestedPayloadJsonb());
        String requestedBy = displayName(request.getRequestedByUserId());
        String reviewedBy = displayName(request.getReviewedByUserId());
        return new TemplateChangeRequestResponse(request.getId(), request.getTemplateId(), displayName(template),
                request.getBaseVersion() == null ? 0 : request.getBaseVersion(), request.getChangeType(), request.getStatus(),
                diffs(template, payload), requestedBy, reviewedBy, request.getReviewReason(), request.getExecutionErrorCode(),
                request.getExecutionErrorMessage(), request.getProviderRequestId(), request.getCreatedAt(), request.getReviewedAt(),
                request.getExecutionCompletedAt());
    }

    private List<FieldDiff> diffs(TemplateEntity template, Payload payload) {
        if (payload == null || template == null) {
            return List.of();
        }
        List<FieldDiff> diffs = new ArrayList<>();
        if (payload.changeType() == ChangeType.SET_SEND_PERMISSION) {
            diffs.add(new FieldDiff("allowSend", "发送权限", template.getAllowSend(), payload.allowSend()));
        } else if (payload.changeType() == ChangeType.DELETE) {
            diffs.add(new FieldDiff("deleted", "模板状态", false, true));
        } else {
            TemplateDraft draft = payload.template();
            if (draft != null) {
                addDiff(diffs, "name", "模板名称", template.getName(), draft.name());
                addDiff(diffs, "category", "模板分类", template.getCategory(), draft.category());
                addDiff(diffs, "components", "模板内容", template.getComponentsJsonb(), draft.components());
                addDiff(diffs, "examples", "变量示例", template.getExamplesJsonb(), draft.examples());
                addDiff(diffs, "messageSendTtlSeconds", "消息有效期", template.getMessageSendTtlSeconds(), draft.messageSendTtlSeconds());
            }
            addDiff(diffs, "remark", "业务备注", template.getRemark(), payload.remark());
        }
        return List.copyOf(diffs);
    }

    private static void addDiff(List<FieldDiff> diffs, String field, String label, Object before, Object after) {
        if (!java.util.Objects.equals(before, after)) {
            diffs.add(new FieldDiff(field, label, before, after));
        }
    }

    private String serialize(Payload payload) {
        try {
            String value = objectMapper.writeValueAsString(payload);
            if (value.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
                throw WhatsAppTemplateException.validation(Map.of("payload", "must not exceed 65536 bytes"));
            }
            return value;
        } catch (JsonProcessingException exception) {
            throw failure("TEMPLATE_CHANGE_PAYLOAD_INVALID", HttpStatus.BAD_REQUEST, "Template change payload is invalid");
        }
    }

    private Payload parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, Payload.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private void ensureSamePayload(String stored, String requested) {
        try {
            JsonNode left = objectMapper.readTree(stored);
            JsonNode right = objectMapper.readTree(requested);
            if (!left.equals(right)) {
                throw failure("IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT,
                        "clientRequestId is already bound to a different template change");
            }
        } catch (JsonProcessingException exception) {
            throw failure("TEMPLATE_CHANGE_PAYLOAD_INVALID", HttpStatus.CONFLICT, "Stored template change payload is invalid");
        }
    }

    private String displayName(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userMapper.findByIdNotDeleted(userId).map(UserEntity::getDisplayName).orElse(null);
    }

    private static String displayName(TemplateEntity template) {
        if (template == null) {
            return null;
        }
        String name = template.getName();
        String code = template.getProviderTemplateId();
        return name == null || name.isBlank() ? code : name + "（" + code + "）";
    }

    private static void requireUser(UUID userId) {
        if (userId == null) {
            throw failure("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
    }

    private static void requireTemplateId(UUID templateId) {
        if (templateId == null) {
            throw WhatsAppTemplateException.validation(Map.of("templateId", "is required"));
        }
    }

    private static WhatsAppTemplateException failure(String code, HttpStatus status, String message) {
        return new WhatsAppTemplateException(code, status, message, Map.of(), null, false);
    }

    private Instant now() {
        return clock.instant();
    }

    private record Payload(ChangeType changeType, long expectedVersion, String clientRequestId,
                           TemplateDraft template, Boolean allowSend, String remark) { }

    public record ChangeOutcome(ChangeMode mode, TemplateChangeRequestResponse request,
                                WhatsAppTemplateApplicationService.OperationView operation) { }
}
