package com.crmforlogistics.messagecenter.service.channel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注册表自身的解析规则：别名、大小写、未注册类型、敏感字段并集、名称冲突。
 *
 * <p>用匿名 {@link ChannelType} 桩而不是真实渠道实现，是为了让这些规则不依赖
 * 任何具体渠道 —— 规则本身与「有哪些渠道」无关。
 */
class ChannelTypeRegistryTest {

    private static final ChannelType EMAIL =
            stub("email", Set.of(), Set.of("smtpPassword"));
    private static final ChannelType CHATAPP =
            stub("chatapp", Set.of("whatsapp"), Set.of("accessKeySecret"));

    @Test
    void resolvesAliasToItsCanonicalChannelType() {
        ChannelTypeRegistry registry = new ChannelTypeRegistry(List.of(EMAIL, CHATAPP));

        assertThat(registry.require("chatapp")).isSameAs(CHATAPP);
        assertThat(registry.require("whatsapp")).isSameAs(CHATAPP);
    }

    @Test
    void resolvesNamesCaseInsensitivelyIgnoringSurroundingWhitespace() {
        ChannelTypeRegistry registry = new ChannelTypeRegistry(List.of(EMAIL, CHATAPP));

        assertThat(registry.require("EMail")).isSameAs(EMAIL);
        assertThat(registry.require("  WhatsApp  ")).isSameAs(CHATAPP);
    }

    @Test
    void rejectsUnregisteredChannelType() {
        ChannelTypeRegistry registry = new ChannelTypeRegistry(List.of(EMAIL, CHATAPP));

        assertThatThrownBy(() -> registry.require("sms"))
                .isInstanceOf(ChannelAccountException.class)
                .hasMessage("CHANNEL_ACCOUNT_TYPE_UNSUPPORTED");
        assertThatThrownBy(() -> registry.require(null))
                .isInstanceOf(ChannelAccountException.class)
                .hasMessage("CHANNEL_ACCOUNT_TYPE_UNSUPPORTED");
    }

    @Test
    void unionsSecretFieldsAcrossEveryRegisteredChannel() {
        ChannelTypeRegistry registry = new ChannelTypeRegistry(List.of(EMAIL, CHATAPP));

        assertThat(registry.secretFields()).containsExactlyInAnyOrder("smtpPassword", "accessKeySecret");
    }

    @Test
    void failsFastWhenTwoChannelsClaimTheSameName() {
        ChannelType rival = stub("sms", Set.of("chatapp"), Set.of());

        assertThatThrownBy(() -> new ChannelTypeRegistry(List.of(CHATAPP, rival)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claimed by both")
                .hasMessageContaining("'chatapp'");
    }

    private static ChannelType stub(String typeKey, Set<String> aliases, Set<String> secretFields) {
        return new ChannelType() {
            @Override
            public String key() {
                return typeKey;
            }

            @Override
            public Set<String> aliases() {
                return aliases;
            }

            @Override
            public Set<String> credentialFields() {
                return Set.of();
            }

            @Override
            public Set<String> secretFields() {
                return secretFields;
            }

            @Override
            public String normalizeIdentifier(String identifier) {
                return identifier;
            }

            @Override
            public Object syncAccount(UUID ownerId, UUID accountId) {
                return Map.of();
            }
        };
    }
}
