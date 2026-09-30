package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateOperationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppSharedTemplateCatalogServiceTest {
    private static final UUID SCOPE_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final UUID ACCOUNT_ID = UUID.fromString("50000000-0000-0000-0000-00000000000b");
    private static final UUID OWNER_ID = UUID.fromString("50000000-0000-0000-0000-00000000000c");

    @Test
    void listUsesTheCallersSharedScopeAndDoesNotExposeCredentialAccount() {
        TemplateMapper mapper = mock(TemplateMapper.class);
        when(mapper.selectCount(any())).thenReturn(1L);
        when(mapper.selectList(any())).thenReturn(List.of(template()));

        WhatsAppSharedTemplateCatalogService service = new WhatsAppSharedTemplateCatalogService(
                mapper, mock(TemplateOperationMapper.class), new ObjectMapper().findAndRegisterModules(),
                mock(ChannelAccountMapper.class));

        var page = service.list(SCOPE_ID, 1, 20,
                new WhatsAppSharedTemplateCatalogService.TemplateFilters(null, null, null, null, null, null));

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).id()).isNotNull();
        assertThat(page.items().get(0).version()).isEqualTo(3);
        assertThat(page.items().get(0).displayName()).isEqualTo("发货提醒（shipping_notice）");
    }

    /**
     * 企业 API 账号读的是<b>它所在空间</b>那一份库。
     *
     * <p>断言落在生成的 SQL 上（{@code provider_scope_id} 而不是 {@code channel_account_id}）：
     * 这是「拿了哪把钥匙」唯一可观测的证据 —— 拿错那把不会报错，只是安静地返回 0 条。
     */
    @Test
    @SuppressWarnings("unchecked")
    void anEnterpriseApiAccountReadsTheLibraryOfItsWholeSpace() {
        TemplateMapper mapper = mock(TemplateMapper.class);
        when(mapper.selectCount(any())).thenReturn(1L);
        when(mapper.selectList(any())).thenReturn(List.of(template()));
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        when(accounts.findByIdAndOwner(ACCOUNT_ID, OWNER_ID)).thenReturn(account("ADMIN_API_WABA", SCOPE_ID));

        var page = new WhatsAppSharedTemplateCatalogService(mapper, mock(TemplateOperationMapper.class),
                new ObjectMapper().findAndRegisterModules(), accounts)
                .listForAccount(OWNER_ID, ACCOUNT_ID, 1, 20, null);

        assertThat(page.total()).isEqualTo(1);
        ArgumentCaptor<QueryWrapper<TemplateEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper, atLeastOnce()).selectCount(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .contains("provider_scope_id")
                .doesNotContain("channel_account_id");
    }

    /**
     * Business App 账号读的是<b>自己的私有库</b>。两边都必须真的分派 ——
     * 只修一半（比如只对企业 API 分派）会让另一半静默返回 0 条。
     */
    @Test
    @SuppressWarnings("unchecked")
    void aBusinessAppAccountReadsItsOwnPrivateLibrary() {
        TemplateMapper mapper = mock(TemplateMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectList(any())).thenReturn(List.of());
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        when(accounts.findByIdAndOwner(ACCOUNT_ID, OWNER_ID))
                .thenReturn(account("EMPLOYEE_BUSINESS_APP", SCOPE_ID));

        new WhatsAppSharedTemplateCatalogService(mapper, mock(TemplateOperationMapper.class),
                new ObjectMapper().findAndRegisterModules(), accounts)
                .listForAccount(OWNER_ID, ACCOUNT_ID, 1, 20, null);

        ArgumentCaptor<QueryWrapper<TemplateEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper, atLeastOnce()).selectCount(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("channel_account_id");
    }

    /**
     * 账号还没绑空间时返回一页空的，<b>不抛错、也不查库</b>。
     *
     * <p>不抛错：一个账号读不出来不该让整份清单消失。不查库：本来也没有可信的键可查 ——
     * 硬拿 null 去查会得到「要么恒空、要么报错」，两种都比「明确地说这一页是空的」差。
     */
    @Test
    void anAccountWithoutASpaceYieldsAnEmptyPageWithoutTouchingTheDatabase() {
        TemplateMapper mapper = mock(TemplateMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        when(accounts.findByIdAndOwner(ACCOUNT_ID, OWNER_ID)).thenReturn(account("ADMIN_API_WABA", null));

        var page = new WhatsAppSharedTemplateCatalogService(mapper, mock(TemplateOperationMapper.class),
                new ObjectMapper().findAndRegisterModules(), accounts)
                .listForAccount(OWNER_ID, ACCOUNT_ID, 1, 20, null);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        verify(mapper, never()).selectCount(any());
    }

    /** 别人的账号读不到 —— 判据在 SQL（{@code findByIdAndOwner}），查不到就当不存在。 */
    @Test
    void anAccountOfSomebodyElseIsIndistinguishableFromAMissingOne() {
        TemplateMapper mapper = mock(TemplateMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        when(accounts.findByIdAndOwner(ACCOUNT_ID, OWNER_ID)).thenReturn(null);

        WhatsAppSharedTemplateCatalogService service = new WhatsAppSharedTemplateCatalogService(mapper,
                mock(TemplateOperationMapper.class), new ObjectMapper().findAndRegisterModules(), accounts);

        assertThatThrownBy(() -> service.listForAccount(OWNER_ID, ACCOUNT_ID, 1, 20, null))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("WHATSAPP_TEMPLATE_NOT_FOUND");
        verify(mapper, never()).selectCount(any());
    }

    private static ChannelAccountEntity account(String onboardingMode, UUID scopeId) {
        ChannelAccountEntity entity = new ChannelAccountEntity();
        entity.setId(ACCOUNT_ID);
        entity.setOnboardingMode(onboardingMode);
        entity.setProviderScopeId(scopeId);
        return entity;
    }

    private static TemplateEntity template() {
        TemplateEntity entity = new TemplateEntity();
        entity.setId(UUID.fromString("60000000-0000-0000-0000-000000000006"));
        entity.setProviderScopeId(SCOPE_ID);
        entity.setProviderTemplateId("shipping_notice");
        entity.setName("shipping_notice");
        entity.setRemark("发货提醒");
        entity.setLanguageCode("zh_CN");
        entity.setStatus("APPROVED");
        entity.setAllowSend(true);
        entity.setComponentsJsonb("[]");
        entity.setExamplesJsonb("{}");
        entity.setVersion(3L);
        entity.setUpdatedAt(Instant.EPOCH);
        return entity;
    }
}
