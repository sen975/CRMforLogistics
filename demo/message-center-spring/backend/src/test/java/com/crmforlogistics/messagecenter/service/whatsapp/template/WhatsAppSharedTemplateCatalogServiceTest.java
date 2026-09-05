package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateOperationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppSharedTemplateCatalogServiceTest {
    private static final UUID SCOPE_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");

    @Test
    void listUsesTheReadySharedScopeAndDoesNotExposeCredentialAccount() {
        WhatsAppTemplateScopeGate gate = mock(WhatsAppTemplateScopeGate.class);
        TemplateMapper mapper = mock(TemplateMapper.class);
        when(gate.requireReady()).thenReturn(SCOPE_ID);
        when(mapper.selectCount(any())).thenReturn(1L);
        when(mapper.selectList(any())).thenReturn(List.of(template()));

        WhatsAppSharedTemplateCatalogService service = new WhatsAppSharedTemplateCatalogService(
                gate, mapper, mock(TemplateOperationMapper.class), new ObjectMapper().findAndRegisterModules());

        var page = service.list(UUID.randomUUID(), 1, 20,
                new WhatsAppSharedTemplateCatalogService.TemplateFilters(null, null, null, null, null, null));

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).id()).isNotNull();
        assertThat(page.items().get(0).version()).isEqualTo(3);
        assertThat(page.items().get(0).displayName()).isEqualTo("发货提醒（shipping_notice）");
        verify(gate).requireReady();
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
