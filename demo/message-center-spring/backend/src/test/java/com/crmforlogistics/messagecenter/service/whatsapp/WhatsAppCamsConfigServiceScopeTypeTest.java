package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppCamsConfigService.ConfigRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A space's type decides whether its template edits need administrator approval, so the type an
 * administrator submits has to survive the round trip — and a Business App space has to name the
 * employee it belongs to, because one employee's edits are the only ones that reach it.
 */
class WhatsAppCamsConfigServiceScopeTypeTest {
    private static final String CUST_SPACE_ID = "cams-9jvb6o87e6m8";
    private static final String SECRET = "SuperSecretAccessKeyValue1234";
    private static final UUID OWNER = UUID.fromString("76dfb877-017f-424d-9783-2c5409032a77");

    private WhatsAppProviderScopeMapper scopes;
    private WhatsAppCamsConfigService service;

    @BeforeEach
    void setUp() {
        scopes = mock(WhatsAppProviderScopeMapper.class);
        service = new WhatsAppCamsConfigService(mock(AppConfig.class), scopes,
                CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])),
                mock(WhatsAppOnboardingGateway.class));
        when(scopes.findByProviderAndExternalScopeId(anyString(), eq(CUST_SPACE_ID))).thenReturn(scope());
        when(scopes.insertAdminScope(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
        when(scopes.updateAdminScope(any(), anyString(), anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(1);
    }

    @Test
    void businessAppSpaceIsPersistedWithItsOwner() {
        service.create(UUID.randomUUID(), request("EMPLOYEE_BUSINESS_APP", OWNER));

        verify(scopes).insertAdminScope(eq(CUST_SPACE_ID), anyString(), anyString(),
                eq("EMPLOYEE_BUSINESS_APP"), eq(OWNER));
    }

    @Test
    void enterpriseSpaceKeepsNoOwner() {
        service.create(UUID.randomUUID(), request("ENTERPRISE_API", OWNER));

        // The database rejects an enterprise space that names an owner, so the owner is dropped
        // even when a client sends one.
        verify(scopes).insertAdminScope(eq(CUST_SPACE_ID), anyString(), anyString(),
                eq("ENTERPRISE_API"), isNull());
    }

    @Test
    void omittedTypeStillCreatesAnEnterpriseSpace() {
        service.create(UUID.randomUUID(), new ConfigRequest("小森", CUST_SPACE_ID, "LTAI_KEY", SECRET,
                "ap-southeast-1", "cams.ap-southeast-1.aliyuncs.com", null, null, null));

        verify(scopes).insertAdminScope(eq(CUST_SPACE_ID), anyString(), anyString(),
                eq("ENTERPRISE_API"), isNull());
    }

    @Test
    void businessAppSpaceWithoutAnOwnerIsRefused() {
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), request("EMPLOYEE_BUSINESS_APP", null)))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_CAMS_OWNER_REQUIRED");
    }

    @Test
    void changingAnExistingSpaceToBusinessAppMovesItsTypeAndOwner() {
        WhatsAppProviderScopeEntity existing = scope();
        when(scopes.findAdminScopeById(existing.getId())).thenReturn(existing);

        service.update(UUID.randomUUID(), existing.getId(), new ConfigRequest("小森", CUST_SPACE_ID, "LTAI_KEY",
                SECRET, "ap-southeast-1", "cams.ap-southeast-1.aliyuncs.com", 6L, "EMPLOYEE_BUSINESS_APP", OWNER));

        verify(scopes).updateAdminScope(eq(existing.getId()), eq(CUST_SPACE_ID), eq("小森"), anyString(),
                eq("EMPLOYEE_BUSINESS_APP"), eq(OWNER), eq(6L));
    }

    @Test
    void theAdminListCarriesBothSpaceTypes() {
        WhatsAppProviderScopeEntity enterprise = scope();
        WhatsAppProviderScopeEntity business = scope();
        business.setDisplayName("小森");
        business.setScopeType("EMPLOYEE_BUSINESS_APP");
        business.setOwnerUserId(OWNER);
        when(scopes.findAllAdminScopes()).thenReturn(java.util.List.of(enterprise, business));

        assertThat(service.list()).extracting(WhatsAppCamsConfigService.ConfigView::scopeType)
                .containsExactly("ENTERPRISE_API", "EMPLOYEE_BUSINESS_APP");
        assertThat(service.list().get(1).ownerUserId()).isEqualTo(OWNER);
    }

    private static ConfigRequest request(String scopeType, UUID owner) {
        return new ConfigRequest("小森", CUST_SPACE_ID, "LTAI_KEY", SECRET,
                "ap-southeast-1", "cams.ap-southeast-1.aliyuncs.com", null, scopeType, owner);
    }

    private static WhatsAppProviderScopeEntity scope() {
        WhatsAppProviderScopeEntity entity = new WhatsAppProviderScopeEntity();
        entity.setId(UUID.randomUUID());
        entity.setProvider("ALIYUN_CAMS");
        entity.setExternalScopeId(CUST_SPACE_ID);
        entity.setDisplayName("东南亚空间");
        entity.setStatus("READY");
        entity.setScopeType("ENTERPRISE_API");
        return entity;
    }
}
