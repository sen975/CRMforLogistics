package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateChangeRequestMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side aggregate behind the admin home page. The browser must not infer accounts,
 * templates or approval state, so every number here is computed from bounded SQL aggregates.
 */
@Service
public class AdminWhatsAppWorkbenchService {
    private final WhatsAppProviderScopeMapper scopes;
    private final ChannelAccountMapper accounts;
    private final TemplateChangeRequestMapper changeRequests;

    public AdminWhatsAppWorkbenchService(WhatsAppProviderScopeMapper scopes,
                                         ChannelAccountMapper accounts,
                                         TemplateChangeRequestMapper changeRequests) {
        this.scopes = scopes;
        this.accounts = accounts;
        this.changeRequests = changeRequests;
    }

    @Transactional(readOnly = true)
    public OverviewView overview() {
        Map<UUID, Long> usableByScope = usableAccountsByScope();
        Map<UUID, Long> pendingByScope = pendingApprovalsByScope();
        List<WhatsAppProviderScopeEntity> configured = scopes.findAllAdminScopes();
        List<ScopeOverview> projections = (configured == null ? List.<WhatsAppProviderScopeEntity>of() : configured)
                .stream()
                .map(scope -> project(scope, usableByScope, pendingByScope))
                .toList();
        // Derived from the projections, so total == sum(scopes[].pendingApprovalCount) by construction.
        long total = projections.stream().mapToLong(ScopeOverview::pendingApprovalCount).sum();
        return new OverviewView(projections, total);
    }

    private Map<UUID, Long> usableAccountsByScope() {
        Map<UUID, Long> counts = new HashMap<>();
        List<ChannelAccountMapper.UsableAccountCountRow> rows = accounts.countUsableAccountsByScope();
        if (rows == null) return counts;
        for (ChannelAccountMapper.UsableAccountCountRow row : rows) {
            if (row.scopeId() != null) counts.put(row.scopeId(), row.total());
        }
        return counts;
    }

    private Map<UUID, Long> pendingApprovalsByScope() {
        Map<UUID, Long> counts = new HashMap<>();
        List<TemplateChangeRequestMapper.PendingApprovalCountRow> rows = changeRequests.countPendingApprovalsByScope();
        if (rows == null) return counts;
        for (TemplateChangeRequestMapper.PendingApprovalCountRow row : rows) {
            // A change request whose template has a null provider_scope_id belongs to no scope: it is
            // dropped here so it can neither surface on a scope nor inflate the total.
            if (row.scopeId() != null) counts.put(row.scopeId(), row.total());
        }
        return counts;
    }

    private static ScopeOverview project(WhatsAppProviderScopeEntity scope,
                                         Map<UUID, Long> usableByScope,
                                         Map<UUID, Long> pendingByScope) {
        UUID scopeId = scope.getId();
        return new ScopeOverview(scopeId, displayName(scope), scope.getExternalScopeId(), scope.getStatus(),
                usableByScope.getOrDefault(scopeId, 0L), pendingByScope.getOrDefault(scopeId, 0L),
                scope.getLastTestedAt(), scope.getLastTestStatus(), scope.getLastTestErrorCode(),
                scope.getLastSyncedAt(), scope.getLastSyncStatus(), scope.getLastSyncErrorCode());
    }

    private static String displayName(WhatsAppProviderScopeEntity scope) {
        String displayName = scope.getDisplayName();
        return displayName == null || displayName.isBlank() ? scope.getExternalScopeId() : displayName;
    }

    public record OverviewView(List<ScopeOverview> scopes, long totalPendingApprovalCount) { }

    public record ScopeOverview(UUID scopeId, String displayName, String custSpaceId, String status,
                                long usableAccountCount, long pendingApprovalCount,
                                Instant lastTestedAt, String lastTestStatus, String lastTestErrorCode,
                                Instant lastSyncedAt, String lastSyncStatus, String lastSyncErrorCode) { }
}
