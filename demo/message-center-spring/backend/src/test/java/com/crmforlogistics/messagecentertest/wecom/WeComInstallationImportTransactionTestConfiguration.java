package com.crmforlogistics.messagecentertest.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement
@ConditionalOnWeComEnabled
public class WeComInstallationImportTransactionTestConfiguration {
}
