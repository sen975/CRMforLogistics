package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public final class ChatAppAccountCredentialsResolver {
    private static final String DEFAULT_REGION = "ap-southeast-1";
    private static final String DEFAULT_ENDPOINT = "cams.ap-southeast-1.aliyuncs.com";

    private final CredentialCipher cipher;

    public ChatAppAccountCredentialsResolver(CredentialCipher cipher) {
        this.cipher = cipher;
    }

    public ChatAppAccountCredentials resolve(ChannelAccountEntity account) {
        if (account == null || account.getDeletedAt() != null
                || !("chatapp".equalsIgnoreCase(account.getChannelType())
                || "whatsapp".equalsIgnoreCase(account.getChannelType()))
                || !"active".equalsIgnoreCase(account.getAuthStatus())) {
            throw new ChatAppAccountCredentialsException("CHATAPP_ACCOUNT_CREDENTIALS_MISSING");
        }
        String encrypted = account.getEncryptedConfig();
        if (encrypted == null || encrypted.isBlank() || "{}".equals(encrypted.trim())) {
            throw new ChatAppAccountCredentialsException("CHATAPP_ACCOUNT_CREDENTIALS_MISSING");
        }
        final Map<String, String> values;
        try {
            values = cipher.decrypt(encrypted);
        } catch (Exception exception) {
            throw new ChatAppAccountCredentialsException("CHATAPP_ACCOUNT_CREDENTIALS_UNREADABLE", exception);
        }
        String accessKeyId = required(values, "accessKeyId");
        String accessKeySecret = required(values, "accessKeySecret");
        String custSpaceId = required(values, "custSpaceId");
        String chatappFrom = required(values, "chatappFrom");
        String region = firstNonBlank(values.get("region"), DEFAULT_REGION);
        String endpoint = firstNonBlank(values.get("endpoint"), DEFAULT_ENDPOINT);
        return new ChatAppAccountCredentials(accessKeyId, accessKeySecret, custSpaceId,
                chatappFrom, region, endpoint);
    }

    private static String required(Map<String, String> values, String key) {
        String value = values == null ? null : values.get(key);
        if (value == null || value.isBlank()) {
            throw new ChatAppAccountCredentialsException("CHATAPP_ACCOUNT_CREDENTIALS_MISSING");
        }
        return value.trim();
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
