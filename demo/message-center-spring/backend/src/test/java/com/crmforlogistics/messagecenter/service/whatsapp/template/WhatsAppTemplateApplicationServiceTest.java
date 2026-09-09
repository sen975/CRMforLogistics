package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.AuditLogMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateOperationMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.PropertyResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppTemplateApplicationServiceTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SCOPE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_SCOPE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Mock ChannelAccountMapper accountMapper;
    @Mock TemplateOperationMapper operationMapper;
    @Mock TemplateMediaAssetMapper mediaMapper;
    @Mock TemplateMapper templateMapper;
    @Mock AuditLogMapper auditLogMapper;
    @Mock WhatsAppTemplateGateway gateway;
    @Mock WhatsAppTemplateValidator validator;

    @Test
    void sharedMutationRejectsCredentialAccountFromAnotherScope() {
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(activeAccount(OTHER_SCOPE_ID));

        assertThatThrownBy(() -> service().setSendPermissionShared(SCOPE_ID, ACCOUNT_ID, UUID.randomUUID(),
                true, "request-1", UUID.randomUUID(), null, "trace-1"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("WHATSAPP_PROVIDER_SCOPE_MISMATCH");

        verify(gateway, never()).setSendPermission(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void sharedPermissionRequiresProviderConfirmation() {
        UUID templateId = UUID.randomUUID();
        TemplateEntity template = new TemplateEntity();
        template.setId(templateId);
        template.setProviderScopeId(SCOPE_ID);
        template.setProviderTemplateId("shipping_notice");
        template.setLanguageCode("zh_CN");
        template.setStatus("APPROVED");
        template.setAllowSend(false);
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(activeAccount(SCOPE_ID));
        when(templateMapper.findSharedForUpdate(templateId)).thenReturn(Optional.of(template));
        when(operationMapper.findByIdempotency(ACCOUNT_ID, "request-2")).thenReturn(Optional.empty());
        when(operationMapper.insertIgnore(any())).thenReturn(1);
        when(operationMapper.findByIdForUpdate(any())).thenReturn(Optional.empty());
        when(gateway.setSendPermission(ACCOUNT_ID, "shipping_notice", "zh_CN", true))
                .thenReturn(new PropertyResult(false, "provider-request"));

        assertThatThrownBy(() -> service().setSendPermissionShared(SCOPE_ID, ACCOUNT_ID, templateId,
                true, "request-2", UUID.randomUUID(), null, "trace-2"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("TEMPLATE_PERMISSION_NOT_CONFIRMED");
    }

    @Test
    void privateMutationRejectsAnAccountNotOwnedByTheActor() {
        UUID actorId = UUID.randomUUID();
        when(accountMapper.findByIdAndOwner(ACCOUNT_ID, actorId)).thenReturn(null);

        assertThatThrownBy(() -> service().modifyPrivate(ACCOUNT_ID, actorId, UUID.randomUUID(),
                null, "trace-private"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("WHATSAPP_ACCOUNT_NOT_FOUND");

        verify(gateway, never()).modify(any(), any(), any(), any());
    }

    private WhatsAppTemplateApplicationService service() {
        return new WhatsAppTemplateApplicationService(accountMapper, operationMapper, mediaMapper, templateMapper,
                auditLogMapper, gateway, validator, new ObjectMapper(), Clock.systemUTC());
    }

    private static ChannelAccountEntity activeAccount(UUID scopeId) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(ACCOUNT_ID);
        account.setProviderScopeId(scopeId);
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        return account;
    }
}
