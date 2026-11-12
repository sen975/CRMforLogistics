package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateChangeRequestMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminWhatsAppWorkbenchServiceTest {
    private static final UUID SCOPE_A = UUID.fromString("a0000000-0000-0000-0000-00000000000a");
    private static final UUID SCOPE_B = UUID.fromString("b0000000-0000-0000-0000-00000000000b");
    private static final Instant TESTED_AT = Instant.parse("2026-09-15T02:00:00Z");
    private static final Instant SYNCED_AT = Instant.parse("2026-09-15T03:00:00Z");

    @Test
    void aggregatesEachScopeFromItsOwnRowsAndSumsOnlyThePendingApprovals() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        TemplateChangeRequestMapper changeRequests = mock(TemplateChangeRequestMapper.class);
        when(scopes.findAllAdminScopes())
                .thenReturn(List.of(scope(SCOPE_A, "华东 CAMS", "cust-space-1"), scope(SCOPE_B, "华南 CAMS", "cust-space-2")));
        when(accounts.countUsableAccountsByScope()).thenReturn(List.of(
                new ChannelAccountMapper.UsableAccountCountRow(SCOPE_A, 2L),
                new ChannelAccountMapper.UsableAccountCountRow(SCOPE_B, 3L)));
        when(changeRequests.countPendingApprovalsByScope()).thenReturn(List.of(
                new TemplateChangeRequestMapper.PendingApprovalCountRow(SCOPE_A, 2L)));

        AdminWhatsAppWorkbenchService.OverviewView view = service(scopes, accounts, changeRequests).overview();

        assertThat(view.scopes()).hasSize(2);
        assertThat(view.scopes().get(0)).isEqualTo(new AdminWhatsAppWorkbenchService.ScopeOverview(
                SCOPE_A, "华东 CAMS", "cust-space-1", "READY", 2L, 2L,
                TESTED_AT, "SUCCEEDED", null, SYNCED_AT, "SUCCEEDED", null));
        assertThat(view.scopes().get(1)).isEqualTo(new AdminWhatsAppWorkbenchService.ScopeOverview(
                SCOPE_B, "华南 CAMS", "cust-space-2", "READY", 3L, 0L,
                TESTED_AT, "SUCCEEDED", null, SYNCED_AT, "SUCCEEDED", null));
        assertThat(view.totalPendingApprovalCount()).isEqualTo(2L);
        verify(accounts).countUsableAccountsByScope();
        verify(changeRequests).countPendingApprovalsByScope();
    }

    @Test
    void scopeWithoutAnyRowsStillAppearsWithZeroCounts() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        TemplateChangeRequestMapper changeRequests = mock(TemplateChangeRequestMapper.class);
        when(scopes.findAllAdminScopes()).thenReturn(List.of(scope(SCOPE_B, "华南 CAMS", "cust-space-2")));
        when(accounts.countUsableAccountsByScope()).thenReturn(List.of());
        when(changeRequests.countPendingApprovalsByScope()).thenReturn(List.of());

        AdminWhatsAppWorkbenchService.OverviewView view = service(scopes, accounts, changeRequests).overview();

        assertThat(view.scopes()).singleElement().satisfies(projection -> {
            assertThat(projection.scopeId()).isEqualTo(SCOPE_B);
            assertThat(projection.usableAccountCount()).isZero();
            assertThat(projection.pendingApprovalCount()).isZero();
        });
        assertThat(view.totalPendingApprovalCount()).isZero();
    }

    @Test
    void zeroConfiguredScopesReturnsAnEmptyOverviewInsteadOfFailing() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        TemplateChangeRequestMapper changeRequests = mock(TemplateChangeRequestMapper.class);
        when(scopes.findAllAdminScopes()).thenReturn(List.of());
        when(accounts.countUsableAccountsByScope()).thenReturn(List.of());
        when(changeRequests.countPendingApprovalsByScope()).thenReturn(List.of());

        AdminWhatsAppWorkbenchService.OverviewView view = service(scopes, accounts, changeRequests).overview();

        assertThat(view.scopes()).isEmpty();
        assertThat(view.totalPendingApprovalCount()).isZero();
    }

    @Test
    void pendingRequestOutsideEveryScopeIsDroppedAndCannotInflateTheTotal() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        TemplateChangeRequestMapper changeRequests = mock(TemplateChangeRequestMapper.class);
        when(scopes.findAllAdminScopes())
                .thenReturn(List.of(scope(SCOPE_A, "华东 CAMS", "cust-space-1"), scope(SCOPE_B, "华南 CAMS", "cust-space-2")));
        when(accounts.countUsableAccountsByScope()).thenReturn(List.of(
                new ChannelAccountMapper.UsableAccountCountRow(SCOPE_A, 1L)));
        // The null row is the aggregate group for templates whose provider_scope_id is null: no scope owns it.
        when(changeRequests.countPendingApprovalsByScope()).thenReturn(List.of(
                new TemplateChangeRequestMapper.PendingApprovalCountRow(SCOPE_A, 2L),
                new TemplateChangeRequestMapper.PendingApprovalCountRow(null, 1L)));

        AdminWhatsAppWorkbenchService.OverviewView view = service(scopes, accounts, changeRequests).overview();

        assertThat(view.scopes()).extracting(AdminWhatsAppWorkbenchService.ScopeOverview::pendingApprovalCount)
                .containsExactly(2L, 0L);
        long summed = view.scopes().stream()
                .mapToLong(AdminWhatsAppWorkbenchService.ScopeOverview::pendingApprovalCount).sum();
        assertThat(view.totalPendingApprovalCount()).isEqualTo(2L);
        assertThat(view.totalPendingApprovalCount()).isEqualTo(summed);
    }

    private static AdminWhatsAppWorkbenchService service(WhatsAppProviderScopeMapper scopes,
                                                         ChannelAccountMapper accounts,
                                                         TemplateChangeRequestMapper changeRequests) {
        return new AdminWhatsAppWorkbenchService(scopes, accounts, changeRequests);
    }

    private static WhatsAppProviderScopeEntity scope(UUID id, String displayName, String custSpaceId) {
        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setId(id);
        scope.setProvider("ALIYUN_CAMS");
        scope.setScopeType("ENTERPRISE_API");
        scope.setDisplayName(displayName);
        scope.setExternalScopeId(custSpaceId);
        scope.setStatus("READY");
        scope.setLastTestedAt(TESTED_AT);
        scope.setLastTestStatus("SUCCEEDED");
        scope.setLastSyncedAt(SYNCED_AT);
        scope.setLastSyncStatus("SUCCEEDED");
        return scope;
    }
}
