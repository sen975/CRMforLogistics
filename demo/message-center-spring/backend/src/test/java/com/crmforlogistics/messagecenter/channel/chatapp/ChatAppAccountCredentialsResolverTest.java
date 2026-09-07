package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAppAccountCredentialsResolverTest {

    @Test
    void resolvesCamsCredentialsFromTheSelectedChannelAccount() throws Exception {
        CredentialCipher cipher = mock(CredentialCipher.class);
        ChannelAccountEntity account = activeAccount();
        account.setEncryptedConfig("encrypted");
        when(cipher.decrypt("encrypted")).thenReturn(Map.of(
                "accessKeyId", "account-key-id",
                "accessKeySecret", "account-key-secret",
                "custSpaceId", "account-space",
                "chatappFrom", "60122222222",
                "region", "cn-hangzhou",
                "endpoint", "cams.cn-hangzhou.aliyuncs.com"));

        ChatAppAccountCredentials credentials =
                new ChatAppAccountCredentialsResolver(cipher).resolve(account);

        assertThat(credentials.accessKeyId()).isEqualTo("account-key-id");
        assertThat(credentials.accessKeySecret()).isEqualTo("account-key-secret");
        assertThat(credentials.custSpaceId()).isEqualTo("account-space");
        assertThat(credentials.chatappFrom()).isEqualTo("60122222222");
        assertThat(credentials.region()).isEqualTo("cn-hangzhou");
        assertThat(credentials.endpoint()).isEqualTo("cams.cn-hangzhou.aliyuncs.com");
    }

    @Test
    void rejectsMissingAccountCredentialsWithoutGlobalFallback() {
        CredentialCipher cipher = mock(CredentialCipher.class);

        assertThatThrownBy(() -> new ChatAppAccountCredentialsResolver(cipher).resolve(activeAccount()))
                .isInstanceOf(ChatAppAccountCredentialsException.class)
                .hasFieldOrPropertyWithValue("code", "CHATAPP_ACCOUNT_CREDENTIALS_MISSING");
    }

    @Test
    void rejectsUnreadableAccountCredentials() throws Exception {
        CredentialCipher cipher = mock(CredentialCipher.class);
        ChannelAccountEntity account = activeAccount();
        account.setEncryptedConfig("encrypted");
        when(cipher.decrypt("encrypted")).thenThrow(
                new CredentialCipher.CredentialDecryptionException("CREDENTIAL_DECRYPTION_FAILED"));

        assertThatThrownBy(() -> new ChatAppAccountCredentialsResolver(cipher).resolve(account))
                .isInstanceOf(ChatAppAccountCredentialsException.class)
                .hasFieldOrPropertyWithValue("code", "CHATAPP_ACCOUNT_CREDENTIALS_UNREADABLE");
    }

    private static ChannelAccountEntity activeAccount() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        account.setAccountIdentifier("60122222222");
        return account;
    }
}
