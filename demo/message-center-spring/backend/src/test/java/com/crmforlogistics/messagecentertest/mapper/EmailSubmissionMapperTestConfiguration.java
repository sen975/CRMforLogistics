package com.crmforlogistics.messagecentertest.mapper;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.crmforlogistics.messagecenter.mapper.EmailSubmissionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration({DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class, MybatisPlusAutoConfiguration.class})
public class EmailSubmissionMapperTestConfiguration {
    @Bean
    MapperFactoryBean<ContactIdentityMapper> contactIdentityMapper(SqlSessionFactory factory) {
        MapperFactoryBean<ContactIdentityMapper> bean = new MapperFactoryBean<>(ContactIdentityMapper.class);
        bean.setSqlSessionFactory(factory);
        return bean;
    }

    @Bean
    MapperFactoryBean<ChannelAccountMapper> channelAccountMapper(SqlSessionFactory factory) {
        MapperFactoryBean<ChannelAccountMapper> bean = new MapperFactoryBean<>(ChannelAccountMapper.class);
        bean.setSqlSessionFactory(factory);
        return bean;
    }

    @Bean
    MapperFactoryBean<ConversationMapper> conversationMapper(SqlSessionFactory factory) {
        MapperFactoryBean<ConversationMapper> bean = new MapperFactoryBean<>(ConversationMapper.class);
        bean.setSqlSessionFactory(factory);
        return bean;
    }

    @Bean
    MapperFactoryBean<MessageMapper> messageMapper(SqlSessionFactory factory) {
        MapperFactoryBean<MessageMapper> bean = new MapperFactoryBean<>(MessageMapper.class);
        bean.setSqlSessionFactory(factory);
        return bean;
    }
    @Bean
    MapperFactoryBean<EmailSubmissionMapper> emailSubmissionMapper(SqlSessionFactory factory) {
        MapperFactoryBean<EmailSubmissionMapper> bean =
                new MapperFactoryBean<>(EmailSubmissionMapper.class);
        bean.setSqlSessionFactory(factory);
        return bean;
    }
}
