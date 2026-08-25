package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAppAccountResolverTest {

    @Test
    void resolvesTheOnlyActiveAccountMatchingConfiguredSender() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        AppConfig config = mock(AppConfig.class);
        ChannelAccountEntity account = account("+60 111-111-111");
        when(mapper.selectById(account.getId())).thenReturn(account);

        ChannelAccountEntity result = new ChatAppAccountResolver(mapper, config)
                .requireCurrentAccount(account.getId());

        assertThat(result).isSameAs(account);
    }

    @Test
    void rejectsRequestedAccountThatIsNotTheFixedAccount() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        AppConfig config = mock(AppConfig.class);
        ChannelAccountEntity account = account("60111111111");
        assertThatThrownBy(() -> new ChatAppAccountResolver(mapper, config)
                .requireCurrentAccount(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
    }

    @Test
    void rejectsAccountThatDoesNotMatchConfiguredSender() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        AppConfig config = mock(AppConfig.class);
        ChannelAccountEntity account = account("60122222222");
        when(mapper.selectActiveChatAppAccounts()).thenReturn(List.of(account));
        when(config.chatappFrom()).thenReturn("60111111111");

        assertThatThrownBy(() -> new ChatAppAccountResolver(mapper, config)
                .currentFixedAccount())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHATAPP_FIXED_ACCOUNT_CONFIG_MISMATCH");
    }

    @Test
    void resolvesExplicitActiveAccountWhenSeveralChatAppAccountsExist() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        AppConfig config = mock(AppConfig.class);
        ChannelAccountEntity first = account("60111111111");
        ChannelAccountEntity selected = account("60122222222");
        when(mapper.selectById(selected.getId())).thenReturn(selected);
        ChannelAccountEntity result = new ChatAppAccountResolver(mapper, config)
                .requireCurrentAccount(selected.getId());

        assertThat(result).isSameAs(selected);
    }

    private static ChannelAccountEntity account(String identifier) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setChannelType("chatapp");
        account.setAccountIdentifier(identifier);
        account.setAuthStatus("active");
        return account;
    }
}
