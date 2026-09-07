package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.AuditLogEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.AuditLogMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService.TemplateView;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class WhatsAppTemplateRemarkService {
    private static final int MAX_REMARK_LENGTH = 120;

    private final ChannelAccountMapper accountMapper;
    private final TemplateMapper templateMapper;
    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WhatsAppTemplateRemarkService(ChannelAccountMapper accountMapper, TemplateMapper templateMapper,
                                         AuditLogMapper auditLogMapper, ObjectMapper objectMapper, Clock clock) {
        this.accountMapper = Objects.requireNonNull(accountMapper);
        this.templateMapper = Objects.requireNonNull(templateMapper);
        this.auditLogMapper = Objects.requireNonNull(auditLogMapper);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public TemplateView update(UUID accountId, String templateCode, String language, String remark,
                               UUID actorUserId, String traceId) {
        requireAccount(accountId);
        requireTemplateKey(templateCode, language);
        String normalizedRemark = normalizeRemark(remark);
        TemplateEntity template = templateMapper.findForUpdate(accountId, templateCode, language)
                .filter(value -> value.getDeletedAt() == null)
                .orElseThrow(() -> business("WHATSAPP_TEMPLATE_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "WhatsApp template not found"));

        String previousRemark = template.getRemark();
        template.setRemark(normalizedRemark);
        template.setUpdatedAt(clock.instant());
        if (templateMapper.updateRemark(template.getId(), normalizedRemark, template.getUpdatedAt()) != 1) {
            throw business("TEMPLATE_REMARK_UPDATE_CONFLICT", HttpStatus.CONFLICT,
                    "WhatsApp template remark update conflict");
        }
        audit(template, previousRemark, normalizedRemark, actorUserId, traceId);
        return view(template);
    }

    private void requireAccount(UUID accountId) {
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
    }

    private static String normalizeRemark(String remark) {
        if (remark == null) {
            return null;
        }
        String normalized = remark.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > MAX_REMARK_LENGTH) {
            throw new WhatsAppTemplateException("TEMPLATE_REMARK_INVALID", HttpStatus.BAD_REQUEST,
                    "WhatsApp template remark is invalid",
                    Map.of("remark", "must contain at most 120 characters"), null, false);
        }
        return normalized;
    }

    private static void requireTemplateKey(String templateCode, String language) {
        if (templateCode == null || templateCode.isBlank()) {
            throw WhatsAppTemplateException.validation(Map.of("templateCode", "is required"));
        }
        if (language == null || language.isBlank()) {
            throw WhatsAppTemplateException.validation(Map.of("language", "is required"));
        }
    }

    private void audit(TemplateEntity template, String beforeRemark, String afterRemark,
                       UUID actorUserId, String traceId) {
        AuditLogEntity audit = new AuditLogEntity();
        audit.setId(UUID.randomUUID());
        audit.setActorUserId(actorUserId);
        audit.setAction("WHATSAPP_TEMPLATE_REMARK_UPDATE");
        audit.setResourceType("MESSAGE_TEMPLATE");
        audit.setResourceId(template.getId());
        audit.setBeforeSummaryJsonb(json(summary(template, beforeRemark)));
        audit.setAfterSummaryJsonb(json(summary(template, afterRemark)));
        audit.setResult("success");
        audit.setTraceId(traceId);
        audit.setOccurredAt(clock.instant());
        auditLogMapper.insert(audit);
    }

    private static Map<String, Object> summary(TemplateEntity template, String remark) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("templateCode", template.getProviderTemplateId());
        summary.put("language", template.getLanguageCode());
        summary.put("remark", remark);
        return summary;
    }

    private TemplateView view(TemplateEntity template) {
        return new TemplateView(template.getId(), template.getChannelAccountId(), template.getProviderTemplateId(),
                template.getName(), template.getRemark(),
                TemplateDisplayName.format(template.getName(), template.getRemark()),
                template.getLanguageCode(), template.getCategory(), template.getStatus(),
                template.getProviderAuditStatus(), template.getRejectionReason(),
                Boolean.TRUE.equals(template.getAllowSend()),
                WhatsAppTemplateApplicationService.desiredAllowSend(template),
                WhatsAppTemplateApplicationService.permissionSyncStatus(template),
                template.getPermissionSyncErrorMessage(), readComponents(template.getComponentsJsonb()),
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
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to read stored template components", error);
        }
    }

    private Map<String, List<String>> readExamples(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to read stored template examples", error);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize template remark audit", error);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static WhatsAppTemplateException business(String code, HttpStatus status, String message) {
        return new WhatsAppTemplateException(code, status, message, Map.of(), null, false);
    }
}
