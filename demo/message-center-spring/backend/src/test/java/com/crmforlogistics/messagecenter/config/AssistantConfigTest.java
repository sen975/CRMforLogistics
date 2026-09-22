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
 * <p>构造期校验不是洁癖：{@code maxHistoryTurns} / {@code candidateTodoLimit} 这类值
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
            assertThat(config.candidateTodoLimit()).isEqualTo(70);
            assertThat(config.pendingTtlSeconds()).isEqualTo(600);
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
                        "assistant.candidate-todo-limit=20",
                        "assistant.pending-ttl-seconds=300")
                .run(application -> {
                    AssistantConfig config = application.getBean(AssistantConfig.class);

                    assertThat(config.enabled()).isTrue();
                    assertThat(config.baseUrl()).isEqualTo("https://api.deepseek.com");
                    assertThat(config.model()).isEqualTo("deepseek-chat");
                    assertThat(config.timeoutSeconds()).isEqualTo(15);
                    assertThat(config.candidateTodoLimit()).isEqualTo(20);
                    assertThat(config.pendingTtlSeconds()).isEqualTo(300);
                });
    }

    @Test
    void outOfRangeValuesFailAtConstructionNotAtRuntime() {
        assertThatThrownBy(() -> config(0, 2000, 8, 8000, 70, 600))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timeoutSeconds");
        assertThatThrownBy(() -> config(30, 0, 8, 8000, 70, 600))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxMessageChars");
        assertThatThrownBy(() -> config(30, 20_001, 8, 8000, 70, 600))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxMessageChars");
        assertThatThrownBy(() -> config(30, 2000, 51, 8000, 70, 600))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxHistoryTurns");
        assertThatThrownBy(() -> config(30, 2000, 8, 100_001, 70, 600))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxHistoryChars");
        assertThatThrownBy(() -> config(30, 2000, 8, 8000, 0, 600))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("candidateTodoLimit");
        assertThatThrownBy(() -> config(30, 2000, 8, 8000, 600, 600))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("candidateTodoLimit");
        assertThatThrownBy(() -> config(30, 2000, 8, 8000, 70, 86401))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pendingTtlSeconds");
    }

    /**
     * 候选窗口的上限必须与服务层的硬上限对齐：允许配得更大只会得到一个被静默压回去的假配置，
     * 而「我明明配了 500」这种误解只有在排查「为什么匹配不到那条待办」时才会被发现。
     */
    @Test
    void candidateLimitCeilingMatchesTheServiceLayerHardLimit() {
        assertThat(AssistantConfig.CANDIDATE_LIMIT_CEILING)
                .isEqualTo(com.crmforlogistics.messagecenter.service.todo.TodoItemService.ASSISTANT_CANDIDATE_MAX_LIMIT);
    }

    @Test
    void zeroHistoryTurnsIsAllowed() {
        // 不带历史的一次性提问是合法用法，不该被配置校验挡下。
        assertThat(config(30, 2000, 0, 8000, 70, 600).maxHistoryTurns()).isZero();
    }

    private static AssistantConfig config(int timeoutSeconds, int maxMessageChars, int maxHistoryTurns,
                                         int maxHistoryChars, int candidateTodoLimit, int pendingTtlSeconds) {
        return new AssistantConfig(false, "", "", "gpt-4o-mini", timeoutSeconds, maxMessageChars,
                maxHistoryTurns, maxHistoryChars, candidateTodoLimit, pendingTtlSeconds, 3);
    }
}
