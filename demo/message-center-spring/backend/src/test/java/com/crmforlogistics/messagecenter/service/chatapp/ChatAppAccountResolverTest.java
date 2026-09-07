package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAppAccountResolverTest {

    @Test
    void resolvesTheOnlyActiveAccountMatchingConfiguredSender() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        ChannelAccountEntity account = account("+60 111-111-111");
        when(mapper.selectById(account.getId())).thenReturn(account);

        ChannelAccountEntity result = new ChatAppAccountResolver(mapper)
                .requireCurrentAccount(account.getId());

        assertThat(result).isSameAs(account);
    }

    @Test
    void rejectsRequestedAccountThatIsNotTheFixedAccount() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        ChannelAccountEntity account = account("60111111111");
        assertThatThrownBy(() -> new ChatAppAccountResolver(mapper)
                .requireCurrentAccount(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
    }

    @Test
    void resolvesCurrentOwnersAccountWithoutGlobalSenderConfiguration() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        UUID ownerId = UUID.randomUUID();
        ChannelAccountEntity account = account("60122222222");
        when(mapper.findByOwnerAndChannelType(ownerId, "chatapp"))
                .thenReturn(java.util.List.of(account));

        assertThat(new ChatAppAccountResolver(mapper).currentOwnedAccount(ownerId))
                .isSameAs(account);
    }

    @Test
    void resolvesExplicitActiveAccountWhenSeveralChatAppAccountsExist() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        ChannelAccountEntity first = account("60111111111");
        ChannelAccountEntity selected = account("60122222222");
        when(mapper.selectById(selected.getId())).thenReturn(selected);
        ChannelAccountEntity result = new ChatAppAccountResolver(mapper)
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
