package com.crmforlogistics.messagecentertest.mapper;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastJobMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastRecipientMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastReconciliationEvidenceMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
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
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
})
public class ChatAppBroadcastPersistenceTestConfiguration {
    @Bean
    MapperFactoryBean<ChatAppBroadcastMapper> broadcastMapper(SqlSessionFactory factory) {
        return mapper(factory, ChatAppBroadcastMapper.class);
    }

    @Bean
    MapperFactoryBean<ChatAppBroadcastJobMapper> jobMapper(SqlSessionFactory factory) {
        return mapper(factory, ChatAppBroadcastJobMapper.class);
    }

    @Bean
    MapperFactoryBean<ChatAppBroadcastRecipientMapper> recipientMapper(SqlSessionFactory factory) {
        return mapper(factory, ChatAppBroadcastRecipientMapper.class);
    }

    @Bean
    MapperFactoryBean<ChatAppBroadcastReconciliationEvidenceMapper> evidenceMapper(
            SqlSessionFactory factory) {
        return mapper(factory, ChatAppBroadcastReconciliationEvidenceMapper.class);
    }

    @Bean
    MapperFactoryBean<ContactIdentityMapper> contactIdentityMapper(SqlSessionFactory factory) {
        return mapper(factory, ContactIdentityMapper.class);
    }

    @Bean
    MapperFactoryBean<MessageMapper> messageMapper(SqlSessionFactory factory) {
        return mapper(factory, MessageMapper.class);
    }

    private static <T> MapperFactoryBean<T> mapper(SqlSessionFactory factory, Class<T> type) {
        MapperFactoryBean<T> mapper = new MapperFactoryBean<>(type);
        mapper.setSqlSessionFactory(factory);
        return mapper;
    }
}
