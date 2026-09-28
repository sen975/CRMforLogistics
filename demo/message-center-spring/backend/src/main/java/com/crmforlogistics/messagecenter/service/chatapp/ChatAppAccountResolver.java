package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppSubmissionContext;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsResolver;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChatAppAccountResolver {
    private final ChannelAccountMapper channelAccountMapper;
    private final ChatAppAccountCredentialsResolver credentialsResolver;

    public ChatAppAccountResolver(ChannelAccountMapper channelAccountMapper) {
        this(channelAccountMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChatAppAccountResolver(ChannelAccountMapper channelAccountMapper,
                                  ChatAppAccountCredentialsResolver credentialsResolver) {
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
        this.credentialsResolver = credentialsResolver;
    }

    /** Converts a validated account row into a detached provider submission snapshot. */
    public ChatAppSubmissionContext snapshot(ChannelAccountEntity account) {
        validateActiveAccount(account);
        if (credentialsResolver == null) {
            return new ChatAppSubmissionContext(account.getId(), account.getAccountIdentifier(),
                    account.getVersion(), null);
        }
        return new ChatAppSubmissionContext(account.getId(), account.getAccountIdentifier(),
                account.getVersion(), credentialsResolver.resolve(account));
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

    /**
     * Verifies the sender still owns the same account generation immediately
     * before an asynchronous provider submission. The row lock makes the
     * ownership/version read consistent with admin assignment mutations.
     */
    public ChannelAccountEntity requireOwnedAccountForSend(
            UUID ownerId, UUID channelAccountId, Long expectedVersion) {
        if (ownerId == null || channelAccountId == null) {
            throw new IllegalArgumentException("WHATSAPP_ACCOUNT_REASSIGNED");
        }
        ChannelAccountEntity account = channelAccountMapper.findWhatsAppByIdForUpdate(channelAccountId);
        if (account == null) {
            throw new IllegalArgumentException("WHATSAPP_ACCOUNT_REASSIGNED");
        }
        try {
            validateActiveAccount(account);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("WHATSAPP_ACCOUNT_REASSIGNED");
        }
        if (!ownerId.equals(account.getOwnerUserId())
                || (expectedVersion != null && !Objects.equals(account.getVersion(), expectedVersion))) {
            throw new IllegalArgumentException("WHATSAPP_ACCOUNT_REASSIGNED");
        }
        return account;
    }

    /**
     * Resolves the account snapshot needed to reconcile a provider submission.
     * Reconciliation is allowed after ownership transfer or reclaim; only the
     * stable account identity and non-deleted chat-app row are required.
     */
    public ChannelAccountEntity requireAccountForReconciliation(UUID channelAccountId) {
        if (channelAccountId == null) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
        }
        ChannelAccountEntity account = channelAccountMapper.selectById(channelAccountId);
        if (account == null || account.getDeletedAt() != null || !isChatApp(account.getChannelType())) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
        }
        if (account.getAccountIdentifier() == null || account.getAccountIdentifier().isBlank()) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
        }
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
