package com.crmforlogistics.messagecenter.service.message;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateMessageTextResolverTest {
    private static final UUID ACCOUNT_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID SCOPE_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SCOPE_B = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Mock TemplateMapper templateMapper;
    @Mock ChannelAccountMapper accountMapper;

    @Test
    void sendsSharedTemplateThroughTheCurrentAccountsScope() {
        when(accountMapper.selectById(ACCOUNT_B)).thenReturn(account(ACCOUNT_B, SCOPE_A));
        when(templateMapper.findSharedForSend(SCOPE_A, "shipping_notice", "zh_CN"))
                .thenReturn(Optional.of(template("Hello $(name)")));

        assertThat(resolver().renderForSend(ACCOUNT_B, "shipping_notice", "zh_CN", Map.of("name", "张三")))
                .isEqualTo("Hello 张三");
        verify(templateMapper).findSharedForSend(SCOPE_A, "shipping_notice", "zh_CN");
    }

    @Test
    void rejectsTemplateFromAnotherScope() {
        when(accountMapper.selectById(ACCOUNT_A)).thenReturn(account(ACCOUNT_A, SCOPE_B));
        when(templateMapper.findSharedForSend(SCOPE_B, "shipping_notice", "zh_CN"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver().renderForSend(ACCOUNT_A, "shipping_notice", "zh_CN", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_TEMPLATE_NOT_SYNCED");
    }

    @Test
    void restoresHistoricalTemplateBodyThroughTheMessagesAccountScope() throws Exception {
        MessageEntity message = new MessageEntity();
        message.setMessageKind("template");
        message.setChannelAccountId(ACCOUNT_A);
        message.setBodyText("[template]");
        message.setMetadataJsonb(new ObjectMapper().writeValueAsString(Map.of(
                "templateCode", "shipping_notice", "languageCode", "zh_CN",
                "templateParams", Map.of("name", "张三"))));
        when(accountMapper.selectById(ACCOUNT_A)).thenReturn(account(ACCOUNT_A, SCOPE_A));
        when(templateMapper.findSharedForDisplay(SCOPE_A, "shipping_notice", "zh_CN"))
                .thenReturn(Optional.of(template("您好 $(name)")));

        assertThat(resolver().resolve(message)).isEqualTo("您好 张三");
    }

    private TemplateMessageTextResolver resolver() {
        return new TemplateMessageTextResolver(templateMapper, accountMapper, new ObjectMapper());
    }

    private static ChannelAccountEntity account(UUID id, UUID scopeId) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(id);
        account.setProviderScopeId(scopeId);
        return account;
    }

    private static TemplateEntity template(String body) {
        TemplateEntity template = new TemplateEntity();
        template.setBody(body);
        template.setStatus("APPROVED");
        template.setAllowSend(true);
        return template;
    }
}
