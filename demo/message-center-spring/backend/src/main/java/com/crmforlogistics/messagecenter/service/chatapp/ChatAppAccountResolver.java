package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.infrastructure.ContactPointUtil;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChatAppAccountResolver {
    private final ChannelAccountMapper channelAccountMapper;
    private final AppConfig config;

    public ChatAppAccountResolver(ChannelAccountMapper channelAccountMapper, AppConfig config) {
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
        this.config = Objects.requireNonNull(config);
    }

    public ChannelAccountEntity currentFixedAccount() {
        List<ChannelAccountEntity> accounts = channelAccountMapper.selectActiveChatAppAccounts();
        if (accounts.isEmpty()) {
            throw new IllegalStateException("CHATAPP_CHANNEL_ACCOUNT_NOT_CONFIGURED");
        }
        if (accounts.size() > 1) {
            throw new IllegalStateException("CHATAPP_FIXED_ACCOUNT_VIOLATION");
        }
        ChannelAccountEntity account = accounts.get(0);
        validateAccount(account);
        return account;
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

    private void validateAccount(ChannelAccountEntity account) {
        validateActiveAccount(account);
        String configured = ContactPointUtil.normalizePhone(config.chatappFrom());
        String stored = ContactPointUtil.normalizePhone(account.getAccountIdentifier());
        if (configured.isBlank() || !configured.equals(stored)) {
            throw new IllegalStateException("CHATAPP_FIXED_ACCOUNT_CONFIG_MISMATCH");
        }
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
