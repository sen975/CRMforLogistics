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
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateSnapshot;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    private WhatsAppTemplateReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new WhatsAppTemplateReconciliationService(gateway, accountMapper, templateMapper,
                operationMapper, changeRequestMapper, mediaMapper, new ObjectMapper(),
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
        when(templateMapper.findScopeTemplates(SCOPE_ID)).thenReturn(List.of(existing));
        when(gateway.list(ACCOUNT_ID, 1, 100)).thenReturn(new ProviderTemplatePage(
                List.of(summary("shipping_notice")), 1, false));
        when(gateway.detail(ACCOUNT_ID, "shipping_notice", "zh_CN"))
                .thenReturn(Optional.of(snapshot("shipping_notice")));

        var result = service.syncScope(SCOPE_ID, ACCOUNT_ID);

        assertThat(result.complete()).isTrue();
        ArgumentCaptor<TemplateEntity> captured = ArgumentCaptor.forClass(TemplateEntity.class);
        verify(templateMapper).upsertShared(captured.capture());
        assertThat(captured.getValue().getProviderScopeId()).isEqualTo(SCOPE_ID);
        assertThat(captured.getValue().getChannelAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(captured.getValue().getRemark()).isEqualTo("共享备注");
    }

    @Test
    void concurrentSyncsForTheSameScopeShareOneProviderFlight() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(templateMapper.findScopeTemplates(SCOPE_ID)).thenReturn(List.of());
        when(gateway.list(ACCOUNT_ID, 1, 100)).thenAnswer(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return new ProviderTemplatePage(List.of(), 1, false);
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.syncScope(SCOPE_ID, ACCOUNT_ID));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.syncScope(SCOPE_ID, ACCOUNT_ID));
            assertThat(calls.get()).isEqualTo(1);
            release.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo(second.get(2, TimeUnit.SECONDS));
            verify(gateway, times(1)).list(ACCOUNT_ID, 1, 100);
        } finally {
            release.countDown();
            executor.shutdownNow();
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
        when(gateway.list(ACCOUNT_ID, 1, 100)).thenReturn(new ProviderTemplatePage(List.of(), 1, false));
        when(templateMapper.findSharedForDisplay(SCOPE_ID, "shipping_notice", "zh_CN"))
                .thenReturn(Optional.of(template));

        assertThat(service.reconcileUnknown("worker-1")).isEqualTo(1);

        verify(templateMapper).findSharedForDisplay(SCOPE_ID, "shipping_notice", "zh_CN");
        assertThat(template.getDeletedAt()).isEqualTo(NOW);
    }

    private static ProviderTemplateSummary summary(String code) {
        return new ProviderTemplateSummary(code, "Shipping Notice", "zh_CN", "UTILITY", "pass", null, NOW);
    }

    private static TemplateSnapshot snapshot(String code) {
        return new TemplateSnapshot(ACCOUNT_ID, code, "Shipping Notice", "zh_CN", "UTILITY",
                ReviewStatus.APPROVED, "pass", null, true,
                List.of(new TemplateComponent(ComponentType.BODY, null, "订单 $(order) 已发货", null, List.of())),
                Map.of(), null, NOW, null);
    }
}
