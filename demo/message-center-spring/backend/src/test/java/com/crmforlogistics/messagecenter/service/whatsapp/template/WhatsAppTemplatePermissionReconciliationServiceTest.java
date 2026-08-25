package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.PropertyResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppTemplatePermissionReconciliationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @Mock TemplateMapper templateMapper;
    @Mock WhatsAppTemplateGateway gateway;

    private WhatsAppTemplatePermissionReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new WhatsAppTemplatePermissionReconciliationService(
                templateMapper, gateway, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void approvedDesiredEnabledTemplateIsEnabledAtProvider() {
        TemplateEntity template = template(false, true, 0, 7L);
        when(templateMapper.findPermissionReconciliationCandidates(ACCOUNT_ID, NOW, 20))
                .thenReturn(List.of(template));
        when(templateMapper.markPermissionPending(template.getId(), 7L, NOW)).thenReturn(1);
        when(gateway.setSendPermission(ACCOUNT_ID, "tpl-1", "en_US", true))
                .thenReturn(new PropertyResult(true, "req-enable"));
        when(templateMapper.markPermissionSucceeded(template.getId(), true, true, NOW))
                .thenReturn(1);

        var result = service.reconcileDueTemplates(ACCOUNT_ID, "worker-1");

        assertThat(result.succeeded()).isEqualTo(1);
        verify(templateMapper).markPermissionSucceeded(
                template.getId(), true, true, NOW);
        verify(templateMapper).findPermissionReconciliationCandidates(ACCOUNT_ID, NOW, 20);
    }

    @Test
    void providerFailureUsesFirstBoundedBackoffStep() {
        TemplateEntity template = template(false, true, 0, 3L);
        when(templateMapper.findPermissionReconciliationCandidates(ACCOUNT_ID, NOW, 20))
                .thenReturn(List.of(template));
        when(templateMapper.markPermissionPending(template.getId(), 3L, NOW)).thenReturn(1);
        when(gateway.setSendPermission(ACCOUNT_ID, "tpl-1", "en_US", true))
                .thenThrow(new WhatsAppTemplateException(
                        "TEMPLATE_PROVIDER_TIMEOUT", HttpStatus.BAD_GATEWAY,
                        "provider timeout", Map.of(), null, true));
        when(templateMapper.markPermissionFailed(
                template.getId(), true, 1, NOW.plusSeconds(60),
                "TEMPLATE_PROVIDER_TIMEOUT", "provider timeout", NOW))
                .thenReturn(1);

        var result = service.reconcileDueTemplates(ACCOUNT_ID, "worker-1");

        assertThat(result.failed()).isEqualTo(1);
        verify(templateMapper).markPermissionFailed(
                template.getId(), true, 1, NOW.plusSeconds(60),
                "TEMPLATE_PROVIDER_TIMEOUT", "provider timeout", NOW);
        verify(templateMapper, never()).markPermissionSucceeded(any(), any(Boolean.class),
                any(Boolean.class), any());
    }

    @Test
    void providerFailureRedactsSignedUrlAndAuthorizationCredentials() {
        TemplateEntity template = template(false, true, 0, 4L);
        when(templateMapper.findPermissionReconciliationCandidates(ACCOUNT_ID, NOW, 20))
                .thenReturn(List.of(template));
        when(templateMapper.markPermissionPending(template.getId(), 4L, NOW)).thenReturn(1);
        when(gateway.setSendPermission(ACCOUNT_ID, "tpl-1", "en_US", true))
                .thenThrow(new IllegalStateException(
                        "request https://cams.ap-southeast-1.aliyuncs.com/"
                                + "?AccessKeyId=AKID-SECRET&Signature=SIGNATURE-SECRET"
                                + "&SecurityToken=STS-SECRET failed; Authorization: Bearer BEARER-SECRET"));

        service.reconcileDueTemplates(ACCOUNT_ID, "worker-1");

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(templateMapper).markPermissionFailed(
                eq(template.getId()), eq(true), eq(1), eq(NOW.plusSeconds(60)),
                eq("TEMPLATE_PERMISSION_SYNC_FAILED"), message.capture(), eq(NOW));
        assertThat(message.getValue())
                .doesNotContain("AKID-SECRET", "SIGNATURE-SECRET", "STS-SECRET", "BEARER-SECRET")
                .contains("[REDACTED]");
    }

    @Test
    void concurrentDesiredStateChangeDoesNotReportStaleProviderSuccess() {
        TemplateEntity template = template(false, true, 0, 8L);
        when(templateMapper.findPermissionReconciliationCandidates(ACCOUNT_ID, NOW, 20))
                .thenReturn(List.of(template));
        when(templateMapper.markPermissionPending(template.getId(), 8L, NOW)).thenReturn(1);
        when(gateway.setSendPermission(ACCOUNT_ID, "tpl-1", "en_US", true))
                .thenReturn(new PropertyResult(true, "req-enable"));
        when(templateMapper.markPermissionSucceeded(template.getId(), true, true, NOW))
                .thenReturn(0);

        var result = service.reconcileDueTemplates(ACCOUNT_ID, "worker-1");

        assertThat(result.succeeded()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void concurrentDesiredStateChangeDoesNotPersistOrReportStaleFailure() {
        TemplateEntity template = template(false, true, 0, 10L);
        when(templateMapper.findPermissionReconciliationCandidates(ACCOUNT_ID, NOW, 20))
                .thenReturn(List.of(template));
        when(templateMapper.markPermissionPending(template.getId(), 10L, NOW)).thenReturn(1);
        when(gateway.setSendPermission(ACCOUNT_ID, "tpl-1", "en_US", true))
                .thenThrow(new IllegalStateException("temporary failure"));
        when(templateMapper.markPermissionFailed(
                template.getId(), true, 1, NOW.plusSeconds(60),
                "TEMPLATE_PERMISSION_SYNC_FAILED", "temporary failure", NOW))
                .thenReturn(0);

        var result = service.reconcileDueTemplates(ACCOUNT_ID, "worker-1");

        assertThat(result.failed()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void staleCandidateIsSkippedBeforeProviderCall() {
        TemplateEntity template = template(false, true, 0, 9L);
        when(templateMapper.findPermissionReconciliationCandidates(ACCOUNT_ID, NOW, 20))
                .thenReturn(List.of(template));
        when(templateMapper.markPermissionPending(template.getId(), 9L, NOW)).thenReturn(0);

        var result = service.reconcileDueTemplates(ACCOUNT_ID, "worker-1");

        assertThat(result.skipped()).isEqualTo(1);
        verify(gateway, never()).setSendPermission(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void sixthAndLaterFailuresStayAtTwentyFourHours() {
        assertThat(WhatsAppTemplatePermissionReconciliationService.backoff(1))
                .hasSeconds(60);
        assertThat(WhatsAppTemplatePermissionReconciliationService.backoff(2))
                .hasSeconds(300);
        assertThat(WhatsAppTemplatePermissionReconciliationService.backoff(6))
                .hasSeconds(86_400);
        assertThat(WhatsAppTemplatePermissionReconciliationService.backoff(20))
                .hasSeconds(86_400);
    }

    private static TemplateEntity template(
            boolean allowSend, boolean desiredAllowSend, int attempts, long version) {
        TemplateEntity entity = new TemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setChannelAccountId(ACCOUNT_ID);
        entity.setProviderTemplateId("tpl-1");
        entity.setLanguageCode("en_US");
        entity.setStatus("APPROVED");
        entity.setAllowSend(allowSend);
        entity.setDesiredAllowSend(desiredAllowSend);
        entity.setPermissionSyncStatus("FAILED");
        entity.setPermissionSyncAttemptCount(attempts);
        entity.setVersion(version);
        return entity;
    }
}
