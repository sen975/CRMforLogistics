package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.AuditLogEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import com.crmforlogistics.messagecenter.entity.TemplateOperationEntity;
import com.crmforlogistics.messagecenter.mapper.AuditLogMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateOperationMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.CreateResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.DeleteResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ModifyResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.PropertyResult;
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
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppTemplateApplicationServiceTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-11T01:00:00Z");

    @Mock private ChannelAccountMapper accountMapper;
    @Mock private TemplateOperationMapper operationMapper;
    @Mock private TemplateMediaAssetMapper mediaMapper;
    @Mock private TemplateMapper templateMapper;
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private WhatsAppTemplateGateway gateway;

    private WhatsAppTemplateApplicationService service;

    @BeforeEach
    void setUp() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(ACCOUNT_ID);
        account.setChannelType("whatsapp");
        account.setAuthStatus("active");
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(account);
        service = new WhatsAppTemplateApplicationService(accountMapper, operationMapper, mediaMapper,
                templateMapper, auditLogMapper, gateway, new WhatsAppTemplateValidator(),
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createIsIdempotentAndCallsProviderOnce() {
        TemplateOperationEntity existing = operation("client-create", "SUCCEEDED", "tpl-1");
        when(operationMapper.findByIdempotency(ACCOUNT_ID, "client-create"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(operationMapper.insertIgnore(any())).thenReturn(1);
        when(operationMapper.findByIdForUpdate(any())).thenReturn(Optional.empty());
        when(gateway.create(eq(ACCOUNT_ID), any())).thenReturn(new CreateResult("tpl-1", "delivery", "req-1"));
        when(gateway.detail(ACCOUNT_ID, "tpl-1", "en_US")).thenReturn(Optional.of(snapshot("tpl-1", ReviewStatus.PENDING, false)));

        var first = service.create(ACCOUNT_ID, command("client-create", null), ACTOR_ID, "trace-1");
        var second = service.create(ACCOUNT_ID, command("client-create", null), ACTOR_ID, "trace-2");

        verify(gateway, times(1)).create(eq(ACCOUNT_ID), any());
        assertThat(first.templateCode()).isEqualTo("tpl-1");
        assertThat(second.operationId()).isEqualTo(existing.getId());
        assertThat(capturedAudit().getResult()).isEqualTo("success");
    }

    @Test
    void timeoutMarksOperationAndMediaUnknownWithoutReplay() {
        UUID assetId = UUID.randomUUID();
        TemplateMediaAssetEntity asset = media(assetId, "UPLOADED");
        when(mediaMapper.findByIdAndChannelAccountId(assetId, ACCOUNT_ID)).thenReturn(Optional.of(asset));
        when(operationMapper.findByIdempotency(ACCOUNT_ID, "client-timeout")).thenReturn(Optional.empty());
        when(operationMapper.insertIgnore(any())).thenReturn(1);
        when(operationMapper.findByIdForUpdate(any())).thenReturn(Optional.empty());
        when(gateway.create(eq(ACCOUNT_ID), any())).thenThrow(provider("TEMPLATE_PROVIDER_TIMEOUT", true, null));

        assertThatThrownBy(() -> service.create(ACCOUNT_ID, command("client-timeout", assetId), ACTOR_ID, "trace"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("TEMPLATE_PROVIDER_TIMEOUT");

        verify(operationMapper).markUnknown(any(), eq("TEMPLATE_PROVIDER_TIMEOUT"), any(), any());
        ArgumentCaptor<TemplateMediaAssetEntity> updated = ArgumentCaptor.forClass(TemplateMediaAssetEntity.class);
        verify(mediaMapper).updateById(updated.capture());
        assertThat(updated.getValue().getAssetStatus()).isEqualTo("ATTACHMENT_UNKNOWN");
        verify(gateway, times(1)).create(eq(ACCOUNT_ID), any());
        assertThat(capturedAudit().getResult()).isEqualTo("unknown");
    }

    @Test
    void explicitProviderRejectionMarksMediaOrphaned() {
        UUID assetId = UUID.randomUUID();
        when(mediaMapper.findByIdAndChannelAccountId(assetId, ACCOUNT_ID))
                .thenReturn(Optional.of(media(assetId, "UPLOADED")));
        when(operationMapper.findByIdempotency(ACCOUNT_ID, "client-rejected")).thenReturn(Optional.empty());
        when(operationMapper.insertIgnore(any())).thenReturn(1);
        when(operationMapper.findByIdForUpdate(any())).thenReturn(Optional.empty());
        when(gateway.create(eq(ACCOUNT_ID), any())).thenThrow(provider("TEMPLATE_PROVIDER_REJECTED", false, "req-r"));

        assertThatThrownBy(() -> service.create(ACCOUNT_ID, command("client-rejected", assetId), ACTOR_ID, "trace"))
                .isInstanceOf(WhatsAppTemplateException.class);

        verify(operationMapper).markFailed(any(), eq("req-r"), eq("TEMPLATE_PROVIDER_REJECTED"), any(), eq(NOW));
        verify(mediaMapper).markOrphaned(assetId);
        assertThat(capturedAudit().getResult()).isEqualTo("failed");
    }

    @Test
    void acknowledgedCreateWithDetailTimeoutPersistsUnknownWithoutMarkingSubmissionUnknown() {
        when(operationMapper.findByIdempotency(ACCOUNT_ID, "client-no-detail")).thenReturn(Optional.empty());
        when(operationMapper.insertIgnore(any())).thenReturn(1);
        when(operationMapper.findByIdForUpdate(any())).thenReturn(Optional.empty());
        when(gateway.create(eq(ACCOUNT_ID), any())).thenReturn(new CreateResult("tpl-2", "delivery", "req-2"));
        when(gateway.detail(ACCOUNT_ID, "tpl-2", "en_US"))
                .thenThrow(provider("TEMPLATE_PROVIDER_TIMEOUT", true, null));

        service.create(ACCOUNT_ID, command("client-no-detail", null), ACTOR_ID, "trace");

        ArgumentCaptor<TemplateEntity> inserted = ArgumentCaptor.forClass(TemplateEntity.class);
        verify(templateMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo("UNKNOWN");
        assertThat(inserted.getValue().getAllowSend()).isFalse();
        verify(operationMapper).markSucceeded(any(), eq("tpl-2"), eq("req-2"), eq(NOW));
        verify(operationMapper, never()).markUnknown(any(), any(), any(), any());
    }

    @Test
    void modifyPermissionAndDeleteUpdateProjectionOnlyAfterProviderSuccess() {
        TemplateEntity template = template("tpl-1", "APPROVED", true);
        when(templateMapper.selectOne(any())).thenReturn(template);
        when(operationMapper.findByIdempotency(eq(ACCOUNT_ID), any())).thenReturn(Optional.empty());
        when(operationMapper.insertIgnore(any())).thenReturn(1);
        when(operationMapper.findByIdForUpdate(any())).thenReturn(Optional.empty());
        when(gateway.modify(eq(ACCOUNT_ID), eq("tpl-1"), eq("en_US"), any()))
                .thenReturn(new ModifyResult("tpl-1", "delivery", "req-m"));
        when(gateway.setSendPermission(ACCOUNT_ID, "tpl-1", "en_US", false))
                .thenReturn(new PropertyResult(false, "req-p"));
        when(gateway.delete(ACCOUNT_ID, "tpl-1", "en_US")).thenReturn(new DeleteResult(true, "req-d"));
        when(gateway.detail(ACCOUNT_ID, "tpl-1", "en_US"))
                .thenReturn(Optional.of(snapshot("tpl-1", ReviewStatus.PENDING, false)),
                        Optional.of(snapshot("tpl-1", ReviewStatus.APPROVED, false)));

        service.modify(ACCOUNT_ID, "tpl-1", "en_US", command("client-modify", null), ACTOR_ID, "trace-m");
        service.setSendPermission(ACCOUNT_ID, "tpl-1", "en_US", false, "client-permission", ACTOR_ID, "trace-p");
        service.delete(ACCOUNT_ID, "tpl-1", "en_US", "client-delete", ACTOR_ID, "trace-d");

        verify(gateway).modify(eq(ACCOUNT_ID), eq("tpl-1"), eq("en_US"), any());
        verify(gateway).setSendPermission(ACCOUNT_ID, "tpl-1", "en_US", false);
        verify(gateway).delete(ACCOUNT_ID, "tpl-1", "en_US");
        assertThat(template.getDeletedAt()).isEqualTo(NOW);
        verify(templateMapper, times(3)).updateById(any(TemplateEntity.class));
    }

    private static TemplateCommand command(String clientRequestId, UUID mediaAssetId) {
        List<TemplateComponent> components = mediaAssetId == null
                ? List.of(new TemplateComponent(ComponentType.BODY, null, "Hello {{customer}}", null, List.of()))
                : List.of(new TemplateComponent(ComponentType.HEADER, HeaderFormat.IMAGE, null,
                        mediaAssetId.toString(), List.of()),
                        new TemplateComponent(ComponentType.BODY, null, "Hello {{customer}}", null, List.of()));
        return new TemplateCommand("delivery", "en_US", "UTILITY", components,
                Map.of("customer", List.of("Ada")), null, clientRequestId);
    }

    private static TemplateSnapshot snapshot(String code, ReviewStatus status, boolean allowSend) {
        return new TemplateSnapshot(ACCOUNT_ID, code, "delivery", "en_US", "UTILITY", status,
                status.name(), null, allowSend,
                List.of(new TemplateComponent(ComponentType.BODY, null, "Hello {{customer}}", null, List.of())),
                Map.of("customer", List.of("Ada")), null, NOW, null);
    }

    private static TemplateOperationEntity operation(String key, String status, String code) {
        TemplateOperationEntity entity = new TemplateOperationEntity();
        entity.setId(UUID.randomUUID());
        entity.setChannelAccountId(ACCOUNT_ID);
        entity.setIdempotencyKey(key);
        entity.setOperationType("CREATE");
        entity.setOperationStatus(status);
        entity.setProviderTemplateId(code);
        entity.setLanguageCode("en_US");
        return entity;
    }

    private static TemplateMediaAssetEntity media(UUID id, String status) {
        TemplateMediaAssetEntity entity = new TemplateMediaAssetEntity();
        entity.setId(id);
        entity.setChannelAccountId(ACCOUNT_ID);
        entity.setProviderUrl("https://oss.example/" + id + ".png");
        entity.setProviderObjectKey("templates/" + id + ".png");
        entity.setMediaFormat("IMAGE");
        entity.setContentType("image/png");
        entity.setSizeBytes(10L);
        entity.setSha256("0".repeat(64));
        entity.setAssetStatus(status);
        return entity;
    }

    private static TemplateEntity template(String code, String status, boolean allowSend) {
        TemplateEntity entity = new TemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setChannelAccountId(ACCOUNT_ID);
        entity.setProviderTemplateId(code);
        entity.setLanguageCode("en_US");
        entity.setName("delivery");
        entity.setStatus(status);
        entity.setAllowSend(allowSend);
        return entity;
    }

    private static WhatsAppTemplateException provider(String code, boolean retryable, String requestId) {
        return new WhatsAppTemplateException(code, HttpStatus.BAD_GATEWAY, code, Map.of(), requestId, retryable);
    }

    private AuditLogEntity capturedAudit() {
        ArgumentCaptor<AuditLogEntity> audit = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogMapper).insert(audit.capture());
        return audit.getValue();
    }
}
