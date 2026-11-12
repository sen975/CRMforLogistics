package com.crmforlogistics.messagecentertest.mapper;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.crmforlogistics.messagecenter.mapper.WeComContactEventMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 客户联系事件 mapper 的最小上下文：只装数据源 + MyBatis，不拉整个应用。 */
@TestConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
})
public class WeComContactEventMapperTestConfiguration {
    @Bean
    MapperFactoryBean<WeComContactEventMapper> weComContactEventMapper(SqlSessionFactory factory) {
        MapperFactoryBean<WeComContactEventMapper> bean = new MapperFactoryBean<>(WeComContactEventMapper.class);
        bean.setSqlSessionFactory(factory);
        return bean;
    }
}
