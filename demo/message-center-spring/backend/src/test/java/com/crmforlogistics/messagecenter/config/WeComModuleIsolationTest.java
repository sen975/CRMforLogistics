package com.crmforlogistics.messagecenter.config;

import com.crmforlogistics.messagecenter.service.wecom.WeComCredentialMigrationRunner;
import com.crmforlogistics.messagecenter.service.wecom.WeComCredentialMigrationService;
import com.crmforlogistics.messagecenter.service.wecom.WeComAuthorizationRecovery;
import com.crmforlogistics.messagecenter.service.wecom.WeComStartupGate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import com.crmforlogistics.messagecenter.web.WeComAuthController;
import com.crmforlogistics.messagecenter.web.WeComAvatarAuthorizationController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WeComModuleIsolationTest {
    private static final String MODULE_CONDITION =
            "com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled";

    private final ApplicationContextRunner runnerContext = new ApplicationContextRunner()
            .withBean(WeComCredentialMigrationService.class,
                    () -> mock(WeComCredentialMigrationService.class))
            .withBean(WeComAuthorizationRecovery.class,
                    () -> mock(WeComAuthorizationRecovery.class))
            .withBean(WeComStartupGate.class, () -> mock(WeComStartupGate.class))
            .withUserConfiguration(WeComCredentialMigrationRunner.class);

    @Test
    void migrationRunnerIsAbsentWhenWeComModuleIsDisabled() {
        runnerContext.withPropertyValues("app.wecom-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(WeComCredentialMigrationRunner.class));
    }

    @Test
    void migrationRunnerCannotBeDisabledWhileWeComModuleIsEnabled() {
        runnerContext.withPropertyValues(
                        "app.wecom-enabled=true",
                        "app.wecom-credential-migration-enabled=false")
                .run(context -> assertThat(context)
                        .hasSingleBean(WeComCredentialMigrationRunner.class));
    }

    @Test
    void everyWeComSpringBeanUsesTheModuleCondition() throws Exception {
        Set<String> beanClasses = scanWeComBeans();
        beanClasses.add(WeComConfiguration.class.getName());

        assertThat(beanClasses).isNotEmpty();
        for (String beanClass : beanClasses) {
            Class<?> type = Class.forName(beanClass);
            boolean moduleConditional = Arrays.stream(type.getAnnotations())
                    .anyMatch(annotation -> MODULE_CONDITION.equals(
                            annotation.annotationType().getName()));
            assertThat(moduleConditional)
                    .as("%s must use @ConditionalOnWeComEnabled", beanClass)
                    .isTrue();
        }
    }

    @Test
    void weComAuthControllerFollowsTheSuiteConfigurationCondition() {
        ConditionalOnExpression condition =
                WeComAuthController.class.getAnnotation(ConditionalOnExpression.class);

        assertThat(condition).isNotNull();
        assertThat(condition.value()).contains("app.wecom-suite-id");
    }

    @Test
    void weComAvatarAuthorizationControllerFollowsTheSuiteConfigurationCondition() {
        ConditionalOnExpression condition = WeComAvatarAuthorizationController.class
                .getAnnotation(ConditionalOnExpression.class);

        assertThat(condition).isNotNull();
        assertThat(condition.value()).contains("app.wecom-suite-id");
    }

    private static Set<String> scanWeComBeans() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class, true));
        Set<String> classes = new LinkedHashSet<>();
        scanner.findCandidateComponents("com.crmforlogistics.messagecenter.channel.wecom")
                .forEach(bean -> classes.add(bean.getBeanClassName()));
        scanner.findCandidateComponents("com.crmforlogistics.messagecenter.service.wecom")
                .forEach(bean -> classes.add(bean.getBeanClassName()));
        scanner.findCandidateComponents("com.crmforlogistics.messagecenter.service.aitopic").stream()
                .map(bean -> bean.getBeanClassName())
                .filter(name -> name != null && name.substring(name.lastIndexOf('.') + 1)
                        .startsWith("WeCom"))
                .forEach(classes::add);
        scanner.findCandidateComponents("com.crmforlogistics.messagecenter.web").stream()
                .map(bean -> bean.getBeanClassName())
                .filter(name -> name != null && name.substring(name.lastIndexOf('.') + 1)
                        .startsWith("WeCom"))
                .forEach(classes::add);
        return classes;
    }
}
