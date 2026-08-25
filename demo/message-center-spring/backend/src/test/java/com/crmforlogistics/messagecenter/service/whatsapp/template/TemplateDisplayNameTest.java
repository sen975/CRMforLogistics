package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.crmforlogistics.messagecenter.dto.response.TemplateAdminResponse;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.AuditLogMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateOperationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateDisplayNameTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void usesRemarkBeforeOfficialName() {
        assertThat(TemplateDisplayName.format("delivery_notice", "发货提醒"))
                .isEqualTo("发货提醒（delivery_notice）");
    }

    @Test
    void fallsBackToOfficialNameWhenRemarkIsBlank() {
        assertThat(TemplateDisplayName.format("delivery_notice", "  "))
                .isEqualTo("delivery_notice");
    }

    @Test
    void managementProjectionKeepsOfficialNameAndAddsRemarkAndDisplayName() {
        Fixture fixture = fixture();
        TemplateEntity entity = template();
        entity.setName("delivery_notice");
        entity.setRemark("发货提醒");
        when(fixture.templateMapper().findForDisplay(ACCOUNT_ID, "tpl-1", "en_US"))
                .thenReturn(Optional.of(entity));

        var view = fixture.service().detail(ACCOUNT_ID, "tpl-1", "en_US");
        assertThat(view.name()).isEqualTo("delivery_notice");
        assertThat(view.remark()).isEqualTo("发货提醒");
        assertThat(view.displayName()).isEqualTo("发货提醒（delivery_notice）");

        TemplateAdminResponse response = TemplateAdminResponse.from(view);
        assertThat(response.name()).isEqualTo("delivery_notice");
        assertThat(response.remark()).isEqualTo("发货提醒");
        assertThat(response.displayName()).isEqualTo("发货提醒（delivery_notice）");
    }

    @Test
    void managementSearchIncludesRemarkAlongsideOfficialAndProviderNames() {
        Fixture fixture = fixture();
        when(fixture.templateMapper().selectCount(any())).thenReturn(0L);

        fixture.service().list(ACCOUNT_ID, 1, 20, "发货", null, null, null, null, null);

        var query = org.mockito.ArgumentCaptor.<QueryWrapper<TemplateEntity>>captor();
        verify(fixture.templateMapper()).selectCount(query.capture());
        assertThat(query.getValue().getCustomSqlSegment())
                .contains("name", "remark", "provider_template_id");
    }

    private static Fixture fixture() {
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        TemplateMapper templateMapper = mock(TemplateMapper.class);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(ACCOUNT_ID);
        account.setChannelType("whatsapp");
        account.setAuthStatus("active");
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(account);
        WhatsAppTemplateApplicationService service = new WhatsAppTemplateApplicationService(
                accountMapper,
                mock(TemplateOperationMapper.class),
                mock(TemplateMediaAssetMapper.class),
                templateMapper,
                mock(AuditLogMapper.class),
                mock(WhatsAppTemplateGateway.class),
                new WhatsAppTemplateValidator(),
                new ObjectMapper(),
                Clock.systemUTC());
        return new Fixture(service, templateMapper);
    }

    private static TemplateEntity template() {
        TemplateEntity entity = new TemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setChannelAccountId(ACCOUNT_ID);
        entity.setProviderTemplateId("tpl-1");
        entity.setLanguageCode("en_US");
        entity.setStatus("APPROVED");
        entity.setAllowSend(true);
        return entity;
    }

    private record Fixture(WhatsAppTemplateApplicationService service, TemplateMapper templateMapper) {
    }
}
