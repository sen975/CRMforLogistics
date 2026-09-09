package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.dto.response.TemplateResponse;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.service.message.TemplateMessageTextResolver;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateScopeGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAppTemplateServiceTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SCOPE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Mock TemplateMapper templateMapper;
    @Mock ChannelAccountMapper accountMapper;
    @Mock WhatsAppTemplateScopeGate scopeGate;
    @Mock TemplateMessageTextResolver templateTextResolver;

    @Test
    void selectorUsesTheReadySharedScope() {
        TemplateEntity approved = template("delivery_ready", "APPROVED", true);
        approved.setName("delivery_notice");
        approved.setRemark("发货提醒");
        approved.setCategory("UTILITY");
        approved.setComponentsJsonb("[{\"type\":\"BODY\",\"text\":\"Hello $(customer)\"}]");
        approved.setExamplesJsonb("{\"customer\":[\"Alice\"]}");
        when(scopeGate.requireReady()).thenReturn(SCOPE_ID);
        when(templateMapper.findSharedSendableForScope(SCOPE_ID)).thenReturn(List.of(approved));

        List<TemplateResponse> response = service().listAll();

        assertThat(response).extracting(TemplateResponse::templateCode).containsExactly("delivery_ready");
        assertThat(response.get(0).displayName()).isEqualTo("发货提醒（delivery_notice）");
        assertThat(response.get(0).placeholders()).containsExactly("customer");
        verify(templateMapper).findSharedSendableForScope(SCOPE_ID);
    }

    @Test
    void accountSelectorUsesThatAccountsSharedScope() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(ACCOUNT_ID);
        account.setProviderScopeId(SCOPE_ID);
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(account);
        when(templateMapper.findSharedSendableForScope(SCOPE_ID))
                .thenReturn(List.of(template("shipping_notice", "APPROVED", true)));

        assertThat(service().listForAccount(ACCOUNT_ID))
                .extracting(TemplateResponse::templateCode)
                .containsExactly("shipping_notice");

        verify(templateMapper).findSharedSendableForScope(SCOPE_ID);
    }

    @Test
    void accountSelectorHidesTemplatesWhenTheAccountHasNoProviderScope() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(ACCOUNT_ID);
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(account);

        assertThat(service().listForAccount(ACCOUNT_ID)).isEmpty();
    }

    private ChatAppTemplateService service() {
        return new ChatAppTemplateService(templateMapper, accountMapper, scopeGate,
                templateTextResolver, new ObjectMapper());
    }

    private static TemplateEntity template(String code, String status, boolean allowSend) {
        TemplateEntity template = new TemplateEntity();
        template.setProviderTemplateId(code);
        template.setName(code);
        template.setLanguageCode("en_US");
        template.setBody("Hello $(customer)");
        template.setStatus(status);
        template.setAllowSend(allowSend);
        return template;
    }
}
