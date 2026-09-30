package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.AuditLogMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateOperationMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.CreateResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.PropertyResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock WhatsAppProviderScopeService providerScopes;

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
        when(gateway.setSendPermission(TemplateCredentialSource.space(SCOPE_ID), "shipping_notice", "zh_CN", true))
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

    @Test
    void aTakenTemplateNameFailsTheOperationInsteadOfAwaitingReconciliation() {
        TemplateCommand command = new TemplateCommand("account_creation_confirmation_3_custom", "zh_CN", "MARKETING",
                List.of(new TemplateComponent(ComponentType.BODY, null, "hello", null, List.of())),
                Map.of(), null, "request-name-taken");
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(activeAccount(SCOPE_ID));
        when(validator.validate(command)).thenReturn(command);
        when(operationMapper.findByIdempotency(ACCOUNT_ID, "request-name-taken")).thenReturn(Optional.empty());
        when(operationMapper.insertIgnore(any())).thenReturn(1);
        when(operationMapper.findByIdForUpdate(any())).thenReturn(Optional.empty());
        when(gateway.create(TemplateCredentialSource.space(SCOPE_ID), command)).thenThrow(new WhatsAppTemplateException(
                "TEMPLATE_NAME_EXISTS", HttpStatus.CONFLICT, "taken", Map.of(), "provider-request", false));

        assertThatThrownBy(() -> service().create(SCOPE_ID, ACCOUNT_ID, command, UUID.randomUUID(), "trace-name"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("TEMPLATE_NAME_EXISTS");

        verify(operationMapper).markFailed(any(), eq("provider-request"), eq("TEMPLATE_NAME_EXISTS"), any(), any());
        verify(operationMapper, never()).markUnknown(any(), any(), any(), any());
    }

    /**
     * 「暂停发送」对一个非营销类模板是<b>必然失败</b>，所以必须在打给 CAMS 之前挡住。
     *
     * <p>2026-09-29 实测（真实实例 + 真实 CAMS）：同一把空间凭据、同一个通知类模板，只改 allowSend
     * —— {@code true} 成功，{@code false} 必然拿到 {@code ERR-COMMON-001}（{@code code: 400, System error}），
     * 网关包成 502 {@code TEMPLATE_PROVIDER_ERROR}，用户看到的是一串英文加 UUID，既看不出原因也看不出下一步。
     * 本地同步还会把非营销模板的 allowSend 直接改回来（见 {@code templateAllowsSend}），
     * 所以这个动作即使被 CAMS 收下也留不住。判据的唯一落点就是这里。
     */
    @Test
    void refusesToPauseAUtilityTemplateBeforeCallingTheProvider() {
        UUID templateId = UUID.randomUUID();
        TemplateEntity template = sharedTemplate(templateId, "UTILITY", "APPROVED", true);
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(activeAccount(SCOPE_ID));
        when(templateMapper.findSharedForUpdate(templateId)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service().setSendPermissionShared(SCOPE_ID, ACCOUNT_ID, templateId,
                false, "request-pause-shared", UUID.randomUUID(), null, "trace-pause-shared"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .satisfies(error -> {
                    var e = (WhatsAppTemplateException) error;
                    assertThat(e.code()).isEqualTo("TEMPLATE_PAUSE_UNSUPPORTED");
                    assertThat(e.statusCode()).isEqualTo(HttpStatus.CONFLICT);
                    // 文案必须点名类别：用户得知道该去改什么，而不是只看到「失败」
                    assertThat(e.getMessage()).contains("UTILITY");
                });

        verify(gateway, never()).setSendPermission(any(), any(), any(), any(Boolean.class));
        verify(operationMapper, never()).insertIgnore(any());
    }

    /** 私有域是本轮 409 的「另一条不校验审核状态的路」，暂停判据必须两边一致，不能只修共享域。 */
    @Test
    void refusesToPauseAUtilityPrivateTemplateBeforeCallingTheProvider() {
        UUID actorId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        ChannelAccountEntity account = activeAccount(SCOPE_ID);
        account.setOnboardingMode("EMPLOYEE_BUSINESS_APP");
        when(accountMapper.findByIdAndOwner(ACCOUNT_ID, actorId)).thenReturn(account);
        when(templateMapper.findPrivateForUpdate(templateId, ACCOUNT_ID))
                .thenReturn(Optional.of(sharedTemplate(templateId, "UTILITY", "APPROVED", true)));

        assertThatThrownBy(() -> service().setSendPermissionPrivate(ACCOUNT_ID, actorId, templateId,
                false, "request-pause-private", "trace-pause-private"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("TEMPLATE_PAUSE_UNSUPPORTED");

        verify(gateway, never()).setSendPermission(any(), any(), any(), any(Boolean.class));
    }

    /** 只挡「必然失败的动作」，不删功能：营销模板仍然照常走 CAMS。 */
    @Test
    void stillPausesAMarketingTemplate() {
        UUID templateId = UUID.randomUUID();
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(activeAccount(SCOPE_ID));
        when(templateMapper.findSharedForUpdate(templateId))
                .thenReturn(Optional.of(sharedTemplate(templateId, "MARKETING", "APPROVED", true)));
        when(operationMapper.findByIdempotency(ACCOUNT_ID, "request-pause-marketing")).thenReturn(Optional.empty());
        when(operationMapper.insertIgnore(any())).thenReturn(1);
        when(operationMapper.findByIdForUpdate(any())).thenReturn(Optional.empty());
        when(gateway.setSendPermission(TemplateCredentialSource.space(SCOPE_ID), "shipping_notice", "zh_CN", false))
                .thenReturn(new PropertyResult(false, "provider-pause"));

        var view = service().setSendPermissionShared(SCOPE_ID, ACCOUNT_ID, templateId, false,
                "request-pause-marketing", UUID.randomUUID(), null, "trace-pause-marketing");

        assertThat(view.operationStatus()).isEqualTo(WhatsAppTemplateModels.OperationStatus.SUCCEEDED);
    }

    private static TemplateEntity sharedTemplate(UUID id, String category, String status, boolean allowSend) {
        TemplateEntity template = new TemplateEntity();
        template.setId(id);
        template.setProviderScopeId(SCOPE_ID);
        template.setProviderTemplateId("shipping_notice");
        template.setLanguageCode("zh_CN");
        template.setCategory(category);
        template.setStatus(status);
        template.setAllowSend(allowSend);
        return template;
    }

    private WhatsAppTemplateApplicationService service() {
        return new WhatsAppTemplateApplicationService(accountMapper, operationMapper, mediaMapper, templateMapper,
                auditLogMapper, gateway, validator, providerScopes, new ObjectMapper(), Clock.systemUTC());
    }

    /**
     * 企业 API 账号走<b>空间凭证</b>、模板落共享库 —— 这是「另一种绑定」要的那条路。
     *
     * <p>修之前这里必然抛 {@code WHATSAPP_TEMPLATE_DOMAIN_MISMATCH}：助手只调
     * {@code createPrivate}，而它第一行就要求账号是 Business App。于是「企业 API 账号
     * 申请不了模板」这件事在界面上表现为一句用户无从理解、也无从处置的错误。
     */
    @Test
    void anEnterpriseApiAccountCreatesIntoTheSharedLibraryOfItsSpace() {
        UUID actorId = UUID.randomUUID();
        TemplateCommand command = command("enterprise-create");
        ChannelAccountEntity account = activeAccount(SCOPE_ID);
        account.setOnboardingMode("ADMIN_API_WABA");
        stubAcceptedCreate(actorId, command, account, "enterprise-create");
        when(gateway.create(TemplateCredentialSource.space(SCOPE_ID), command))
                .thenReturn(new CreateResult("TPL-1", "shipping_notice", "provider-1"));

        var view = service().createForActor(ACCOUNT_ID, command, actorId, "trace-enterprise");

        assertThat(view.operationStatus()).isEqualTo(WhatsAppTemplateModels.OperationStatus.SUCCEEDED);
        verify(templateMapper).upsertShared(any());
        verify(templateMapper, never()).upsertPrivate(any());
    }

    /** Business App 账号走<b>自己的凭证</b>、模板落私有库。两域都真的分派，不是只修一半。 */
    @Test
    void aBusinessAppAccountCreatesIntoItsOwnPrivateLibrary() {
        UUID actorId = UUID.randomUUID();
        TemplateCommand command = command("private-create");
        ChannelAccountEntity account = activeAccount(SCOPE_ID);
        account.setOnboardingMode("EMPLOYEE_BUSINESS_APP");
        stubAcceptedCreate(actorId, command, account, "private-create");
        when(gateway.create(TemplateCredentialSource.account(ACCOUNT_ID), command))
                .thenReturn(new CreateResult("TPL-2", "shipping_notice", "provider-2"));

        service().createForActor(ACCOUNT_ID, command, actorId, "trace-private-domain");

        verify(templateMapper).upsertPrivate(any());
        verify(templateMapper, never()).upsertShared(any());
    }

    /**
     * 账号还没绑空间时<b>先补绑一次</b>，而不是拒掉。
     *
     * <p>{@code provider_scope_id} 只是我们这边的缓存，账号属于哪个空间由凭证里的
     * {@code custSpaceId} 决定。缓存为空就把账号判死，等于让一个在 CAMS 里明明有号码的
     * 用户因为一次没跑过的绑定而「不能申请模板」。
     */
    @Test
    void anAccountWithoutASpaceIsBoundFirstInsteadOfBeingRefused() {
        UUID actorId = UUID.randomUUID();
        TemplateCommand command = command("bind-first");
        ChannelAccountEntity account = activeAccount(null);
        stubAcceptedCreate(actorId, command, account, "bind-first");
        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setId(SCOPE_ID);
        when(providerScopes.bind(account)).thenReturn(scope);
        when(gateway.create(TemplateCredentialSource.space(SCOPE_ID), command))
                .thenReturn(new CreateResult("TPL-3", "shipping_notice", "provider-3"));

        service().createForActor(ACCOUNT_ID, command, actorId, "trace-bind-first");

        verify(providerScopes).bind(account);
        verify(gateway).create(TemplateCredentialSource.space(SCOPE_ID), command);
    }

    /**
     * 别人的账号上绝不建模板 —— 共享域的服务层方法自己不查归属（它的调用方先过了
     * scope 门），域分派把两条路合到一处后，这一查就成了共用的唯一防线。
     */
    @Test
    void aTemplateIsNeverCreatedOnAnAccountTheActorDoesNotOwn() {
        UUID actorId = UUID.randomUUID();
        when(accountMapper.findByIdAndOwner(ACCOUNT_ID, actorId)).thenReturn(null);

        assertThatThrownBy(() -> service().createForActor(ACCOUNT_ID, command("not-mine"),
                actorId, "trace-not-mine"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("WHATSAPP_ACCOUNT_NOT_FOUND");

        verifyNoInteractions(gateway);
    }

    /** 一次成功的申请要满足的前置：账号可查、命令过校验、幂等键是新的、平台已受理。 */
    private void stubAcceptedCreate(UUID actorId, TemplateCommand command, ChannelAccountEntity account,
                                    String clientRequestId) {
        when(accountMapper.findByIdAndOwner(ACCOUNT_ID, actorId)).thenReturn(account);
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(account);
        when(validator.validate(command)).thenReturn(command);
        when(operationMapper.findByIdempotency(ACCOUNT_ID, clientRequestId)).thenReturn(Optional.empty());
        when(operationMapper.insertIgnore(any())).thenReturn(1);
        when(operationMapper.findByIdForUpdate(any())).thenReturn(Optional.empty());
    }

    private static TemplateCommand command(String clientRequestId) {
        return new TemplateCommand("shipping_notice", "zh_CN", "UTILITY",
                List.of(new TemplateComponent(ComponentType.BODY, null, "hello", null, List.of())),
                Map.of(), null, clientRequestId);
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
