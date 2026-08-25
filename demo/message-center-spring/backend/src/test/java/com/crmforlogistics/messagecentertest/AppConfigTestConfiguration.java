package com.crmforlogistics.messagecentertest;

import com.crmforlogistics.messagecenter.config.AppConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppConfig.class)
public class AppConfigTestConfiguration {
}
