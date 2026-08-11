package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import com.crmforlogistics.messagecenter.entity.TemplateOperationEntity;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateOperationMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ProviderTemplatePage;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ProviderTemplateSummary;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ReviewStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateCommand;
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
import java.util.ArrayList;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppTemplateReconciliationServiceTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-08-11T02:00:00Z");

    @Mock private WhatsAppTemplateGateway gateway;
    @Mock private TemplateMapper templateMapper;
    @Mock private TemplateOperationMapper operationMapper;
    @Mock private TemplateMediaAssetMapper mediaMapper;

    private ObjectMapper objectMapper;
    private WhatsAppTemplateReconciliationService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new WhatsAppTemplateReconciliationService(gateway, templateMapper, operationMapper,
                mediaMapper, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void syncIsBoundedToTwentyPagesAndTwoThousandItems() {
        when(gateway.list(eq(ACCOUNT_ID), any(Integer.class), eq(100)))
                .thenAnswer(invocation -> {
                    int pageNumber = invocation.getArgument(1);
                    List<ProviderTemplateSummary> page = new ArrayList<>();
                    for (int i = 0; i < 100; i++) {
                        int index = (pageNumber - 1) * 100 + i;
                        page.add(summary("tpl-" + index, "name-" + index, "pass"));
                    }
                    return new ProviderTemplatePage(page, pageNumber, true);
                });
        when(gateway.detail(eq(ACCOUNT_ID), any(), eq("en_US"))).thenAnswer(invocation ->
                Optional.of(snapshot(invocation.getArgument(1), "name", ReviewStatus.APPROVED, true, null)));
        when(templateMapper.selectList(any())).thenReturn(List.of());

        var result = service.syncAccount(ACCOUNT_ID);

        assertThat(result.pages()).isEqualTo(20);
        assertThat(result.fetched()).isEqualTo(2_000);
        verify(gateway, times(20)).list(eq(ACCOUNT_ID), any(Integer.class), eq(100));
    }

    @Test
    void concurrentSyncsForTheSameAccountShareOneProviderFlight() throws Exception {
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger providerCalls = new AtomicInteger();
        when(gateway.list(ACCOUNT_ID, 1, 100)).thenAnswer(invocation -> {
            providerCalls.incrementAndGet();
            providerEntered.countDown();
            releaseProvider.await(2, TimeUnit.SECONDS);
            return new ProviderTemplatePage(List.of(), 1, false);
        });
        when(templateMapper.selectList(any())).thenReturn(List.of());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                bothReady.countDown();
                start.await();
                return service.syncAccount(ACCOUNT_ID);
            });
            var second = executor.submit(() -> {
                bothReady.countDown();
                start.await();
                return service.syncAccount(ACCOUNT_ID);
            });
            assertThat(bothReady.await(1, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(providerEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(providerCalls.get()).isEqualTo(1);
            releaseProvider.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo(second.get(2, TimeUnit.SECONDS));
            assertThat(providerCalls.get()).isEqualTo(1);
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void listFailureRetainsPreviousSnapshotAndNeverMarksMissingTemplatesDeleted() {
        when(gateway.list(ACCOUNT_ID, 1, 100)).thenThrow(providerFailure());

        var result = service.syncAccount(ACCOUNT_ID);

        assertThat(result.complete()).isFalse();
        verify(templateMapper, never()).updateById(any(TemplateEntity.class));
    }

    @Test
    void emptyPageClaimingMorePagesIsIncompleteAndCannotDeleteLocalTemplates() {
        TemplateEntity existing = template("tpl-old", "APPROVED", "[]");
        when(templateMapper.selectList(any())).thenReturn(List.of(existing));
        when(gateway.list(ACCOUNT_ID, 1, 100))
                .thenReturn(new ProviderTemplatePage(List.of(), 1, true));

        var result = service.syncAccount(ACCOUNT_ID);

        assertThat(result.complete()).isFalse();
        assertThat(existing.getDeletedAt()).isNull();
        verify(templateMapper, never()).updateById(existing);
    }

    @Test
    void detailFailureRetainsComponentsButUpdatesConfirmedReviewStatus() {
        TemplateEntity existing = template("tpl-1", "APPROVED", "[{\"type\":\"BODY\",\"text\":\"old\"}]");
        when(templateMapper.selectList(any())).thenReturn(List.of(existing));
        when(gateway.list(ACCOUNT_ID, 1, 100))
                .thenReturn(new ProviderTemplatePage(List.of(summary("tpl-1", "delivery", "fail")), 1, false));
        when(gateway.detail(ACCOUNT_ID, "tpl-1", "en_US")).thenThrow(providerFailure());

        service.syncAccount(ACCOUNT_ID);

        ArgumentCaptor<TemplateEntity> updated = ArgumentCaptor.forClass(TemplateEntity.class);
        verify(templateMapper).updateById(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo("REJECTED");
        assertThat(updated.getValue().getComponentsJsonb()).contains("old");
    }

    @Test
    void mismatchedDetailCannotMoveAProjectionAcrossAccounts() {
        UUID otherAccount = UUID.randomUUID();
        TemplateEntity existing = template("tpl-1", "APPROVED", "[{\"type\":\"BODY\",\"text\":\"old\"}]");
        when(templateMapper.selectList(any())).thenReturn(List.of(existing));
        when(gateway.list(ACCOUNT_ID, 1, 100))
                .thenReturn(new ProviderTemplatePage(List.of(summary("tpl-1", "delivery", "fail")), 1, false));
        TemplateSnapshot mismatched = new TemplateSnapshot(otherAccount, "other-code", "delivery", "zh_CN",
                "UTILITY", ReviewStatus.APPROVED, "pass", null, true, List.of(), Map.of(), null, NOW, null);
        when(gateway.detail(ACCOUNT_ID, "tpl-1", "en_US")).thenReturn(Optional.of(mismatched));

        service.syncAccount(ACCOUNT_ID);

        ArgumentCaptor<TemplateEntity> updated = ArgumentCaptor.forClass(TemplateEntity.class);
        verify(templateMapper).updateById(updated.capture());
        assertThat(updated.getValue().getChannelAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(updated.getValue().getProviderTemplateId()).isEqualTo("tpl-1");
        assertThat(updated.getValue().getLanguageCode()).isEqualTo("en_US");
        assertThat(updated.getValue().getStatus()).isEqualTo("REJECTED");
        assertThat(updated.getValue().getComponentsJsonb()).contains("old");
    }

    @Test
    void duplicateProviderKeysAreProcessedOnceAndMakeTheSnapshotIncomplete() {
        ProviderTemplateSummary duplicate = summary("tpl-1", "delivery", "pass");
        when(templateMapper.selectList(any())).thenReturn(List.of());
        when(gateway.list(ACCOUNT_ID, 1, 100))
                .thenReturn(new ProviderTemplatePage(List.of(duplicate, duplicate), 1, false));
        when(gateway.detail(ACCOUNT_ID, "tpl-1", "en_US"))
                .thenReturn(Optional.of(snapshot("tpl-1", "delivery", ReviewStatus.APPROVED, true, null)));

        var result = service.syncAccount(ACCOUNT_ID);

        assertThat(result.complete()).isFalse();
        verify(gateway).detail(ACCOUNT_ID, "tpl-1", "en_US");
        verify(templateMapper).insert(any(TemplateEntity.class));
    }

    @Test
    void onlyCompletePaginationCanMarkMissingProviderTemplatesDeleted() {
        TemplateEntity present = template("tpl-present", "APPROVED", "[]");
        TemplateEntity missing = template("tpl-missing", "APPROVED", "[]");
        when(templateMapper.selectList(any())).thenReturn(List.of(present, missing));
        when(gateway.list(ACCOUNT_ID, 1, 100))
                .thenReturn(new ProviderTemplatePage(List.of(summary("tpl-present", "present", "pass")), 1, false));
        when(gateway.detail(ACCOUNT_ID, "tpl-present", "en_US"))
                .thenReturn(Optional.of(snapshot("tpl-present", "present", ReviewStatus.APPROVED, true, null)));

        service.syncAccount(ACCOUNT_ID);

        assertThat(missing.getDeletedAt()).isEqualTo(NOW);
        verify(templateMapper).updateById(missing);
    }

    @Test
    void uniqueUnknownCreateResolvesAndAttachesReferencedMedia() throws Exception {
        UUID assetId = UUID.randomUUID();
        TemplateMediaAssetEntity asset = media(assetId, "ATTACHMENT_UNKNOWN");
        TemplateOperationEntity operation = unknownCreate(assetId, "delivery");
        when(operationMapper.claimUnknown("worker-1", NOW, NOW.plusSeconds(120), 20))
                .thenReturn(List.of(operation));
        when(gateway.list(ACCOUNT_ID, 1, 100))
                .thenReturn(new ProviderTemplatePage(List.of(summary("tpl-1", "delivery", "pass")), 1, false));
        when(gateway.detail(ACCOUNT_ID, "tpl-1", "en_US"))
                .thenReturn(Optional.of(snapshot("tpl-1", "delivery", ReviewStatus.APPROVED, true,
                        "https://oss.example/asset.png")));
        when(mediaMapper.findByIdAndChannelAccountId(assetId, ACCOUNT_ID)).thenReturn(Optional.of(asset));

        int resolved = service.reconcileUnknown("worker-1");

        assertThat(resolved).isEqualTo(1);
        verify(operationMapper).markSucceeded(operation.getId(), "tpl-1", null, NOW);
        ArgumentCaptor<TemplateMediaAssetEntity> updated = ArgumentCaptor.forClass(TemplateMediaAssetEntity.class);
        verify(mediaMapper).updateById(updated.capture());
        assertThat(updated.getValue().getAssetStatus()).isEqualTo("ATTACHED");
        assertThat(updated.getValue().getAttachedAt()).isEqualTo(NOW);
    }

    @Test
    void ambiguousUnknownCreateRemainsUnknownAndDoesNotTouchMedia() throws Exception {
        UUID assetId = UUID.randomUUID();
        TemplateOperationEntity operation = unknownCreate(assetId, "delivery");
        when(operationMapper.claimUnknown("worker-1", NOW, NOW.plusSeconds(120), 20))
                .thenReturn(List.of(operation));
        when(gateway.list(ACCOUNT_ID, 1, 100)).thenReturn(new ProviderTemplatePage(List.of(
                summary("tpl-1", "delivery", "pass"), summary("tpl-2", "delivery", "pass")), 1, false));

        int resolved = service.reconcileUnknown("worker-1");

        assertThat(resolved).isZero();
        verify(operationMapper, never()).markSucceeded(any(), any(), any(), any());
        verify(operationMapper).markUnknown(eq(operation.getId()), eq("RECONCILIATION_AMBIGUOUS"), any(), any());
        verify(mediaMapper, never()).markAttached(any(), any());
        verify(mediaMapper, never()).markOrphaned(any());
    }

    @Test
    void unknownModifyResolvesByTemplateCodeAndLanguage() throws Exception {
        TemplateOperationEntity operation = unknownModify("tpl-1");
        when(operationMapper.claimUnknown("worker-1", NOW, NOW.plusSeconds(120), 20))
                .thenReturn(List.of(operation));
        when(gateway.list(ACCOUNT_ID, 1, 100))
                .thenReturn(new ProviderTemplatePage(List.of(summary("tpl-1", "renamed", "pass")), 1, false));
        when(gateway.detail(ACCOUNT_ID, "tpl-1", "en_US"))
                .thenReturn(Optional.of(snapshot("tpl-1", "renamed", ReviewStatus.APPROVED, true, null)));

        int resolved = service.reconcileUnknown("worker-1");

        assertThat(resolved).isEqualTo(1);
        verify(operationMapper).markSucceeded(operation.getId(), "tpl-1", null, NOW);
    }

    @Test
    void unknownDeleteResolvesOnlyAfterCompletePaginationConfirmsTemplateIsAbsent() throws Exception {
        TemplateOperationEntity operation = unknownDelete("tpl-deleted");
        TemplateEntity deleted = template("tpl-deleted", "APPROVED", "[]");
        when(operationMapper.claimUnknown("worker-1", NOW, NOW.plusSeconds(120), 20))
                .thenReturn(List.of(operation));
        when(gateway.list(ACCOUNT_ID, 1, 100))
                .thenReturn(new ProviderTemplatePage(List.of(summary("tpl-other", "other", "pass")), 1, false));
        when(templateMapper.findForDisplay(ACCOUNT_ID, "tpl-deleted", "en_US"))
                .thenReturn(Optional.of(deleted));

        int resolved = service.reconcileUnknown("worker-1");

        assertThat(resolved).isEqualTo(1);
        verify(operationMapper).markSucceeded(operation.getId(), "tpl-deleted", null, NOW);
        assertThat(deleted.getDeletedAt()).isEqualTo(NOW);
        assertThat(deleted.getAllowSend()).isFalse();
        verify(templateMapper).updateById(deleted);
    }

    @Test
    void unknownDeleteStaysUnknownWhenProviderStillReturnsItsCode() throws Exception {
        TemplateOperationEntity operation = unknownDelete("tpl-1");
        when(operationMapper.claimUnknown("worker-1", NOW, NOW.plusSeconds(120), 20))
                .thenReturn(List.of(operation));
        when(gateway.list(ACCOUNT_ID, 1, 100))
                .thenReturn(new ProviderTemplatePage(List.of(summary("tpl-1", "delivery", "pass")), 1, false));

        int resolved = service.reconcileUnknown("worker-1");

        assertThat(resolved).isZero();
        verify(operationMapper).markUnknown(eq(operation.getId()), eq("RECONCILIATION_NOT_CONFIRMED"), any(), any());
    }

    @Test
    void explicitProviderRejectionFailsUnknownCreateAndOrphansReferencedMedia() throws Exception {
        UUID assetId = UUID.randomUUID();
        TemplateMediaAssetEntity asset = media(assetId, "ATTACHMENT_UNKNOWN");
        TemplateOperationEntity operation = unknownCreate(assetId, "delivery");
        when(operationMapper.claimUnknown("worker-1", NOW, NOW.plusSeconds(120), 20))
                .thenReturn(List.of(operation));
        when(gateway.list(ACCOUNT_ID, 1, 100))
                .thenReturn(new ProviderTemplatePage(List.of(summary("tpl-1", "delivery", "fail")), 1, false));
        when(gateway.detail(ACCOUNT_ID, "tpl-1", "en_US"))
                .thenReturn(Optional.of(snapshot("tpl-1", "delivery", ReviewStatus.REJECTED, false,
                        "https://oss.example/asset.png")));
        when(mediaMapper.findByIdAndChannelAccountId(assetId, ACCOUNT_ID)).thenReturn(Optional.of(asset));

        int resolved = service.reconcileUnknown("worker-1");

        assertThat(resolved).isEqualTo(1);
        verify(operationMapper).markFailed(eq(operation.getId()), eq(null), eq("TEMPLATE_PROVIDER_REJECTED"),
                any(), eq(NOW));
        verify(mediaMapper).markOrphaned(assetId);
    }

    @Test
    void expiredUnknownOperationStopsReconciliationAfterTwentyFourHours() throws Exception {
        TemplateOperationEntity operation = unknownModify("tpl-1");
        operation.setStartedAt(NOW.minusSeconds(24 * 60 * 60 + 1));
        when(operationMapper.claimUnknown("worker-1", NOW, NOW.plusSeconds(120), 20))
                .thenReturn(List.of(operation));

        int resolved = service.reconcileUnknown("worker-1");

        assertThat(resolved).isZero();
        verify(operationMapper).markFailed(eq(operation.getId()), eq(null), eq("RECONCILIATION_WINDOW_EXPIRED"),
                any(), eq(NOW));
        verify(gateway, never()).list(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    void unconfirmedOperationUsesBoundedExponentialBackoff() throws Exception {
        TemplateOperationEntity operation = unknownModify("tpl-1");
        operation.setReconcileAttemptCount(9);
        when(operationMapper.claimUnknown("worker-1", NOW, NOW.plusSeconds(120), 20))
                .thenReturn(List.of(operation));
        when(gateway.list(ACCOUNT_ID, 1, 100))
                .thenReturn(new ProviderTemplatePage(List.of(summary("tpl-1", "delivery", "pass")), 1, false));
        when(gateway.detail(ACCOUNT_ID, "tpl-1", "en_US")).thenReturn(Optional.empty());

        service.reconcileUnknown("worker-1");

        verify(operationMapper).markUnknown(eq(operation.getId()), eq("RECONCILIATION_NOT_CONFIRMED"),
                any(), eq(NOW.plusSeconds(3 * 60 * 60)));
    }

    @Test
    void tenthClaimedAttemptStillPerformsTheFinalReconciliation() throws Exception {
        TemplateOperationEntity operation = unknownModify("tpl-1");
        operation.setReconcileAttemptCount(10);
        when(operationMapper.claimUnknown("worker-1", NOW, NOW.plusSeconds(120), 20))
                .thenReturn(List.of(operation));
        when(gateway.list(ACCOUNT_ID, 1, 100))
                .thenReturn(new ProviderTemplatePage(List.of(summary("tpl-1", "delivery", "pass")), 1, false));
        when(gateway.detail(ACCOUNT_ID, "tpl-1", "en_US"))
                .thenReturn(Optional.of(snapshot("tpl-1", "delivery", ReviewStatus.APPROVED, true, null)));

        int resolved = service.reconcileUnknown("worker-1");

        assertThat(resolved).isEqualTo(1);
        verify(operationMapper).markSucceeded(operation.getId(), "tpl-1", null, NOW);
    }

    @Test
    void tenthUnconfirmedAttemptBecomesAQueryableFailure() throws Exception {
        TemplateOperationEntity operation = unknownModify("tpl-1");
        operation.setReconcileAttemptCount(10);
        when(operationMapper.claimUnknown("worker-1", NOW, NOW.plusSeconds(120), 20))
                .thenReturn(List.of(operation));

        int resolved = service.reconcileUnknown("worker-1");

        assertThat(resolved).isZero();
        verify(gateway).list(ACCOUNT_ID, 1, 100);
        verify(operationMapper).markFailed(eq(operation.getId()), eq(null),
                eq("RECONCILIATION_ATTEMPT_LIMIT"), any(), eq(NOW));
        verify(operationMapper, never()).markUnknown(any(), any(), any(), any());
    }

    private TemplateOperationEntity unknownCreate(UUID assetId, String name) throws Exception {
        TemplateCommand command = new TemplateCommand(name, "en_US", "UTILITY", List.of(
                new TemplateComponent(ComponentType.HEADER, HeaderFormat.IMAGE, null, assetId.toString(), List.of()),
                new TemplateComponent(ComponentType.BODY, null, "Hello", null, List.of())),
                Map.of(), null, "client-1");
        TemplateOperationEntity operation = new TemplateOperationEntity();
        operation.setId(UUID.randomUUID());
        operation.setChannelAccountId(ACCOUNT_ID);
        operation.setOperationType("CREATE");
        operation.setOperationStatus("SUBMISSION_UNKNOWN");
        operation.setLanguageCode("en_US");
        operation.setRequestedSnapshotJsonb(objectMapper.writeValueAsString(command));
        operation.setReconcileAttemptCount(1);
        operation.setStartedAt(NOW.minusSeconds(60));
        return operation;
    }

    private TemplateOperationEntity unknownModify(String templateCode) throws Exception {
        TemplateCommand command = new TemplateCommand("delivery", "en_US", "UTILITY", List.of(
                new TemplateComponent(ComponentType.BODY, null, "Hello", null, List.of())), Map.of(), null, "client-1");
        TemplateOperationEntity operation = new TemplateOperationEntity();
        operation.setId(UUID.randomUUID());
        operation.setChannelAccountId(ACCOUNT_ID);
        operation.setOperationType("MODIFY");
        operation.setOperationStatus("SUBMISSION_UNKNOWN");
        operation.setProviderTemplateId(templateCode);
        operation.setLanguageCode("en_US");
        operation.setRequestedSnapshotJsonb(objectMapper.writeValueAsString(command));
        operation.setReconcileAttemptCount(1);
        operation.setStartedAt(NOW.minusSeconds(60));
        return operation;
    }

    private TemplateOperationEntity unknownDelete(String templateCode) {
        TemplateOperationEntity operation = new TemplateOperationEntity();
        operation.setId(UUID.randomUUID());
        operation.setChannelAccountId(ACCOUNT_ID);
        operation.setOperationType("DELETE");
        operation.setOperationStatus("SUBMISSION_UNKNOWN");
        operation.setProviderTemplateId(templateCode);
        operation.setLanguageCode("en_US");
        operation.setRequestedSnapshotJsonb("{}");
        operation.setReconcileAttemptCount(1);
        operation.setStartedAt(NOW.minusSeconds(60));
        return operation;
    }

    private static ProviderTemplateSummary summary(String code, String name, String auditStatus) {
        return new ProviderTemplateSummary(code, name, "en_US", "UTILITY", auditStatus, null, NOW);
    }

    private static TemplateSnapshot snapshot(String code, String name, ReviewStatus status,
                                             boolean allowSend, String mediaUrl) {
        List<TemplateComponent> components = mediaUrl == null
                ? List.of(new TemplateComponent(ComponentType.BODY, null, "Hello", null, List.of()))
                : List.of(new TemplateComponent(ComponentType.HEADER, HeaderFormat.IMAGE, null, mediaUrl, List.of()),
                        new TemplateComponent(ComponentType.BODY, null, "Hello", null, List.of()));
        return new TemplateSnapshot(ACCOUNT_ID, code, name, "en_US", "UTILITY", status,
                status == ReviewStatus.APPROVED ? "pass" : status.name(), null, allowSend, components,
                Map.of(), null, NOW, null);
    }

    private static TemplateEntity template(String code, String status, String components) {
        TemplateEntity entity = new TemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setChannelAccountId(ACCOUNT_ID);
        entity.setProviderTemplateId(code);
        entity.setLanguageCode("en_US");
        entity.setName(code);
        entity.setStatus(status);
        entity.setComponentsJsonb(components);
        entity.setAllowSend(true);
        return entity;
    }

    private static TemplateMediaAssetEntity media(UUID id, String status) {
        TemplateMediaAssetEntity asset = new TemplateMediaAssetEntity();
        asset.setId(id);
        asset.setChannelAccountId(ACCOUNT_ID);
        asset.setProviderUrl("https://oss.example/asset.png");
        asset.setAssetStatus(status);
        return asset;
    }

    private static WhatsAppTemplateException providerFailure() {
        return new WhatsAppTemplateException("TEMPLATE_PROVIDER_ERROR",
                org.springframework.http.HttpStatus.BAD_GATEWAY, "provider failed", Map.of(), null, true);
    }
}
