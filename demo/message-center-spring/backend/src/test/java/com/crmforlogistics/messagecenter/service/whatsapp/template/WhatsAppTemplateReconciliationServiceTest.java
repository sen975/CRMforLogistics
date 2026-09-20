package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.entity.TemplateOperationEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateChangeRequestMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateOperationMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ProviderTemplatePage;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ProviderTemplateSummary;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ReviewStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateSnapshot;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateReconciliationService.SyncResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppTemplateReconciliationServiceTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID SCOPE_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final Instant NOW = Instant.parse("2026-08-11T02:00:00Z");

    @Mock WhatsAppTemplateGateway gateway;
    @Mock ChannelAccountMapper accountMapper;
    @Mock TemplateMapper templateMapper;
    @Mock TemplateOperationMapper operationMapper;
    @Mock TemplateChangeRequestMapper changeRequestMapper;
    @Mock TemplateMediaAssetMapper mediaMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WhatsAppTemplateReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new WhatsAppTemplateReconciliationService(gateway, accountMapper, templateMapper,
                operationMapper, changeRequestMapper, mediaMapper, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void scopeSyncPersistsTheProviderSnapshotInThatSharedScope() {
        TemplateEntity existing = new TemplateEntity();
        existing.setId(UUID.randomUUID());
        existing.setProviderScopeId(SCOPE_ID);
        existing.setProviderTemplateId("shipping_notice");
        existing.setLanguageCode("zh_CN");
        existing.setRemark("共享备注");
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(accountIn(SCOPE_ID));
        when(templateMapper.findScopeTemplates(SCOPE_ID)).thenReturn(List.of(existing));
        when(gateway.list(TemplateCredentialSource.space(SCOPE_ID), 1, 100)).thenReturn(
                new ProviderTemplatePage(List.of(summary("shipping_notice")), 1, false));
        when(gateway.detail(TemplateCredentialSource.space(SCOPE_ID), "shipping_notice", "zh_CN"))
                .thenReturn(Optional.of(snapshot("shipping_notice")));

        var result = service.syncScope(SCOPE_ID, ACCOUNT_ID);

        assertThat(result.complete()).isTrue();
        ArgumentCaptor<TemplateEntity> captured = ArgumentCaptor.forClass(TemplateEntity.class);
        verify(templateMapper).upsertShared(captured.capture());
        assertThat(captured.getValue().getProviderScopeId()).isEqualTo(SCOPE_ID);
        assertThat(captured.getValue().getChannelAccountId()).isNull();
        assertThat(captured.getValue().getTemplateDomain()).isEqualTo("ENTERPRISE_API");
        assertThat(captured.getValue().getRemark()).isEqualTo("共享备注");
    }

    @Test
    void scopeSyncRejectsACredentialAccountFromAnotherScope() {
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(accountIn(UUID.randomUUID()));

        assertThatThrownBy(() -> service.syncScope(SCOPE_ID, ACCOUNT_ID))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("WHATSAPP_PROVIDER_SCOPE_MISMATCH");

        verify(gateway, never()).list(any(), anyInt(), anyInt());
    }

    @Test
    void concurrentSyncsForTheSameScopeShareOneProviderFlight() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(accountIn(SCOPE_ID));
        when(templateMapper.findScopeTemplates(SCOPE_ID)).thenReturn(List.of());
        when(gateway.list(TemplateCredentialSource.space(SCOPE_ID), 1, 100)).thenAnswer(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return new ProviderTemplatePage(List.of(), 1, false);
        });

        AtomicReference<SyncResult> leaderResult = new AtomicReference<>();
        AtomicReference<SyncResult> followerResult = new AtomicReference<>();
        Thread leader = new Thread(() -> leaderResult.set(service.syncScope(SCOPE_ID, ACCOUNT_ID)));
        Thread follower = new Thread(() -> followerResult.set(service.syncScope(SCOPE_ID, ACCOUNT_ID)));

        leader.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        follower.start();
        try {
            // The leader cannot leave the flight until the line below releases it, so the follower
            // either parks on that flight or starts a fetch of its own. Both are terminal outcomes,
            // and waiting for one of them keeps how fast the thread starts out of the verdict.
            awaitFollower(follower, calls);
            release.countDown();

            leader.join(TimeUnit.SECONDS.toMillis(2));
            follower.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(calls.get()).isEqualTo(1);
            assertThat(followerResult.get()).isSameAs(leaderResult.get());
            verify(gateway, times(1)).list(TemplateCredentialSource.space(SCOPE_ID), 1, 100);
        } finally {
            release.countDown();
        }
    }

    /** A follower either parks on the leader's in-flight sync or starts a provider fetch of its own. */
    private static void awaitFollower(Thread follower, AtomicInteger providerCalls) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (providerCalls.get() > 1 || follower.getState() == Thread.State.WAITING) {
                return;
            }
            Thread.sleep(2);
        }
    }

    @Test
    void unknownOperationResolvesItsScopeFromItsOriginalCredentialAccount() {
        TemplateOperationEntity operation = new TemplateOperationEntity();
        operation.setId(UUID.randomUUID());
        operation.setChannelAccountId(ACCOUNT_ID);
        operation.setOperationType("DELETE");
        operation.setOperationStatus("SUBMISSION_UNKNOWN");
        operation.setProviderTemplateId("shipping_notice");
        operation.setLanguageCode("zh_CN");
        operation.setReconcileAttemptCount(0);
        operation.setStartedAt(NOW.minusSeconds(60));
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(ACCOUNT_ID);
        account.setProviderScopeId(SCOPE_ID);
        TemplateEntity template = new TemplateEntity();
        template.setId(UUID.randomUUID());
        template.setProviderScopeId(SCOPE_ID);
        template.setAllowSend(true);
        when(operationMapper.claimUnknown("worker-1", NOW, NOW.plusSeconds(120), 20))
                .thenReturn(List.of(operation));
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(account);
        when(gateway.list(TemplateCredentialSource.space(SCOPE_ID), 1, 100))
                .thenReturn(new ProviderTemplatePage(List.of(), 1, false));
        when(templateMapper.findSharedForDisplay(SCOPE_ID, "shipping_notice", "zh_CN"))
                .thenReturn(Optional.of(template));

        assertThat(service.reconcileUnknown("worker-1")).isEqualTo(1);

        verify(templateMapper).findSharedForDisplay(SCOPE_ID, "shipping_notice", "zh_CN");
        assertThat(template.getDeletedAt()).isEqualTo(NOW);
    }

    @Test
    void createReconciliationIgnoresATemplateTheProviderHeldBeforeTheSubmission() throws Exception {
        TemplateOperationEntity operation = createOperation("Welcome", "MARKETING", NOW.minusSeconds(60));
        when(operationMapper.claimUnknown("worker-1", NOW, NOW.plusSeconds(120), 20))
                .thenReturn(List.of(operation));
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(accountIn(SCOPE_ID));
        when(gateway.list(TemplateCredentialSource.space(SCOPE_ID), 1, 100)).thenReturn(
                new ProviderTemplatePage(List.of(new ProviderTemplateSummary("welcome_001", "Welcome", "zh_CN",
                        "MARKETING", "pass", null, NOW.minusSeconds(86_400))), 1, false));

        assertThat(service.reconcileUnknown("worker-1")).isZero();

        verify(operationMapper).markUnknown(eq(operation.getId()), eq("RECONCILIATION_NOT_CONFIRMED"),
                eq("Provider template is absent"), any());
        verify(operationMapper, never()).markSucceeded(any(), any(), any(), any());
        verify(gateway, never()).detail(any(), any(), any());
    }

    @Test
    void createReconciliationIgnoresATemplateTheSubmissionDidNotAskForTheCategoryOf() throws Exception {
        TemplateOperationEntity operation = createOperation("Welcome", "MARKETING", NOW.minusSeconds(60));
        when(operationMapper.claimUnknown("worker-1", NOW, NOW.plusSeconds(120), 20))
                .thenReturn(List.of(operation));
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(accountIn(SCOPE_ID));
        when(gateway.list(TemplateCredentialSource.space(SCOPE_ID), 1, 100)).thenReturn(
                new ProviderTemplatePage(List.of(new ProviderTemplateSummary("welcome_001", "Welcome", "zh_CN",
                        "UTILITY", "pass", null, NOW)), 1, false));

        assertThat(service.reconcileUnknown("worker-1")).isZero();

        verify(operationMapper, never()).markSucceeded(any(), any(), any(), any());
    }

    @Test
    void createReconciliationAdoptsATemplateTheProviderPublishedAfterTheSubmission() throws Exception {
        TemplateOperationEntity operation = createOperation("Welcome", "MARKETING", NOW.minusSeconds(60));
        when(operationMapper.claimUnknown("worker-1", NOW, NOW.plusSeconds(120), 20))
                .thenReturn(List.of(operation));
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(accountIn(SCOPE_ID));
        when(gateway.list(TemplateCredentialSource.space(SCOPE_ID), 1, 100)).thenReturn(
                new ProviderTemplatePage(List.of(new ProviderTemplateSummary("welcome_001", "Welcome", "zh_CN",
                        "MARKETING", "pass", null, NOW)), 1, false));
        when(gateway.detail(TemplateCredentialSource.space(SCOPE_ID), "welcome_001", "zh_CN"))
                .thenReturn(Optional.of(snapshot("welcome_001")));

        assertThat(service.reconcileUnknown("worker-1")).isEqualTo(1);

        ArgumentCaptor<TemplateEntity> captured = ArgumentCaptor.forClass(TemplateEntity.class);
        verify(templateMapper).upsertShared(captured.capture());
        assertThat(captured.getValue().getProviderScopeId()).isEqualTo(SCOPE_ID);
        assertThat(captured.getValue().getChannelAccountId()).isNull();
        verify(operationMapper).markSucceeded(operation.getId(), "welcome_001", null, NOW);
    }

    private TemplateOperationEntity createOperation(String name, String category, Instant startedAt)
            throws Exception {
        TemplateOperationEntity operation = new TemplateOperationEntity();
        operation.setId(UUID.randomUUID());
        operation.setChannelAccountId(ACCOUNT_ID);
        operation.setOperationType("CREATE");
        operation.setOperationStatus("SUBMISSION_UNKNOWN");
        operation.setLanguageCode("zh_CN");
        operation.setReconcileAttemptCount(0);
        operation.setStartedAt(startedAt);
        operation.setRequestedSnapshotJsonb(objectMapper.writeValueAsString(
                new TemplateCommand(name, "zh_CN", category, List.of(), Map.of(), null, "request-1")));
        return operation;
    }

    private static ChannelAccountEntity accountIn(UUID scopeId) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(ACCOUNT_ID);
        account.setProviderScopeId(scopeId);
        return account;
    }

    private static ProviderTemplateSummary summary(String code) {
        return new ProviderTemplateSummary(code, "Shipping Notice", "zh_CN", "UTILITY", "pass", null, NOW);
    }

    private static TemplateSnapshot snapshot(String code) {
        return new TemplateSnapshot(code, "Shipping Notice", "zh_CN", "UTILITY",
                ReviewStatus.APPROVED, "pass", null, true,
                List.of(new TemplateComponent(ComponentType.BODY, null, "订单 $(order) 已发货", null, List.of())),
                Map.of(), null, NOW, null);
    }
}
