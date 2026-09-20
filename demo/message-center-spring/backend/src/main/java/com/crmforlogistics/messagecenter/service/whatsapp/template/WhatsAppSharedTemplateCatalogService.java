package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.crmforlogistics.messagecenter.dto.response.SharedTemplateResponse;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.entity.TemplateOperationEntity;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateOperationMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService.OperationHistoryView;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class WhatsAppSharedTemplateCatalogService {
    private static final int MAX_PAGE_SIZE = 100;

    private final TemplateMapper templateMapper;
    private final TemplateOperationMapper operationMapper;
    private final ObjectMapper objectMapper;

    public WhatsAppSharedTemplateCatalogService(TemplateMapper templateMapper,
                                                TemplateOperationMapper operationMapper,
                                                ObjectMapper objectMapper) {
        this.templateMapper = Objects.requireNonNull(templateMapper);
        this.operationMapper = Objects.requireNonNull(operationMapper);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /** The library is scoped to one CAMS space; the caller decides which one. */
    public SharedTemplateResponse.Page list(UUID scopeId, int page, int size, TemplateFilters filters) {
        Objects.requireNonNull(scopeId);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw validation("page", "page must be >= 1 and size must be between 1 and 100");
        }
        TemplateFilters safe = filters == null ? new TemplateFilters(null, null, null, null, null, null) : filters;
        QueryWrapper<TemplateEntity> base = query(scopeId, safe);
        long total = templateMapper.selectCount(base);
        QueryWrapper<TemplateEntity> rows = query(scopeId, safe)
                .orderByDesc("updated_at").orderByAsc("id")
                .last("LIMIT " + size + " OFFSET " + ((page - 1L) * size));
        List<SharedTemplateResponse> items = templateMapper.selectList(rows).stream().map(this::view).toList();
        return new SharedTemplateResponse.Page(items, total, page, size);
    }

    public SharedTemplateResponse.Page listPrivate(UUID actorUserId, UUID accountId, int page, int size,
                                                   TemplateFilters filters) {
        Objects.requireNonNull(actorUserId);
        if (accountId == null) {
            throw validation("accountId", "accountId is required");
        }
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw validation("page", "page must be >= 1 and size must be between 1 and 100");
        }
        TemplateFilters safe = filters == null ? new TemplateFilters(null, null, null, null, null, null) : filters;
        QueryWrapper<TemplateEntity> base = privateQuery(accountId, safe);
        long total = templateMapper.selectCount(base);
        QueryWrapper<TemplateEntity> rows = privateQuery(accountId, safe)
                .orderByDesc("updated_at").orderByAsc("id")
                .last("LIMIT " + size + " OFFSET " + ((page - 1L) * size));
        List<SharedTemplateResponse> items = templateMapper.selectList(rows).stream().map(this::view).toList();
        return new SharedTemplateResponse.Page(items, total, page, size);
    }

    public SharedTemplateResponse privateDetail(UUID actorUserId, UUID accountId, UUID templateId) {
        Objects.requireNonNull(actorUserId);
        TemplateEntity template = templateMapper.findPrivateForDisplay(templateId, accountId).orElse(null);
        if (template == null) {
            throw notFound();
        }
        return view(template);
    }

    public SharedTemplateResponse detail(UUID scopeId, UUID templateId) {
        Objects.requireNonNull(scopeId);
        TemplateEntity template = templateMapper.selectOne(new QueryWrapper<TemplateEntity>()
                .eq("id", templateId).eq("provider_scope_id", scopeId)
                .eq("template_domain", "ENTERPRISE_API").last("LIMIT 1"));
        if (template == null) {
            throw notFound();
        }
        return view(template);
    }

    public List<OperationHistoryView> history(UUID scopeId, UUID templateId) {
        detail(scopeId, templateId);
        return operationMapper.selectList(new QueryWrapper<TemplateOperationEntity>()
                        .eq("template_id", templateId).orderByDesc("started_at").last("LIMIT 100"))
                .stream().map(WhatsAppSharedTemplateCatalogService::operationView).toList();
    }

    public List<OperationHistoryView> privateHistory(UUID actorUserId, UUID accountId, UUID templateId) {
        privateDetail(actorUserId, accountId, templateId);
        return operationMapper.selectList(new QueryWrapper<TemplateOperationEntity>()
                        .eq("template_id", templateId).eq("channel_account_id", accountId)
                        .orderByDesc("started_at").last("LIMIT 100"))
                .stream().map(WhatsAppSharedTemplateCatalogService::operationView).toList();
    }

    private QueryWrapper<TemplateEntity> query(UUID scopeId, TemplateFilters filters) {
        QueryWrapper<TemplateEntity> query = new QueryWrapper<TemplateEntity>().eq("provider_scope_id", scopeId)
                .eq("template_domain", "ENTERPRISE_API");
        if (!Boolean.TRUE.equals(filters.deleted())) query.isNull("deleted_at");
        if (filters.status() != null && !filters.status().isBlank()) query.eq("status", filters.status().trim());
        if (filters.category() != null && !filters.category().isBlank()) query.eq("category", filters.category().trim());
        if (filters.language() != null && !filters.language().isBlank()) query.eq("language_code", filters.language().trim());
        if (filters.allowSend() != null) query.eq("allow_send", filters.allowSend());
        if (filters.search() != null && !filters.search().isBlank()) {
            String value = filters.search().trim();
            query.and(nested -> nested.like("name", value).or().like("remark", value)
                    .or().like("provider_template_id", value));
        }
        return query;
    }

    private QueryWrapper<TemplateEntity> privateQuery(UUID accountId, TemplateFilters filters) {
        QueryWrapper<TemplateEntity> query = new QueryWrapper<TemplateEntity>().eq("channel_account_id", accountId)
                .eq("template_domain", "EMPLOYEE_BUSINESS_APP");
        if (!Boolean.TRUE.equals(filters.deleted())) query.isNull("deleted_at");
        if (filters.status() != null && !filters.status().isBlank()) query.eq("status", filters.status().trim());
        if (filters.category() != null && !filters.category().isBlank()) query.eq("category", filters.category().trim());
        if (filters.language() != null && !filters.language().isBlank()) query.eq("language_code", filters.language().trim());
        if (filters.allowSend() != null) query.eq("allow_send", filters.allowSend());
        if (filters.search() != null && !filters.search().isBlank()) {
            String value = filters.search().trim();
            query.and(nested -> nested.like("name", value).or().like("remark", value)
                    .or().like("provider_template_id", value));
        }
        return query;
    }

    private SharedTemplateResponse view(TemplateEntity entity) {
        return new SharedTemplateResponse(entity.getId(), entity.getVersion() == null ? 0 : entity.getVersion(),
                entity.getProviderTemplateId(), entity.getName(), entity.getRemark(),
                TemplateDisplayName.format(entity.getName(), entity.getRemark()), entity.getLanguageCode(),
                entity.getCategory(), entity.getStatus(), entity.getProviderAuditStatus(),
                entity.getRejectionReason(), Boolean.TRUE.equals(entity.getAllowSend()),
                read(entity.getComponentsJsonb(), new TypeReference<List<TemplateComponent>>() {}, List.of()),
                read(entity.getExamplesJsonb(), new TypeReference<Map<String, List<String>>>() {}, Map.of()),
                entity.getMessageSendTtlSeconds(), entity.getQualityScore(), entity.getProviderUpdatedAt(),
                entity.getLastSyncedAt(), entity.getDeletedAt());
    }

    private <T> T read(String json, TypeReference<T> type, T empty) {
        if (json == null || json.isBlank()) return empty;
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Stored WhatsApp template projection is invalid", error);
        }
    }

    private static OperationHistoryView operationView(TemplateOperationEntity value) {
        return new OperationHistoryView(value.getId(), value.getOperationType(), value.getOperationStatus(),
                value.getProviderTemplateId(), value.getLanguageCode(), value.getProviderRequestId(),
                value.getErrorCode(), value.getErrorMessage(), value.getTraceId(), value.getActorUserId(),
                value.getStartedAt(), value.getCompletedAt());
    }

    private static WhatsAppTemplateException validation(String field, String message) {
        return new WhatsAppTemplateException("TEMPLATE_VALIDATION_FAILED", HttpStatus.BAD_REQUEST,
                "WhatsApp template query is invalid", Map.of(field, message), null, false);
    }

    private static WhatsAppTemplateException notFound() {
        return new WhatsAppTemplateException("WHATSAPP_TEMPLATE_NOT_FOUND", HttpStatus.NOT_FOUND,
                "WhatsApp template not found", Map.of(), null, false);
    }

    public record TemplateFilters(String search, String status, String category, String language,
                                  Boolean allowSend, Boolean deleted) { }
}
