package com.crmforlogistics.messagecentertest.mapper;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.crmforlogistics.messagecenter.mapper.AssistantActionAuditMapper;
import com.crmforlogistics.messagecenter.mapper.AssistantPendingActionMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 助手两张表的 mapper 最小上下文：只装数据源 + MyBatis，不拉整个应用。 */
@TestConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
})
public class AssistantMapperTestConfiguration {
    @Bean
    MapperFactoryBean<AssistantPendingActionMapper> assistantPendingActionMapper(SqlSessionFactory factory) {
        MapperFactoryBean<AssistantPendingActionMapper> bean =
                new MapperFactoryBean<>(AssistantPendingActionMapper.class);
        bean.setSqlSessionFactory(factory);
        return bean;
    }

    @Bean
    MapperFactoryBean<AssistantActionAuditMapper> assistantActionAuditMapper(SqlSessionFactory factory) {
        MapperFactoryBean<AssistantActionAuditMapper> bean =
                new MapperFactoryBean<>(AssistantActionAuditMapper.class);
        bean.setSqlSessionFactory(factory);
        return bean;
    }
}
