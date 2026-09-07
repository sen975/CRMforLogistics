package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChatAppAccountResolver {
    private final ChannelAccountMapper channelAccountMapper;

    public ChatAppAccountResolver(ChannelAccountMapper channelAccountMapper) {
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
    }

    public ChannelAccountEntity requireCurrentAccount(UUID channelAccountId) {
        if (channelAccountId == null) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
        }
        ChannelAccountEntity account = channelAccountMapper.selectById(channelAccountId);
        if (account == null) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
        }
        validateActiveAccount(account);
        return account;
    }

    public ChannelAccountEntity requireOwnedAccount(UUID ownerId, UUID channelAccountId) {
        if (ownerId == null || channelAccountId == null) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
        }
        ChannelAccountEntity account = channelAccountMapper.findByIdAndOwner(channelAccountId, ownerId);
        if (account == null) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
        }
        validateActiveAccount(account);
        return account;
    }

    public ChannelAccountEntity currentOwnedAccount(UUID ownerId) {
        if (ownerId == null) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
        }
        List<ChannelAccountEntity> accounts =
                channelAccountMapper.findByOwnerAndChannelType(ownerId, "chatapp");
        if (accounts.isEmpty()) {
            throw new IllegalStateException("CHATAPP_CHANNEL_ACCOUNT_NOT_CONFIGURED");
        }
        if (accounts.size() > 1) {
            throw new IllegalStateException("CHATAPP_FIXED_ACCOUNT_VIOLATION");
        }
        ChannelAccountEntity account = accounts.get(0);
        validateActiveAccount(account);
        return account;
    }

    private static void validateActiveAccount(ChannelAccountEntity account) {
        if (account == null || account.getDeletedAt() != null
                || !isChatApp(account.getChannelType())
                || !"active".equalsIgnoreCase(account.getAuthStatus())) {
            throw new IllegalArgumentException("CHATAPP_CHANNEL_ACCOUNT_NOT_FOUND");
        }
    }

    private static boolean isChatApp(String channelType) {
        return "chatapp".equalsIgnoreCase(channelType)
                || "whatsapp".equalsIgnoreCase(channelType);
    }
}
