package com.crmforlogistics.messagecentertest.mapper;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.crmforlogistics.messagecenter.mapper.TodoDailyReminderMapper;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 待办提醒两个 mapper 的最小上下文：只装数据源 + MyBatis，不拉整个应用。 */
@TestConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
})
public class TodoReminderMapperTestConfiguration {
    @Bean
    MapperFactoryBean<TodoItemMapper> todoItemMapper(SqlSessionFactory factory) {
        MapperFactoryBean<TodoItemMapper> bean = new MapperFactoryBean<>(TodoItemMapper.class);
        bean.setSqlSessionFactory(factory);
        return bean;
    }

    @Bean
    MapperFactoryBean<TodoDailyReminderMapper> todoDailyReminderMapper(SqlSessionFactory factory) {
        MapperFactoryBean<TodoDailyReminderMapper> bean =
                new MapperFactoryBean<>(TodoDailyReminderMapper.class);
        bean.setSqlSessionFactory(factory);
        return bean;
    }
}
