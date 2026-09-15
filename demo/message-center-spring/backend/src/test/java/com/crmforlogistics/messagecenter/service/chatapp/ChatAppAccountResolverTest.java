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

    @Test
    void requireOwnedAccountForSendLocksAndAcceptsMatchingOwnerAndVersion() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        UUID ownerId = UUID.randomUUID();
        ChannelAccountEntity selected = account("60122222222");
        selected.setOwnerUserId(ownerId);
        selected.setVersion(7L);
        when(mapper.findWhatsAppByIdForUpdate(selected.getId())).thenReturn(selected);

        assertThat(new ChatAppAccountResolver(mapper)
                .requireOwnedAccountForSend(ownerId, selected.getId(), 7L))
                .isSameAs(selected);
    }

    @Test
    void requireOwnedAccountForSendRejectsReassignedOrStaleVersion() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        UUID originalOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        ChannelAccountEntity selected = account("60122222222");
        selected.setOwnerUserId(newOwner);
        selected.setVersion(8L);
        when(mapper.findWhatsAppByIdForUpdate(selected.getId())).thenReturn(selected);

        assertThatThrownBy(() -> new ChatAppAccountResolver(mapper)
                .requireOwnedAccountForSend(originalOwner, selected.getId(), 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("WHATSAPP_ACCOUNT_REASSIGNED");
    }

    @Test
    void reconciliationAcceptsReclaimedAccountSnapshotWithoutCurrentOwner() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        ChannelAccountEntity account = account("60122222222");
        account.setAuthStatus("disabled");
        account.setOwnerUserId(null);
        when(mapper.selectById(account.getId())).thenReturn(account);

        assertThat(new ChatAppAccountResolver(mapper)
                .requireAccountForReconciliation(account.getId()))
                .isSameAs(account);
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
