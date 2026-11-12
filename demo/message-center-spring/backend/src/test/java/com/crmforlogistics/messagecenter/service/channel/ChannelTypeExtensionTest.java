package com.crmforlogistics.messagecenter.service.channel;

import com.crmforlogistics.messagecenter.dto.request.CreateChannelAccountRequest;
import com.crmforlogistics.messagecenter.dto.response.ChannelAccountSummary;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ③「渠道注册表」的验收用例：新增一个渠道，核心服务一行都不用改。
 *
 * <p>这里注册一个全新的 {@code sms} 渠道 —— 它不出现在
 * {@link ChannelAccountService} 的任何分支里 —— 然后走完整的绑定与同步链路。
 * 用例通过即说明「新增渠道必须改核心」这个根因已经消失。
 *
 * <p>改造前这条用例写不出来：核心有 {@code switch(channelType)} 同步分派、
 * 写死的 {@code CREDENTIAL_KEYS} 凭证表、以及 {@code normalizeIdentifier} 的 if-else，
 * 新渠道必然落到 {@code default -> throw} 上。这是本轮改造的回归护栏 ——
 * 谁把渠道判断再写回核心，这条用例就会红。
 */
class ChannelTypeExtensionTest {

    private static final String SMS = "sms";

    @Test
    void aNewlyRegisteredChannelBindsAndSyncsWithoutTouchingTheCoreService() throws Exception {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        UUID owner = UUID.randomUUID();
        when(mapper.countActiveByOwnerAndChannel(owner, SMS)).thenReturn(0);
        when(mapper.findOwnedByIdentifier(owner, SMS, "13800000000")).thenReturn(null);
        when(cipher.encrypt(any())).thenReturn("encrypted");
        SmsChannelType sms = new SmsChannelType();
        ChannelAccountService service = service(mapper, cipher, sms);

        ChannelAccountSummary bound = service.createOrBind(owner,
                new CreateChannelAccountRequest(SMS, "通知短信", "138 0000 0000", Map.of("apiKey", "key-1")));

        assertThat(bound.channelType()).isEqualTo(SMS);
        ArgumentCaptor<ChannelAccountEntity> inserted = ArgumentCaptor.forClass(ChannelAccountEntity.class);
        verify(mapper).insertOwned(inserted.capture(), eq(owner));
        assertThat(inserted.getValue().getAccountIdentifierNormalized())
                .as("账号标识归一化由渠道自己实现（这里去掉空格）")
                .isEqualTo("13800000000");

        UUID accountId = UUID.randomUUID();
        when(mapper.findByIdAndOwner(accountId, owner)).thenReturn(activeAccount(accountId, owner, SMS));

        assertThat(service.sync(owner, accountId)).isEqualTo(Map.of("providerMessageId", "msg-1"));
        assertThat(sms.syncedAccountId).isEqualTo(accountId);
        verify(mapper).updateSyncStatusOwned(eq(owner), eq(accountId), eq("syncing"), any());
        verify(mapper).updateSyncStatusOwned(eq(owner), eq(accountId), eq("success"), any());
    }

    @Test
    void credentialRulesComeFromTheRegisteredChannel() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        UUID owner = UUID.randomUUID();
        when(mapper.countActiveByOwnerAndChannel(owner, SMS)).thenReturn(0);
        ChannelAccountService service = service(mapper, mock(CredentialCipher.class), new SmsChannelType());

        assertThatThrownBy(() -> service.createOrBind(owner, new CreateChannelAccountRequest(
                SMS, "通知短信", "13800000000", Map.of("apiKey", "key-1", "smtpHost", "smtp.example"))))
                .as("未在渠道声明过的字段被拒绝")
                .isInstanceOf(ChannelAccountException.class)
                .hasMessage("CHANNEL_ACCOUNT_INVALID_CREDENTIAL_FIELD");

        assertThatThrownBy(() -> service.createOrBind(owner, new CreateChannelAccountRequest(
                SMS, "通知短信", "13800000000", Map.of())))
                .as("渠道声明的字段必须全部提供")
                .isInstanceOf(ChannelAccountException.class)
                .hasMessage("CHANNEL_ACCOUNT_INCOMPLETE_CREDENTIALS");

        verify(mapper, never()).insertOwned(any(), any());
    }

    private static ChannelAccountService service(ChannelAccountMapper mapper, CredentialCipher cipher,
                                                 ChannelType channelType) {
        return new ChannelAccountService(mapper, new ChannelTypeRegistry(List.of(channelType)), cipher);
    }

    private static ChannelAccountEntity activeAccount(UUID id, UUID owner, String channelType) {
        ChannelAccountEntity entity = new ChannelAccountEntity();
        entity.setId(id);
        entity.setOwnerUserId(owner);
        entity.setChannelType(channelType);
        entity.setAuthStatus("active");
        return entity;
    }

    /** 一个不存在的渠道，用来证明核心对渠道无感知。 */
    private static final class SmsChannelType implements ChannelType {

        private UUID syncedAccountId;

        @Override
        public String key() {
            return SMS;
        }

        @Override
        public Set<String> credentialFields() {
            return Set.of("apiKey");
        }

        @Override
        public Set<String> secretFields() {
            return Set.of("apiKey");
        }

        @Override
        public String normalizeIdentifier(String identifier) {
            return identifier == null ? "" : identifier.replaceAll("\\D", "");
        }

        @Override
        public Object syncAccount(UUID ownerId, UUID accountId) {
            this.syncedAccountId = accountId;
            return Map.of("providerMessageId", "msg-1");
        }
    }
}
