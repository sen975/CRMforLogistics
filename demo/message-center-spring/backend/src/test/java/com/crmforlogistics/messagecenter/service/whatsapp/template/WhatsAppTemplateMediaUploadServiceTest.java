package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService.UploadResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadStore.Reservation;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.MediaAssetStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.UploadedMedia;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppTemplateMediaUploadServiceTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SCOPE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-11T01:00:00Z");
    private static final String ONE_BYTE_SHA256 =
            "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a";

    @Mock private WhatsAppProviderScopeService providerScopeService;
    @Mock private WhatsAppTemplateMediaUploadStore store;
    @Mock private WhatsAppTemplateGateway gateway;

    private WhatsAppTemplateMediaUploadService service;

    @BeforeEach
    void setUp() {
        service = new WhatsAppTemplateMediaUploadService(store, gateway,
                Clock.fixed(NOW, ZoneOffset.UTC), providerScopeService);
        when(providerScopeService.requireAccount(ACCOUNT_ID)).thenReturn(scopeAccount());
    }

    /** Media belongs to the space of the account it is uploaded for, so the space is what answers. */
    private static WhatsAppProviderScopeService.ScopeAccount scopeAccount() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(ACCOUNT_ID);
        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setId(SCOPE_ID);
        scope.setExternalScopeId("cams-1");
        return new WhatsAppProviderScopeService.ScopeAccount(account, scope);
    }

    @Test
    void sameFingerprintReturnsStoredUploadWithoutCallingGateway() {
        TemplateMediaAssetEntity existing = asset("request-1", "UPLOADED", ONE_BYTE_SHA256);
        when(store.reserve(any())).thenReturn(new Reservation(existing, false));

        UploadResult result = upload("request-1", " image/PNG; charset=binary ");

        assertThat(result.created()).isFalse();
        assertThat(result.asset().contentType()).isEqualTo("image/png");
        assertThat(result.asset().assetStatus()).isEqualTo(MediaAssetStatus.UPLOADED);
        verifyNoInteractions(gateway);
    }

    @Test
    void reusedRequestIdWithDifferentFingerprintFailsClosed() {
        when(store.reserve(any())).thenReturn(new Reservation(
                asset("request-1", "UPLOADED", "0".repeat(64)), false));

        assertThatThrownBy(() -> upload("request-1", "image/png"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        verifyNoInteractions(gateway);
    }

    @Test
    void timeoutPersistsUnknownAndDoesNotReplay() {
        when(store.reserve(any())).thenAnswer(invocation ->
                new Reservation(invocation.getArgument(0), true));
        when(gateway.upload(any(), any(), any(), any(), any())).thenThrow(new WhatsAppTemplateException(
                "TEMPLATE_PROVIDER_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, "timeout", Map.of(), null, true));
        when(store.markUnknown(any(), any(), any())).thenReturn(
                asset("request-timeout", "SUBMISSION_UNKNOWN", ONE_BYTE_SHA256));

        UploadResult result = upload("request-timeout", "image/png");

        assertThat(result.created()).isTrue();
        assertThat(result.asset().assetStatus()).isEqualTo(MediaAssetStatus.SUBMISSION_UNKNOWN);
        verify(gateway, times(1)).upload(any(), any(), any(), any(), any());
        verify(store, never()).markFailed(any(), any(), any());
    }

    @Test
    void stableProviderFailureIsPersistedAndRethrown() {
        WhatsAppTemplateException rejected = new WhatsAppTemplateException(
                "PROVIDER_PERMISSION_DENIED", HttpStatus.FORBIDDEN, "denied", Map.of(), null, false);
        when(store.reserve(any())).thenAnswer(invocation ->
                new Reservation(invocation.getArgument(0), true));
        when(gateway.upload(any(), any(), any(), any(), any())).thenThrow(rejected);
        when(store.markFailed(any(), eq(rejected), any())).thenReturn(
                asset("request-failed", "FAILED", ONE_BYTE_SHA256));

        assertThatThrownBy(() -> upload("request-failed", "image/png")).isSameAs(rejected);
        verify(store).markFailed(any(), eq(rejected), eq(NOW));
        verify(store, never()).markUnknown(any(), any(), any());
    }

    @Test
    void storedFailureRebuildsStableStatusWithoutCallingGateway() {
        TemplateMediaAssetEntity failed = asset("request-failed", "FAILED", ONE_BYTE_SHA256);
        failed.setErrorCode("PROVIDER_RATE_LIMITED");
        failed.setErrorMessage("slow down");
        when(store.reserve(any())).thenReturn(new Reservation(failed, false));

        assertThatThrownBy(() -> upload("request-failed", "image/png"))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class, error -> {
                    assertThat(error.code()).isEqualTo("PROVIDER_RATE_LIMITED");
                    assertThat(error.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getMessage()).isEqualTo("slow down");
                });
        verifyNoInteractions(gateway);
    }

    @Test
    void createdReservationUploadsNormalizedMediaAndPersistsResult() {
        TemplateMediaAssetEntity uploaded = asset("request-new", "UPLOADED", ONE_BYTE_SHA256);
        when(store.reserve(any())).thenAnswer(invocation ->
                new Reservation(invocation.getArgument(0), true));
        when(gateway.upload(TemplateCredentialSource.space(SCOPE_ID), HeaderFormat.IMAGE,
                new byte[]{1}, "a.png", "image/png"))
                .thenReturn(new UploadedMedia("templates/a.png", "https://provider.invalid/a.png",
                        HeaderFormat.IMAGE, "image/png", 1, ONE_BYTE_SHA256));
        when(store.markUploaded(any(), any(), eq(NOW))).thenReturn(uploaded);

        UploadResult result = upload("request-new", " Image/PNG ; charset=UTF-8");

        assertThat(result.created()).isTrue();
        assertThat(result.asset().assetStatus()).isEqualTo(MediaAssetStatus.UPLOADED);
        verify(providerScopeService).requireAccount(ACCOUNT_ID);
        verify(gateway).upload(TemplateCredentialSource.space(SCOPE_ID), HeaderFormat.IMAGE,
                new byte[]{1}, "a.png", "image/png");
    }

    @Test
    void invalidDeclarationFailsBeforeReservation() {
        assertThatThrownBy(() -> service.upload(ACCOUNT_ID, HeaderFormat.IMAGE,
                new ByteArrayInputStream(new byte[]{1}), 0, "a.png", "image/png",
                "request-1", ACTOR_ID, "trace-1"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("TEMPLATE_VALIDATION_FAILED");
        assertThatThrownBy(() -> service.upload(ACCOUNT_ID, HeaderFormat.IMAGE,
                new ByteArrayInputStream(new byte[]{1}), 1, "a.gif", "image/gif",
                "request-2", ACTOR_ID, "trace-2"))
                .isInstanceOf(WhatsAppTemplateException.class);
        assertThatThrownBy(() -> service.upload(ACCOUNT_ID, HeaderFormat.IMAGE,
                new ByteArrayInputStream(new byte[]{1}), 1, "a.png", "image/png",
                " ", ACTOR_ID, "trace-3"))
                .isInstanceOf(WhatsAppTemplateException.class);
        verifyNoInteractions(store, gateway);
    }

    @Test
    void unsafeRequestIdsFailBeforeReservation() {
        for (String requestId : new String[]{"order/123", "request?id=1", "abc def"}) {
            assertThatThrownBy(() -> upload(requestId, "image/png"))
                    .isInstanceOfSatisfying(WhatsAppTemplateException.class, error -> {
                        assertThat(error.code()).isEqualTo("TEMPLATE_VALIDATION_FAILED");
                        assertThat(error.fieldErrors()).containsEntry("clientRequestId",
                                "must match [A-Za-z0-9._~:-]{1,255}");
                    });
        }
        verifyNoInteractions(store, gateway);
    }

    @Test
    void pathSafeRequestIdsAreAccepted() {
        when(store.reserve(any())).thenAnswer(invocation ->
                new Reservation(invocation.getArgument(0), false));

        assertThat(upload("legacy:123e4567-e89b-12d3-a456-426614174000", "image/png").created())
                .isFalse();
        assertThat(upload("550e8400-e29b-41d4-a716-446655440000", "image/png").created())
                .isFalse();
    }

    @Test
    void findExpiresStaleProcessingWithoutCallingGateway() {
        TemplateMediaAssetEntity processing = asset("request-stale", "PROCESSING", ONE_BYTE_SHA256);
        processing.setStartedAt(NOW.minusSeconds(90));
        TemplateMediaAssetEntity unknown = asset("request-stale", "SUBMISSION_UNKNOWN", ONE_BYTE_SHA256);
        when(store.find(ACCOUNT_ID, "request-stale")).thenReturn(processing);
        when(store.expireIfStale(processing.getId(), NOW.minusSeconds(90), NOW)).thenReturn(unknown);

        assertThat(service.find(ACCOUNT_ID, "request-stale").assetStatus())
                .isEqualTo(MediaAssetStatus.SUBMISSION_UNKNOWN);
        verify(store).expireIfStale(processing.getId(), NOW.minusSeconds(90), NOW);
        verifyNoInteractions(gateway);
    }

    private UploadResult upload(String clientRequestId, String contentType) {
        return service.upload(ACCOUNT_ID, HeaderFormat.IMAGE,
                new ByteArrayInputStream(new byte[]{1}), 1, "a.png", contentType,
                clientRequestId, ACTOR_ID, "trace-2");
    }

    private static TemplateMediaAssetEntity asset(String requestId, String status, String sha256) {
        TemplateMediaAssetEntity asset = new TemplateMediaAssetEntity();
        asset.setId(UUID.randomUUID());
        asset.setChannelAccountId(ACCOUNT_ID);
        asset.setClientRequestId(requestId.trim());
        asset.setMediaFormat("IMAGE");
        asset.setContentType("image/png");
        asset.setSizeBytes(1L);
        asset.setSha256(sha256);
        asset.setAssetStatus(status);
        asset.setCreatedByUserId(ACTOR_ID);
        asset.setTraceId("trace-stored");
        asset.setStartedAt(NOW);
        asset.setCreatedAt(NOW);
        asset.setUpdatedAt(NOW);
        if (!"PROCESSING".equals(status) && !"FAILED".equals(status)
                && !"SUBMISSION_UNKNOWN".equals(status)) {
            asset.setProviderObjectKey("templates/a.png");
            asset.setProviderUrl("https://provider.invalid/a.png");
        }
        return asset;
    }
}
