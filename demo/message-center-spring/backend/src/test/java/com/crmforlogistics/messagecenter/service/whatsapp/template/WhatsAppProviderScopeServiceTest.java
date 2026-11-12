package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentials;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsException;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsResolver;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppProviderScopeServiceTest {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID SCOPE_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");

    @Test
    void resolvesTheOwnedAccountAndReusesTheExistingProviderScope() {
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopeMapper = mock(WhatsAppProviderScopeMapper.class);
        ChatAppAccountCredentialsResolver resolver = mock(ChatAppAccountCredentialsResolver.class);
        ChannelAccountEntity account = activeAccount();
        WhatsAppProviderScopeEntity scope = scope(SCOPE_ID, "space-1");
        when(accountMapper.findByOwnerAndChannelType(USER_ID, "chatapp")).thenReturn(List.of(account));
        when(resolver.resolve(account)).thenReturn(credentials("space-1"));
        when(scopeMapper.findAllByProvider("ALIYUN_CAMS")).thenReturn(List.of(scope));
        when(scopeMapper.findByProviderAndExternalScopeId("ALIYUN_CAMS", "space-1")).thenReturn(scope);
        when(accountMapper.bindProviderScope(ACCOUNT_ID, SCOPE_ID)).thenReturn(1);
        WhatsAppProviderScopeService service = service(accountMapper, scopeMapper, resolver);

        WhatsAppProviderScopeService.ScopeAccount resolved = service.requireOwnedActive(USER_ID);

        assertThat(resolved.account().getId()).isEqualTo(ACCOUNT_ID);
        assertThat(resolved.scope().getExternalScopeId()).isEqualTo("space-1");
        verify(scopeMapper).insertIgnore("ALIYUN_CAMS", "space-1");
        verify(accountMapper).bindProviderScope(ACCOUNT_ID, SCOPE_ID);
    }

    @Test
    void copiesExistingAccountCredentialsIntoAnEmptyEnterpriseScope() {
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopeMapper = mock(WhatsAppProviderScopeMapper.class);
        ChatAppAccountCredentialsResolver resolver = mock(ChatAppAccountCredentialsResolver.class);
        ChannelAccountEntity account = activeAccount();
        WhatsAppProviderScopeEntity scope = scope(SCOPE_ID, "space-1");
        scope.setEncryptedConfig("{}");
        when(accountMapper.findByOwnerAndChannelType(USER_ID, "chatapp")).thenReturn(List.of(account));
        when(resolver.resolve(account)).thenReturn(credentials("space-1"));
        when(scopeMapper.findAllByProvider("ALIYUN_CAMS")).thenReturn(List.of(scope));
        when(scopeMapper.findByProviderAndExternalScopeId("ALIYUN_CAMS", "space-1")).thenReturn(scope);
        when(scopeMapper.updateEncryptedConfigIfEmpty(SCOPE_ID, "encrypted")).thenReturn(1);
        when(accountMapper.bindProviderScope(ACCOUNT_ID, SCOPE_ID)).thenReturn(1);
        WhatsAppProviderScopeService service = service(accountMapper, scopeMapper, resolver);

        WhatsAppProviderScopeEntity resolved = service.requireOwnedActive(USER_ID).scope();

        assertThat(resolved.getEncryptedConfig()).isEqualTo("encrypted");
        verify(scopeMapper).updateEncryptedConfigIfEmpty(SCOPE_ID, "encrypted");
    }

    /** Each CAMS space keeps its own template library, so a second space is not a conflict. */
    @Test
    void acceptsAnAccountThatNamesASpaceOtherThanAnExistingOne() {
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopeMapper = mock(WhatsAppProviderScopeMapper.class);
        ChatAppAccountCredentialsResolver resolver = mock(ChatAppAccountCredentialsResolver.class);
        WhatsAppProviderScopeService service = service(accountMapper, scopeMapper, resolver);

        service.assertCompatible("space-2");

        verify(scopeMapper, never()).insertIgnore(any(), any());
    }

    @Test
    void rejectsAnAccountThatNamesNoSpace() {
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopeMapper = mock(WhatsAppProviderScopeMapper.class);
        ChatAppAccountCredentialsResolver resolver = mock(ChatAppAccountCredentialsResolver.class);
        WhatsAppProviderScopeService service = service(accountMapper, scopeMapper, resolver);

        assertThatThrownBy(() -> service.assertCompatible("  "))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class,
                        error -> assertThat(error.code()).isEqualTo("CHATAPP_ACCOUNT_CREDENTIALS_MISSING"));
    }

    @Test
    void rejectsMissingInactiveAndUnreadableOwnedAccounts() {
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopeMapper = mock(WhatsAppProviderScopeMapper.class);
        ChatAppAccountCredentialsResolver resolver = mock(ChatAppAccountCredentialsResolver.class);
        WhatsAppProviderScopeService service = service(accountMapper, scopeMapper, resolver);
        when(accountMapper.findByOwnerAndChannelType(USER_ID, "chatapp")).thenReturn(List.of());

        assertThatThrownBy(() -> service.requireOwnedActive(USER_ID))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class,
                        error -> assertThat(error.code()).isEqualTo("WHATSAPP_ACCOUNT_REQUIRED"));

        ChannelAccountEntity inactive = activeAccount();
        inactive.setAuthStatus("disabled");
        when(accountMapper.findByOwnerAndChannelType(USER_ID, "chatapp")).thenReturn(List.of(inactive));
        assertThatThrownBy(() -> service.requireOwnedActive(USER_ID))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class,
                        error -> assertThat(error.code()).isEqualTo("WHATSAPP_ACCOUNT_INACTIVE"));

        ChannelAccountEntity unreadable = activeAccount();
        when(accountMapper.findByOwnerAndChannelType(USER_ID, "chatapp")).thenReturn(List.of(unreadable));
        when(resolver.resolve(unreadable)).thenThrow(
                new ChatAppAccountCredentialsException("CHATAPP_ACCOUNT_CREDENTIALS_UNREADABLE"));
        assertThatThrownBy(() -> service.requireOwnedActive(USER_ID))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class, error -> {
                    assertThat(error.code()).isEqualTo("CHATAPP_ACCOUNT_CREDENTIALS_UNREADABLE");
                    assertThat(error.getMessage()).doesNotContain("accessKeySecret");
                });
    }

    @Test
    void requireAccountRejectsInactiveAccountsBeforeResolvingSecrets() {
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopeMapper = mock(WhatsAppProviderScopeMapper.class);
        ChatAppAccountCredentialsResolver resolver = mock(ChatAppAccountCredentialsResolver.class);
        ChannelAccountEntity inactive = activeAccount();
        inactive.setAuthStatus("expired");
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(inactive);
        WhatsAppProviderScopeService service = service(accountMapper, scopeMapper, resolver);

        assertThatThrownBy(() -> service.requireAccount(ACCOUNT_ID))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class,
                        error -> assertThat(error.code()).isEqualTo("WHATSAPP_ACCOUNT_INACTIVE"));
        verify(resolver, never()).resolve(any());
    }

    @Test
    void aNamedSpaceKeepsUsingTheCallersOwnAccountWhenItLivesThere() {
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopeMapper = mock(WhatsAppProviderScopeMapper.class);
        ChannelAccountEntity owned = activeAccount();
        owned.setProviderScopeId(SCOPE_ID);
        when(scopeMapper.selectById(SCOPE_ID)).thenReturn(scope(SCOPE_ID, "space-1"));
        when(accountMapper.findByOwnerAndChannelType(USER_ID, "chatapp")).thenReturn(List.of(owned));
        WhatsAppProviderScopeService service = service(accountMapper, scopeMapper,
                mock(ChatAppAccountCredentialsResolver.class));

        WhatsAppProviderScopeService.ScopeAccount resolved = service.requireScopeAccount(USER_ID, SCOPE_ID);

        assertThat(resolved.account().getId()).isEqualTo(ACCOUNT_ID);
        assertThat(resolved.scope().getId()).isEqualTo(SCOPE_ID);
        verify(accountMapper, never()).findActiveByScope(any());
    }

    @Test
    void aNamedSpaceFallsBackToAnAccountBoundToIt() {
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopeMapper = mock(WhatsAppProviderScopeMapper.class);
        ChannelAccountEntity owned = activeAccount();
        owned.setProviderScopeId(UUID.fromString("30000000-0000-0000-0000-0000000000ff"));
        ChannelAccountEntity bound = activeAccount();
        bound.setId(UUID.fromString("20000000-0000-0000-0000-0000000000ff"));
        bound.setProviderScopeId(SCOPE_ID);
        when(scopeMapper.selectById(SCOPE_ID)).thenReturn(scope(SCOPE_ID, "space-1"));
        when(accountMapper.findByOwnerAndChannelType(USER_ID, "chatapp")).thenReturn(List.of(owned));
        when(accountMapper.findActiveByScope(SCOPE_ID)).thenReturn(List.of(bound));
        WhatsAppProviderScopeService service = service(accountMapper, scopeMapper,
                mock(ChatAppAccountCredentialsResolver.class));

        WhatsAppProviderScopeService.ScopeAccount resolved = service.requireScopeAccount(USER_ID, SCOPE_ID);

        assertThat(resolved.account().getId()).isEqualTo(bound.getId());
    }

    @Test
    void aNamedSpaceWithoutAnActiveAccountIsRefused() {
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopeMapper = mock(WhatsAppProviderScopeMapper.class);
        ChannelAccountEntity expired = activeAccount();
        expired.setProviderScopeId(SCOPE_ID);
        expired.setAuthStatus("expired");
        when(scopeMapper.selectById(SCOPE_ID)).thenReturn(scope(SCOPE_ID, "space-1"));
        when(accountMapper.findByOwnerAndChannelType(USER_ID, "chatapp")).thenReturn(List.of());
        when(accountMapper.findActiveByScope(SCOPE_ID)).thenReturn(List.of(expired));
        WhatsAppProviderScopeService service = service(accountMapper, scopeMapper,
                mock(ChatAppAccountCredentialsResolver.class));

        assertThatThrownBy(() -> service.requireScopeAccount(USER_ID, SCOPE_ID))
                .isInstanceOfSatisfying(WhatsAppTemplateException.class, error -> {
                    assertThat(error.code()).isEqualTo("WHATSAPP_SCOPE_ACCOUNT_REQUIRED");
                    assertThat(error.statusCode()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void scopeGatePersistsReadyAndFailureState() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WhatsAppTemplateScopeGate gate = new WhatsAppTemplateScopeGate(jdbc);

        gate.open(SCOPE_ID);
        gate.fail("WHATSAPP_TEMPLATE_SCOPE_CONFLICT", "共享模板空间存在冲突");

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("status = 'READY'"),
                org.mockito.ArgumentMatchers.eq(SCOPE_ID));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("status = 'BLOCKED'"),
                org.mockito.ArgumentMatchers.eq("WHATSAPP_TEMPLATE_SCOPE_CONFLICT"),
                org.mockito.ArgumentMatchers.eq("共享模板空间存在冲突"),
                org.mockito.ArgumentMatchers.eq("WHATSAPP_TEMPLATE_SCOPE_CONFLICT"),
                org.mockito.ArgumentMatchers.eq("共享模板空间存在冲突"));
    }

    @Test
    void scopeGateRejectsPendingAndReturnsReadyScope() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WhatsAppTemplateScopeGate gate = new WhatsAppTemplateScopeGate(jdbc);
        when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        assertThatThrownBy(gate::requireReady)
                .isInstanceOfSatisfying(WhatsAppTemplateException.class, error -> {
                    assertThat(error.code()).isEqualTo("WHATSAPP_TEMPLATE_MIGRATION_PENDING");
                    assertThat(error.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });

        when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(new WhatsAppTemplateScopeGate.GateState("READY", SCOPE_ID, null, null)));
        assertThat(gate.requireReady()).isEqualTo(SCOPE_ID);
    }

    private WhatsAppProviderScopeService service(ChannelAccountMapper accountMapper,
                                                 WhatsAppProviderScopeMapper scopeMapper,
                                                 ChatAppAccountCredentialsResolver resolver) {
        return new WhatsAppProviderScopeService(accountMapper, scopeMapper, resolver);
    }

    private ChannelAccountEntity activeAccount() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(ACCOUNT_ID);
        account.setOwnerUserId(USER_ID);
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        account.setEncryptedConfig("encrypted");
        return account;
    }

    private WhatsAppProviderScopeEntity scope(UUID id, String externalScopeId) {
        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setId(id);
        scope.setProvider("ALIYUN_CAMS");
        scope.setExternalScopeId(externalScopeId);
        scope.setStatus("READY");
        scope.setEncryptedConfig("encrypted");
        return scope;
    }

    private ChatAppAccountCredentials credentials(String custSpaceId) {
        return new ChatAppAccountCredentials("key-id", "secret-not-for-errors", custSpaceId,
                "60111111111", "ap-southeast-1", "cams.ap-southeast-1.aliyuncs.com");
    }
}
