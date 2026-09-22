package com.crmforlogistics.messagecentertest.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 「功能未开启」的 {@link AssistantConfig} 替身（{@code enabled=false}）。
 *
 * <p>注意它替换的只是一份<b>配置值</b>，不是 {@code @ConditionalOnAssistantEnabled} 的判定本身：
 * 编排服务之所以不装配，是因为该条件注解读的正是这份配置。所以这个替身必须是真的 Bean，
 * 而不是 mock 掉服务 —— mock 掉服务就绕过了要验的那条条件。
 *
 * <p>为什么是顶层类而不是嵌套类，见 {@link AssistantEnabledTestConfiguration} 的类注释。
 */
@TestConfiguration(proxyBeanMethods = false)
public class AssistantDisabledTestConfiguration {

    @Bean
    AssistantConfig assistantDisabledConfig() {
        return new AssistantConfig(false, "", "", "gpt-4o-mini", 30, 2000, 8, 8000, 70, 600, 3);
    }
}
