package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.AuditLogEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.AuditLogMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService.TemplateView;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppTemplateRemarkServiceTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-13T01:00:00Z");

    @Mock private ChannelAccountMapper accountMapper;
    @Mock private TemplateMapper templateMapper;
    @Mock private AuditLogMapper auditLogMapper;

    private WhatsAppTemplateRemarkService service;

    @BeforeEach
    void setUp() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(ACCOUNT_ID);
        account.setChannelType("whatsapp");
        account.setAuthStatus("active");
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(account);
        service = new WhatsAppTemplateRemarkService(accountMapper, templateMapper, auditLogMapper,
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void trimsRemarkUpdatesOnlyLocalProjectionAndAuditsBeforeAfter() throws Exception {
        TemplateEntity template = template("旧备注");
        Instant providerUpdatedAt = Instant.parse("2026-08-12T01:00:00Z");
        Instant lastSyncedAt = Instant.parse("2026-08-12T02:00:00Z");
        template.setProviderUpdatedAt(providerUpdatedAt);
        template.setLastSyncedAt(lastSyncedAt);
        when(templateMapper.findForUpdate(ACCOUNT_ID, "tpl-1", "zh_CN")).thenReturn(Optional.of(template));
        when(templateMapper.updateRemark(template.getId(), "发货提醒", NOW)).thenReturn(1);

        TemplateView updated = service.update(ACCOUNT_ID, "tpl-1", "zh_CN", "  发货提醒  ", ACTOR_ID, "trace-1");

        assertThat(updated.name()).isEqualTo("delivery_notice");
        assertThat(updated.remark()).isEqualTo("发货提醒");
        assertThat(updated.displayName()).isEqualTo("发货提醒（delivery_notice）");
        verify(templateMapper).updateRemark(template.getId(), "发货提醒", NOW);
        assertThat(template.getUpdatedAt()).isEqualTo(NOW);
        assertThat(template.getName()).isEqualTo("delivery_notice");
        assertThat(template.getStatus()).isEqualTo("APPROVED");
        assertThat(template.getAllowSend()).isTrue();
        assertThat(template.getProviderUpdatedAt()).isEqualTo(providerUpdatedAt);
        assertThat(template.getLastSyncedAt()).isEqualTo(lastSyncedAt);

        ArgumentCaptor<AuditLogEntity> audit = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogMapper).insert(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("WHATSAPP_TEMPLATE_REMARK_UPDATE");
        assertThat(audit.getValue().getResourceType()).isEqualTo("MESSAGE_TEMPLATE");
        assertThat(audit.getValue().getResourceId()).isEqualTo(template.getId());
        assertThat(audit.getValue().getActorUserId()).isEqualTo(ACTOR_ID);
        assertThat(audit.getValue().getTraceId()).isEqualTo("trace-1");
        assertThat(new ObjectMapper().readTree(audit.getValue().getBeforeSummaryJsonb()))
                .isEqualTo(new ObjectMapper().readTree("{\"templateCode\":\"tpl-1\",\"language\":\"zh_CN\",\"remark\":\"旧备注\"}"));
        assertThat(new ObjectMapper().readTree(audit.getValue().getAfterSummaryJsonb()))
                .isEqualTo(new ObjectMapper().readTree("{\"templateCode\":\"tpl-1\",\"language\":\"zh_CN\",\"remark\":\"发货提醒\"}"));
    }

    @Test
    void nullAndBlankRemarksClearStoredRemark() {
        TemplateEntity nullTemplate = template("已有备注");
        TemplateEntity blankTemplate = template("已有备注");
        blankTemplate.setId(UUID.fromString("30000000-0000-0000-0000-000000000004"));
        when(templateMapper.findForUpdate(ACCOUNT_ID, "tpl-null", "zh_CN")).thenReturn(Optional.of(nullTemplate));
        when(templateMapper.findForUpdate(ACCOUNT_ID, "tpl-blank", "zh_CN")).thenReturn(Optional.of(blankTemplate));
        when(templateMapper.updateRemark(nullTemplate.getId(), null, NOW)).thenReturn(1);
        when(templateMapper.updateRemark(blankTemplate.getId(), null, NOW)).thenReturn(1);

        TemplateView nullResult = service.update(ACCOUNT_ID, "tpl-null", "zh_CN", null, ACTOR_ID, "trace-null");
        TemplateView blankResult = service.update(ACCOUNT_ID, "tpl-blank", "zh_CN", "   ", ACTOR_ID, "trace-blank");

        assertThat(nullResult.remark()).isNull();
        assertThat(nullResult.displayName()).isEqualTo("delivery_notice");
        assertThat(blankResult.remark()).isNull();
        assertThat(blankResult.displayName()).isEqualTo("delivery_notice");
        verify(templateMapper).updateRemark(nullTemplate.getId(), null, NOW);
        verify(templateMapper).updateRemark(blankTemplate.getId(), null, NOW);
    }

    @Test
    void rejectsRemarkLongerThan120CharactersWithStableFieldError() {
        String invalidRemark = "x".repeat(121);

        assertThatThrownBy(() -> service.update(ACCOUNT_ID, "tpl-1", "zh_CN", invalidRemark, ACTOR_ID, "trace-1"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .satisfies(error -> {
                    WhatsAppTemplateException templateError = (WhatsAppTemplateException) error;
                    assertThat(templateError.code()).isEqualTo("TEMPLATE_REMARK_INVALID");
                    assertThat(templateError.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(templateError.fieldErrors()).containsEntry("remark", "must contain at most 120 characters");
                });

        verify(templateMapper, never()).findForUpdate(any(), any(), any());
        verify(templateMapper, never()).updateRemark(any(), any(), any());
        verify(auditLogMapper, never()).insert(any());
    }

    @Test
    void acceptsRemarkWithExactly120Characters() {
        TemplateEntity template = template(null);
        String maximumRemark = "x".repeat(120);
        when(templateMapper.findForUpdate(ACCOUNT_ID, "tpl-1", "zh_CN")).thenReturn(Optional.of(template));
        when(templateMapper.updateRemark(template.getId(), maximumRemark, NOW)).thenReturn(1);

        TemplateView updated = service.update(ACCOUNT_ID, "tpl-1", "zh_CN", maximumRemark, ACTOR_ID, "trace-1");

        assertThat(updated.remark()).isEqualTo(maximumRemark);
        verify(templateMapper).updateRemark(template.getId(), maximumRemark, NOW);
    }

    @Test
    void rejectsWriteConflictWithoutSuccessAuditOrProjection() {
        TemplateEntity template = template("旧备注");
        when(templateMapper.findForUpdate(ACCOUNT_ID, "tpl-1", "zh_CN")).thenReturn(Optional.of(template));
        when(templateMapper.updateRemark(template.getId(), "发货提醒", NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.update(ACCOUNT_ID, "tpl-1", "zh_CN", "发货提醒", ACTOR_ID, "trace-1"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .satisfies(error -> {
                    WhatsAppTemplateException templateError = (WhatsAppTemplateException) error;
                    assertThat(templateError.code()).isEqualTo("TEMPLATE_REMARK_UPDATE_CONFLICT");
                    assertThat(templateError.statusCode()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(auditLogMapper, never()).insert(any());
    }

    @Test
    void rejectsMissingTemplateCodeBeforeTemplateMapper() {
        assertThatThrownBy(() -> service.update(ACCOUNT_ID, "  ", "zh_CN", "备注", ACTOR_ID, "trace-1"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .satisfies(error -> {
                    WhatsAppTemplateException templateError = (WhatsAppTemplateException) error;
                    assertThat(templateError.code()).isEqualTo("TEMPLATE_VALIDATION_FAILED");
                    assertThat(templateError.fieldErrors()).containsEntry("templateCode", "is required");
                });

        verify(templateMapper, never()).findForUpdate(any(), any(), any());
        verify(templateMapper, never()).updateRemark(any(), any(), any());
        verify(auditLogMapper, never()).insert(any());
    }

    @Test
    void rejectsNullTemplateCodeBeforeTemplateMapper() {
        assertThatThrownBy(() -> service.update(ACCOUNT_ID, null, "zh_CN", "备注", ACTOR_ID, "trace-1"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .satisfies(error -> {
                    WhatsAppTemplateException templateError = (WhatsAppTemplateException) error;
                    assertThat(templateError.code()).isEqualTo("TEMPLATE_VALIDATION_FAILED");
                    assertThat(templateError.fieldErrors()).containsEntry("templateCode", "is required");
                });

        verify(templateMapper, never()).findForUpdate(any(), any(), any());
        verify(templateMapper, never()).updateRemark(any(), any(), any());
        verify(auditLogMapper, never()).insert(any());
    }

    @Test
    void rejectsMissingLanguageBeforeTemplateMapper() {
        assertThatThrownBy(() -> service.update(ACCOUNT_ID, "tpl-1", null, "备注", ACTOR_ID, "trace-1"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .satisfies(error -> {
                    WhatsAppTemplateException templateError = (WhatsAppTemplateException) error;
                    assertThat(templateError.code()).isEqualTo("TEMPLATE_VALIDATION_FAILED");
                    assertThat(templateError.fieldErrors()).containsEntry("language", "is required");
                });

        verify(templateMapper, never()).findForUpdate(any(), any(), any());
        verify(templateMapper, never()).updateRemark(any(), any(), any());
        verify(auditLogMapper, never()).insert(any());
    }

    @Test
    void rejectsBlankLanguageBeforeTemplateMapper() {
        assertThatThrownBy(() -> service.update(ACCOUNT_ID, "tpl-1", "  ", "备注", ACTOR_ID, "trace-1"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .satisfies(error -> {
                    WhatsAppTemplateException templateError = (WhatsAppTemplateException) error;
                    assertThat(templateError.code()).isEqualTo("TEMPLATE_VALIDATION_FAILED");
                    assertThat(templateError.fieldErrors()).containsEntry("language", "is required");
                });

        verify(templateMapper, never()).findForUpdate(any(), any(), any());
        verify(templateMapper, never()).updateRemark(any(), any(), any());
        verify(auditLogMapper, never()).insert(any());
    }

    @Test
    void rejectsMissingTemplateAfterAccountValidation() {
        when(templateMapper.findForUpdate(ACCOUNT_ID, "missing", "zh_CN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(ACCOUNT_ID, "missing", "zh_CN", "备注", ACTOR_ID, "trace-1"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("WHATSAPP_TEMPLATE_NOT_FOUND");

        verify(templateMapper, never()).updateRemark(any(), any(), any());
        verify(auditLogMapper, never()).insert(any());
    }

    @Test
    void rejectsInvalidAccountBeforeLockingTemplate() {
        ChannelAccountEntity invalidAccount = new ChannelAccountEntity();
        invalidAccount.setId(ACCOUNT_ID);
        invalidAccount.setChannelType("wecom");
        invalidAccount.setAuthStatus("active");
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(invalidAccount);

        assertThatThrownBy(() -> service.update(ACCOUNT_ID, "tpl-1", "zh_CN", "备注", ACTOR_ID, "trace-1"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("WHATSAPP_ACCOUNT_TYPE_INVALID");

        verify(templateMapper, never()).findForUpdate(any(), any(), any());
        verify(templateMapper, never()).updateRemark(any(), any(), any());
    }

    @Test
    void constructorDoesNotDependOnProviderGateway() {
        TemplateEntity template = template(null);
        when(templateMapper.findForUpdate(ACCOUNT_ID, "tpl-1", "zh_CN")).thenReturn(Optional.of(template));
        when(templateMapper.updateRemark(template.getId(), null, NOW)).thenReturn(1);
        service.update(ACCOUNT_ID, "tpl-1", "zh_CN", null, ACTOR_ID, "trace-1");

        assertThat(service.getClass().getDeclaredConstructors())
                .allSatisfy(constructor -> assertThat(constructor.getParameterTypes())
                        .noneMatch(WhatsAppTemplateGateway.class::equals));
    }

    private static TemplateEntity template(String remark) {
        TemplateEntity entity = new TemplateEntity();
        entity.setId(UUID.fromString("30000000-0000-0000-0000-000000000003"));
        entity.setChannelAccountId(ACCOUNT_ID);
        entity.setProviderTemplateId("tpl-1");
        entity.setLanguageCode("zh_CN");
        entity.setName("delivery_notice");
        entity.setRemark(remark);
        entity.setStatus("APPROVED");
        entity.setAllowSend(true);
        entity.setCategory("UTILITY");
        entity.setComponentsJsonb("[]");
        entity.setExamplesJsonb("{}");
        return entity;
    }
}
