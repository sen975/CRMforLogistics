package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateChangeRequestEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import com.crmforlogistics.messagecenter.entity.UserEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.TemplateChangeRequestMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.mapper.UserMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppProviderScopeService.ScopeAccount;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ChangeCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ChangeRequestStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ChangeType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateDraft;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppTemplateChangeRequestServiceTest {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN_ID = UUID.fromString("90000000-0000-0000-0000-000000000009");
    private static final UUID ACCOUNT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID SCOPE_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID TEMPLATE_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final Instant NOW = Instant.parse("2026-09-05T02:00:00Z");

    @Mock private TemplateMapper templateMapper;
    @Mock private TemplateChangeRequestMapper changeRequestMapper;
    @Mock private TemplateMediaAssetMapper mediaAssetMapper;
    @Mock private WhatsAppProviderScopeService providerScopeService;
    @Mock private UserMapper userMapper;
    @Mock private RoleMapper roleMapper;
    @Mock private WhatsAppTemplateApplicationService applicationService;

    private WhatsAppTemplateChangeRequestService service;

    @BeforeEach
    void setUp() {
        lenient().when(templateMapper.findSharedForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(template()));
        lenient().when(providerScopeService.requireOwnedActive(USER_ID)).thenReturn(scopeAccount());
        lenient().when(changeRequestMapper.insertIgnore(any())).thenReturn(1);
        lenient().when(userMapper.findByIdNotDeleted(USER_ID)).thenReturn(Optional.of(user(USER_ID, "申请人")));
        service = new WhatsAppTemplateChangeRequestService(templateMapper, changeRequestMapper, mediaAssetMapper,
                providerScopeService, userMapper, roleMapper, applicationService, new WhatsAppTemplateValidator(),
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void submitsEveryChangeTypeWithoutMutatingTheSharedTemplate() {
        for (ChangeCommand command : List.of(
                new ChangeCommand(ChangeType.MODIFY, 4, "modify-1", draft(null), null, "新的业务备注"),
                new ChangeCommand(ChangeType.SET_SEND_PERMISSION, 4, "permission-1", null, false, null),
                new ChangeCommand(ChangeType.DELETE, 4, "delete-1", null, null, null),
                new ChangeCommand(ChangeType.BIND_MEDIA, 4, "media-1", draft(null), null, null))) {
            var outcome = service.submit(USER_ID, TEMPLATE_ID, command, "trace-1");

            assertThat(outcome.request().status()).isEqualTo(ChangeRequestStatus.PENDING_APPROVAL.name());
            assertThat(outcome.request().templateId()).isEqualTo(TEMPLATE_ID);
        }

        verify(templateMapper, never()).updateById(any(TemplateEntity.class));
    }

    @Test
    void remarkOnlyModificationIsPersistedAsAStructuredModifyPayload() throws Exception {
        service.submit(USER_ID, TEMPLATE_ID,
                new ChangeCommand(ChangeType.MODIFY, 4, "remark-1", draft(null), null, "新的业务备注"), "trace-1");

        ArgumentCaptor<TemplateChangeRequestEntity> request = ArgumentCaptor.forClass(TemplateChangeRequestEntity.class);
        verify(changeRequestMapper).insertIgnore(request.capture());
        assertThat(request.getValue().getChangeType()).isEqualTo(ChangeType.MODIFY.name());
        assertThat(new ObjectMapper().readTree(request.getValue().getRequestedPayloadJsonb()).path("remark").asText())
                .isEqualTo("新的业务备注");
    }

    @Test
    void bindMediaRequiresAnUploadedAssetOwnedByTheRequestAccount() {
        UUID assetId = UUID.randomUUID();
        TemplateMediaAssetEntity asset = new TemplateMediaAssetEntity();
        asset.setId(assetId);
        asset.setAssetStatus("UPLOADED");
        asset.setMediaFormat("IMAGE");
        when(mediaAssetMapper.findByIdAndChannelAccountId(assetId, ACCOUNT_ID)).thenReturn(Optional.of(asset));

        var outcome = service.submit(USER_ID, TEMPLATE_ID,
                new ChangeCommand(ChangeType.BIND_MEDIA, 4, "media-owned-1", draft(assetId), null, null), "trace-1");

        assertThat(outcome.request().status()).isEqualTo(ChangeRequestStatus.PENDING_APPROVAL.name());
        verify(mediaAssetMapper).findByIdAndChannelAccountId(assetId, ACCOUNT_ID);
    }

    @Test
    void idempotentReplayReturnsTheOriginalRequestButPayloadConflictIsRejected() {
        TemplateChangeRequestEntity existing = request("request-1", ChangeType.SET_SEND_PERMISSION,
                ChangeRequestStatus.PENDING_APPROVAL, 4, "request-1", false);
        when(changeRequestMapper.findByRequesterAndIdempotency(USER_ID, "request-1"))
                .thenReturn(Optional.of(existing));

        var replay = service.submit(USER_ID, TEMPLATE_ID,
                new ChangeCommand(ChangeType.SET_SEND_PERMISSION, 4, "request-1", null, false, null), "trace-1");
        assertThat(replay.request().id()).isEqualTo(existing.getId());

        assertThatThrownBy(() -> service.submit(USER_ID, TEMPLATE_ID,
                new ChangeCommand(ChangeType.SET_SEND_PERMISSION, 4, "request-1", null, true, null), "trace-2"))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class, error -> {
                    assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                    assertThat(error.statusCode()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void staleVersionIsRecordedImmediatelyWithoutChangingTheTemplate() {
        var outcome = service.submit(USER_ID, TEMPLATE_ID,
                new ChangeCommand(ChangeType.DELETE, 3, "stale-1", null, null, null), "trace-1");

        assertThat(outcome.request().status()).isEqualTo(ChangeRequestStatus.STALE.name());
        ArgumentCaptor<TemplateChangeRequestEntity> request = ArgumentCaptor.forClass(TemplateChangeRequestEntity.class);
        verify(changeRequestMapper).insertIgnore(request.capture());
        assertThat(request.getValue().getStatus()).isEqualTo(ChangeRequestStatus.STALE.name());
        verify(templateMapper, never()).updateById(any(TemplateEntity.class));
    }

    @Test
    void listMineOnlyQueriesTheCurrentRequester() {
        TemplateChangeRequestEntity mine = request("mine-1", ChangeType.DELETE,
                ChangeRequestStatus.PENDING_APPROVAL, 4, "mine-1", null);
        when(changeRequestMapper.listByRequester(USER_ID, 0, 20)).thenReturn(List.of(mine));
        when(changeRequestMapper.countByRequester(USER_ID)).thenReturn(1L);

        var page = service.listMine(USER_ID, 1, 20);

        assertThat(page.items()).singleElement().extracting(view -> view.id()).isEqualTo(mine.getId());
        verify(changeRequestMapper).listByRequester(USER_ID, 0, 20);
        verify(changeRequestMapper, never()).listByRequester(OTHER_USER_ID, 0, 20);
    }

    @Test
    void administratorApprovalClaimsTheRequestAndUsesTheRequestedAccount() {
        TemplateChangeRequestEntity pending = request("pending-1", ChangeType.SET_SEND_PERMISSION,
                ChangeRequestStatus.PENDING_APPROVAL, 4, "pending-1", false);
        when(roleMapper.userHasRole(ADMIN_ID, "admin")).thenReturn(true);
        when(changeRequestMapper.findByIdForUpdate(pending.getId())).thenReturn(Optional.of(pending));
        when(changeRequestMapper.claimApproval(pending.getId(), ADMIN_ID, NOW)).thenReturn(1);
        when(providerScopeService.requireAccount(ACCOUNT_ID)).thenReturn(scopeAccount());
        when(applicationService.setSendPermissionShared(SCOPE_ID, ACCOUNT_ID, TEMPLATE_ID, false,
                "approve-1", ADMIN_ID, pending.getId(), "trace-1"))
                .thenReturn(operation());

        var outcome = service.approve(ADMIN_ID, pending.getId(), "approve-1", "trace-1");

        assertThat(outcome.request().status()).isEqualTo(ChangeRequestStatus.SUCCEEDED.name());
        verify(applicationService).setSendPermissionShared(SCOPE_ID, ACCOUNT_ID, TEMPLATE_ID, false,
                "approve-1", ADMIN_ID, pending.getId(), "trace-1");
        verify(applicationService, never()).setSendPermissionShared(eq(SCOPE_ID), eq(ADMIN_ID), any(), anyBoolean(),
                any(), any(), any(), any());
        verify(changeRequestMapper).markSucceeded(pending.getId(), operation().providerRequestId(), NOW);
    }

    @Test
    void nonAdministratorCannotApproveOrDirectlyExecuteAChange() {
        TemplateChangeRequestEntity pending = request("pending-2", ChangeType.DELETE,
                ChangeRequestStatus.PENDING_APPROVAL, 4, "pending-2", null);
        assertThatThrownBy(() -> service.approve(USER_ID, pending.getId(), "approve-2", "trace-1"))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class,
                        error -> assertThat(error.code()).isEqualTo("WHATSAPP_TEMPLATE_ADMIN_REQUIRED"));
        verifyNoInteractions(applicationService);
    }

    private static ScopeAccount scopeAccount() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(ACCOUNT_ID);
        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setId(SCOPE_ID);
        return new ScopeAccount(account, scope);
    }

    private static TemplateEntity template() {
        TemplateEntity template = new TemplateEntity();
        template.setId(TEMPLATE_ID);
        template.setProviderScopeId(SCOPE_ID);
        template.setProviderTemplateId("shipping_notice");
        template.setName("shipping_notice");
        template.setLanguageCode("zh_CN");
        template.setCategory("UTILITY");
        template.setRemark("旧备注");
        template.setComponentsJsonb("[{\"type\":\"BODY\",\"text\":\"您好 $(name)\"}]");
        template.setExamplesJsonb("{\"name\":[\"Ada\"]}");
        template.setVersion(4L);
        return template;
    }

    private static TemplateDraft draft(UUID mediaAssetId) {
        TemplateComponent header = mediaAssetId == null
                ? new TemplateComponent(ComponentType.BODY, null, "您好 $(name)", null, List.of())
                : new TemplateComponent(ComponentType.HEADER, HeaderFormat.IMAGE, null, mediaAssetId.toString(), List.of());
        List<TemplateComponent> components = mediaAssetId == null ? List.of(header)
                : List.of(header, new TemplateComponent(ComponentType.BODY, null, "您好 $(name)", null, List.of()));
        return new TemplateDraft("shipping_notice", "UTILITY", components, Map.of("name", List.of("Ada")), null);
    }

    private static TemplateChangeRequestEntity request(String idempotencyKey, ChangeType type,
                                                       ChangeRequestStatus status, long baseVersion,
                                                       String requestId, Boolean allowSend) {
        TemplateChangeRequestEntity entity = new TemplateChangeRequestEntity();
        entity.setId(UUID.nameUUIDFromBytes(requestId.getBytes()));
        entity.setTemplateId(TEMPLATE_ID);
        entity.setChangeType(type.name());
        entity.setStatus(status.name());
        entity.setBaseVersion(baseVersion);
        entity.setRequestedByUserId(USER_ID);
        entity.setRequestedViaAccountId(ACCOUNT_ID);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setRequestedPayloadJsonb("{\"changeType\":\"" + type.name() + "\",\"expectedVersion\":"
                + baseVersion + ",\"clientRequestId\":\"" + idempotencyKey + "\",\"allowSend\":"
                + (allowSend == null ? "null" : allowSend) + ",\"template\":null,\"remark\":null}");
        entity.setCreatedAt(NOW);
        return entity;
    }

    private static UserEntity user(UUID id, String displayName) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setDisplayName(displayName);
        return user;
    }

    private static WhatsAppTemplateApplicationService.OperationView operation() {
        return new WhatsAppTemplateApplicationService.OperationView(UUID.randomUUID(),
                WhatsAppTemplateModels.OperationType.SET_SEND_PERMISSION,
                WhatsAppTemplateModels.OperationStatus.SUCCEEDED, "shipping_notice", "provider-1", null);
    }
}
