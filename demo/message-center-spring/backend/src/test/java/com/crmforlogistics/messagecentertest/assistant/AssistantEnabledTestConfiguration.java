package com.crmforlogistics.messagecentertest.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 一个「功能已开启、参数取默认上限」的 {@link AssistantConfig} 替身。
 *
 * <h2>为什么这里必须是一个顶层类，而不是测试类里的嵌套 {@code @TestConfiguration}</h2>
 * 因为<b>嵌套在测试类里的 {@code @TestConfiguration} 会被组件的扫描捡走</b>。
 * 本仓库实测过这件事：把嵌套配置留在 `com.crmforlogistics.messagecenter.*` 下，
 * 全量跑时 `AppIntegrationTest` 等 `@SpringBootTest` 上下文会连带注册它们，
 * 于是多个测试类各定义一份同名 `assistantConfig`，
 * 直接以 {@code BeanDefinitionOverrideException} 把 20 个<b>无关</b>的用例打挂 ——
 * 单独跑那个测试类却全绿，症状极具误导性。
 *
 * <p>本仓库既有的 `ApplicationIntegrationTestConfiguration`、`AppConfigTestConfiguration`
 * 等全都放在 {@code com.crmforlogistics.messagecentertest}（<b>不是</b>
 * {@code com.crmforlogistics.messagecenter} 的子包，因此扫描不到）—— 这条约定就是这么来的。
 * 新增测试配置跟着放这里，别再往测试类里塞嵌套类。
 *
 * <p>bean 方法名刻意取得各不相同（`assistantEnabledConfig` / `assistantDisabledConfig` /
 * `assistantLimitsConfig`）：万一将来有人把其中两个同时 {@code @Import} 进一个上下文，
 * 撞的是 bean 名，那时才想起来改名就晚了。控制器按类型取 bean，名字随便。
 */
@TestConfiguration(proxyBeanMethods = false)
public class AssistantEnabledTestConfiguration {

    @Bean
    AssistantConfig assistantEnabledConfig() {
        return new AssistantConfig(true, "https://api.deepseek.com", "secret", "deepseek-chat",
                30, 2000, 8, 8000, 600, 3);
    }
}
