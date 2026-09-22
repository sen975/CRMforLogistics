package com.crmforlogistics.messagecentertest.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 「输入上限压到 50 字符」的 {@link AssistantConfig} 替身。
 *
 * <p>存在的理由是让「输入超长 → 400」这条用例用一行文本就能构造出来，
 * 而不是往请求里塞 2000 个字符 —— 后者一旦断言失败，失败信息本身就没法看。
 *
 * <p>为什么是顶层类而不是嵌套类，见 {@link AssistantEnabledTestConfiguration} 的类注释。
 */
@TestConfiguration(proxyBeanMethods = false)
public class AssistantMessageLimitTestConfiguration {

    /** 与 {@code AssistantControllerTest} 的断言配套；改这里要同步改那边的 {@code "x".repeat(...)}。 */
    public static final int MAX_MESSAGE_CHARS = 50;

    @Bean
    AssistantConfig assistantLimitsConfig() {
        return new AssistantConfig(true, "https://api.deepseek.com", "secret", "deepseek-chat",
                30, MAX_MESSAGE_CHARS, 8, 200, 70, 600, 3);
    }
}
