package com.crmforlogistics.messagecentertest.mapper;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.crmforlogistics.messagecenter.mapper.UserMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Lives here rather than beside its test on purpose: classes under
 * {@code com.crmforlogistics.messagecenter} are picked up by the application's component scan, and a
 * {@code userMapper} bean defined there collides with {@code @MapperScan} in the full-app
 * integration contexts.
 */
@TestConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
})
public class UserMapperAssignableTargetTestConfiguration {
    @Bean
    MapperFactoryBean<UserMapper> userMapper(SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<UserMapper> mapperFactory = new MapperFactoryBean<>(UserMapper.class);
        mapperFactory.setSqlSessionFactory(sqlSessionFactory);
        return mapperFactory;
    }
}
