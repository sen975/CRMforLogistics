package com.crmforlogistics.messagecentertest.whatsapp;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppAccountSyncService;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppCamsConfigService;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppOnboardingGateway;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Base64;

import static org.mockito.Mockito.mock;

/**
 * Lives here rather than beside its test on purpose: classes under
 * {@code com.crmforlogistics.messagecenter} are picked up by the application's component scan, and
 * these mapper beans defined there collide with {@code @MapperScan} in the full-app integration
 * contexts.
 *
 * <p>The sync service is handed the real {@link PlatformTransactionManager} so its own
 * {@code TransactionTemplate} opens a genuine transaction; the defect under test (a rolled-back
 * FAILED projection) only manifests when the mappers run against one.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppConfig.class)
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
})
public class AdminWhatsAppAccountSyncServiceFailurePersistenceTestConfiguration {

    @Bean
    MapperFactoryBean<WhatsAppProviderScopeMapper> whatsAppProviderScopeMapper(SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<WhatsAppProviderScopeMapper> mapperFactory =
                new MapperFactoryBean<>(WhatsAppProviderScopeMapper.class);
        mapperFactory.setSqlSessionFactory(sqlSessionFactory);
        return mapperFactory;
    }

    @Bean
    MapperFactoryBean<ChannelAccountMapper> channelAccountMapper(SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<ChannelAccountMapper> mapperFactory =
                new MapperFactoryBean<>(ChannelAccountMapper.class);
        mapperFactory.setSqlSessionFactory(sqlSessionFactory);
        return mapperFactory;
    }

    @Bean
    WhatsAppOnboardingGateway syncFailureGateway() {
        return mock(WhatsAppOnboardingGateway.class);
    }

    @Bean
    CredentialCipher credentialCipher() {
        return CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Bean
    WhatsAppCamsConfigService whatsAppCamsConfigService(AppConfig config, WhatsAppProviderScopeMapper scopes,
                                                        CredentialCipher cipher,
                                                        WhatsAppOnboardingGateway gateway) {
        return new WhatsAppCamsConfigService(config, scopes, cipher, gateway);
    }

    @Bean
    AdminWhatsAppAccountSyncService adminWhatsAppAccountSyncService(AppConfig config,
                                                                    WhatsAppOnboardingGateway gateway,
                                                                    WhatsAppProviderScopeMapper scopes,
                                                                    ChannelAccountMapper accounts,
                                                                    WhatsAppCamsConfigService camsConfig,
                                                                    PlatformTransactionManager transactionManager) {
        return new AdminWhatsAppAccountSyncService(config, gateway, scopes, accounts, null, camsConfig,
                transactionManager);
    }
}
