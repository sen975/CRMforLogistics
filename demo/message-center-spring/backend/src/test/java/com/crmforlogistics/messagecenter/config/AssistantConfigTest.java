package com.crmforlogistics.messagecenter.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 助手配置：默认关闭、边界在构造期就抛。
 *
 * <p>构造期校验不是洁癖：{@code maxHistoryTurns} 这类值
 * 直接决定每轮的提示词体积与调用成本，配错一个量级不会报错，只会让 token 数悄悄涨十倍。
 */
class AssistantConfigTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Binding.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AssistantConfig.class)
    static class Binding {
    }

    @Test
    void defaultsAreDisabledAndBounded() {
        context.run(application -> {
            AssistantConfig config = application.getBean(AssistantConfig.class);

            assertThat(config.enabled()).as("默认关闭：先灰度后端、不发布前端应当是默认状态").isFalse();
            assertThat(config.baseUrl()).isEmpty();
            assertThat(config.apiKey()).isEmpty();
            assertThat(config.model()).isEqualTo("gpt-4o-mini");
            assertThat(config.timeoutSeconds()).isEqualTo(30);
            assertThat(config.maxMessageChars()).isEqualTo(2000);
            assertThat(config.maxHistoryTurns()).isEqualTo(8);
            assertThat(config.maxHistoryChars()).isEqualTo(8000);
            assertThat(config.pendingTtlSeconds()).isEqualTo(600);
            assertThat(config.maxReadTurns()).as("默认 3：够一次检索加一次追问，又不至于让单轮成本失控")
                    .isEqualTo(3);
        });
    }

    @Test
    void bindsExplicitValues() {
        context.withPropertyValues(
                        "assistant.enabled=true",
                        "assistant.base-url=https://api.deepseek.com",
                        "assistant.api-key=secret",
                        "assistant.model=deepseek-chat",
                        "assistant.timeout-seconds=15",
                        "assistant.max-message-chars=500",
                        "assistant.max-history-turns=4",
                        "assistant.max-history-chars=2000",
                        "assistant.pending-ttl-seconds=300")
                .run(application -> {
                    AssistantConfig config = application.getBean(AssistantConfig.class);

                    assertThat(config.enabled()).isTrue();
                    assertThat(config.baseUrl()).isEqualTo("https://api.deepseek.com");
                    assertThat(config.model()).isEqualTo("deepseek-chat");
                    assertThat(config.timeoutSeconds()).isEqualTo(15);
                    assertThat(config.pendingTtlSeconds()).isEqualTo(300);
                });
    }

    @Test
    void outOfRangeValuesFailAtConstructionNotAtRuntime() {
        assertThatThrownBy(() -> config(0, 2000, 8, 8000, 600))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timeoutSeconds");
        assertThatThrownBy(() -> config(30, 0, 8, 8000, 600))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxMessageChars");
        assertThatThrownBy(() -> config(30, 20_001, 8, 8000, 600))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxMessageChars");
        assertThatThrownBy(() -> config(30, 2000, 51, 8000, 600))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxHistoryTurns");
        assertThatThrownBy(() -> config(30, 2000, 8, 100_001, 600))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxHistoryChars");
        assertThatThrownBy(() -> config(30, 2000, 8, 8000, 86401))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pendingTtlSeconds");
    }

    @Test
    void zeroHistoryTurnsIsAllowed() {
        // 不带历史的一次性提问是合法用法，不该被配置校验挡下。
        assertThat(config(30, 2000, 0, 8000, 600).maxHistoryTurns()).isZero();
    }

    /**
     * 只读轨的两个特殊档位：{@code 0} 是回滚开关，{@code 1} 合法但不可用于「部分回滚」。
     *
     * <p>这条测试锁的不是行为（配 1 会让模型不检索，那是走查实测的模型行为，单测测不到），
     * 而是<b>下界的宽度</b>：两个值都必须能配出来。理由是这条例外的代价不对称 ——
     * 把下界提到 2 不会有人报错，只会让一个真实跑过探针的档位静默消失，
     * 而下一次需要它的人会先怀疑是不是自己配错了。
     */
    @Test
    void bothZeroAndOneAreAcceptedEvenThoughOnlyZeroIsTheRollbackSwitch() {
        assertThat(configWithReadTurns(0).maxReadTurns()).as("0 是回滚开关：整条只读轨关掉").isZero();
        assertThat(configWithReadTurns(1).maxReadTurns()).as("1 合法，只是不可用于部分回滚").isEqualTo(1);
        assertThat(configWithReadTurns(AssistantConfig.MAX_READ_TURNS).maxReadTurns())
                .isEqualTo(AssistantConfig.MAX_READ_TURNS);
    }

    @Test
    void readTurnsOutsideTheSupportedRangeFailAtConstruction() {
        assertThatThrownBy(() -> configWithReadTurns(-1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxReadTurns");
        assertThatThrownBy(() -> configWithReadTurns(AssistantConfig.MAX_READ_TURNS + 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxReadTurns");
    }

    private static AssistantConfig config(int timeoutSeconds, int maxMessageChars, int maxHistoryTurns,
                                         int maxHistoryChars, int pendingTtlSeconds) {
        return new AssistantConfig(false, "", "", "gpt-4o-mini", timeoutSeconds, maxMessageChars,
                maxHistoryTurns, maxHistoryChars, pendingTtlSeconds, 3);
    }

    private static AssistantConfig configWithReadTurns(int maxReadTurns) {
        return new AssistantConfig(false, "", "", "gpt-4o-mini", 30, 2000, 8, 8000, 600, maxReadTurns);
    }
}
