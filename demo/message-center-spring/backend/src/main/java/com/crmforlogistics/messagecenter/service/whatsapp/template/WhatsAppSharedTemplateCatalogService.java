package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.crmforlogistics.messagecenter.dto.response.SharedTemplateResponse;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.entity.TemplateOperationEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateOperationMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAccountMode;
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
    private final ChannelAccountMapper accountMapper;

    public WhatsAppSharedTemplateCatalogService(TemplateMapper templateMapper,
                                                TemplateOperationMapper operationMapper,
                                                ObjectMapper objectMapper,
                                                ChannelAccountMapper accountMapper) {
        this.templateMapper = Objects.requireNonNull(templateMapper);
        this.operationMapper = Objects.requireNonNull(operationMapper);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.accountMapper = Objects.requireNonNull(accountMapper);
    }

    /** The library is scoped to one CAMS space; the caller decides which one. */
    public SharedTemplateResponse.Page list(UUID scopeId, int page, int size, TemplateFilters filters) {
        Objects.requireNonNull(scopeId);
        requirePage(page, size);
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
        requirePage(page, size);
        TemplateFilters safe = filters == null ? new TemplateFilters(null, null, null, null, null, null) : filters;
        QueryWrapper<TemplateEntity> base = privateQuery(accountId, safe);
        long total = templateMapper.selectCount(base);
        QueryWrapper<TemplateEntity> rows = privateQuery(accountId, safe)
                .orderByDesc("updated_at").orderByAsc("id")
                .last("LIMIT " + size + " OFFSET " + ((page - 1L) * size));
        List<SharedTemplateResponse> items = templateMapper.selectList(rows).stream().map(this::view).toList();
        return new SharedTemplateResponse.Page(items, total, page, size);
    }

    /**
     * 一个账号的模板库：<b>私有域读账号自己的，企业 API 域读它所在的那个 CAMS 空间</b>。
     *
     * <h2>为什么「看模板」也要分域（2026-09-29）</h2>
     * 两种绑定把模板放在两个地方：Business App 的模板是<b>账号私有</b>的
     * （{@code channel_account_id}），企业 API 的模板是<b>整个 CAMS 空间共享</b>的
     * （{@code provider_scope_id}）。拿错那把钥匙不会报错 —— 它只是安静地返回 0 条：
     * 私有查询对企业 API 账号恒空（那些行没有 {@code channel_account_id}），
     * 空间查询对 Business App 账号也恒空（那些行不是 {@code ENTERPRISE_API} 域）。
     *
     * <p>于是「助手说这个账号一个模板都没有」与「这个账号真的没有模板」在界面上长得一模一样，
     * 而前者会让用户去重复申请一个已经存在的模板名 —— 平台按重名拒。所以分域这件事
     * 必须落在这里，而不是靠调用方记得挑对方法。
     *
     * <h2>账号未绑空间时返回空页，而不是抛错</h2>
     * 本方法是「看一眼」的路径，一个账号读不出来不该让整份清单消失。判据与
     * {@code ChatAppAccountCandidates.Item#templateScopeReady} 同源
     * （{@code provider_scope_id != null}）：调用方据此能把「读不出来」讲成一句有下一步的话，
     * 而不是把它讲成「你没有模板」。
     */
    public SharedTemplateResponse.Page listForAccount(UUID actorUserId, UUID accountId, int page, int size,
                                                     TemplateFilters filters) {
        Objects.requireNonNull(actorUserId);
        ChannelAccountEntity account = accountId == null
                ? null : accountMapper.findByIdAndOwner(accountId, actorUserId);
        if (account == null) {
            // 与 findByIdAndOwner 的既有口径一致：不区分「不存在」与「不属于你」。
            throw notFound();
        }
        if (WhatsAppAccountMode.isBusinessApp(account.getOnboardingMode())) {
            return listPrivate(actorUserId, accountId, page, size, filters);
        }
        UUID scopeId = account.getProviderScopeId();
        if (scopeId == null) {
            // 仍然走一次分页判据：未绑空间不是「参数可以随便给」的理由。
            requirePage(page, size);
            return new SharedTemplateResponse.Page(List.of(), 0, page, size);
        }
        return list(scopeId, page, size, filters);
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

    private static void requirePage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw validation("page", "page must be >= 1 and size must be between 1 and 100");
        }
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
