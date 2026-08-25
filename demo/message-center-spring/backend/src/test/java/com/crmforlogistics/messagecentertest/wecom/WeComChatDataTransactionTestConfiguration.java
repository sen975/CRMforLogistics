package com.crmforlogistics.messagecentertest.wecom;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataCursorMapper;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.crmforlogistics.messagecenter.service.wecom.WeComChatDataStore;
import com.crmforlogistics.messagecenter.service.wecom.WeComCredentialProtector;
import com.crmforlogistics.messagecenter.service.wecom.WeComExternalContactService;
import com.crmforlogistics.messagecenter.service.wecom.WeComMessageProjector;
import java.util.Base64;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.mockito.Mockito.mock;

@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnWeComEnabled
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
})
public class WeComChatDataTransactionTestConfiguration {
    @Bean
    MapperFactoryBean<WeComChatDataMessageMapper> chatDataMessageMapper(SqlSessionFactory factory) {
        return mapper(factory, WeComChatDataMessageMapper.class);
    }

    @Bean
    MapperFactoryBean<WeComChatDataCursorMapper> chatDataCursorMapper(SqlSessionFactory factory) {
        return mapper(factory, WeComChatDataCursorMapper.class);
    }

    @Bean
    MapperFactoryBean<ChannelAccountMapper> channelAccountMapper(SqlSessionFactory factory) {
        return mapper(factory, ChannelAccountMapper.class);
    }

    @Bean
    MapperFactoryBean<ContactIdentityMapper> contactIdentityMapper(SqlSessionFactory factory) {
        return mapper(factory, ContactIdentityMapper.class);
    }

    @Bean
    MapperFactoryBean<ContactMapper> contactMapper(SqlSessionFactory factory) {
        return mapper(factory, ContactMapper.class);
    }

    @Bean
    MapperFactoryBean<ConversationMapper> conversationMapper(SqlSessionFactory factory) {
        return mapper(factory, ConversationMapper.class);
    }

    @Bean
    MapperFactoryBean<MessageMapper> messageMapper(SqlSessionFactory factory) {
        return mapper(factory, MessageMapper.class);
    }

    @Bean
    WeComCredentialProtector credentialProtector() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        return new WeComCredentialProtector(CredentialCipher.fromBase64Key(key));
    }

    @Bean
    EventHub eventHub() {
        return mock(EventHub.class);
    }

    @Bean
    WeComMessageProjector projector(ChannelAccountMapper accounts,
                                    ContactIdentityMapper identities,
                                    ContactMapper contacts,
                                    ConversationMapper conversations,
                                    MessageMapper messages) {
        return new WeComMessageProjector(
                accounts, identities, contacts, conversations, messages, mock(WeComExternalContactService.class));
    }

    @Bean
    WeComChatDataStore store(WeComChatDataMessageMapper messages,
                             WeComChatDataCursorMapper cursors,
                             WeComCredentialProtector protector,
                             WeComMessageProjector projector,
                             EventHub events) {
        return new WeComChatDataStore(
                messages, cursors, protector, projector, events, null, null, null, null, null, null);
    }

    private static <T> MapperFactoryBean<T> mapper(SqlSessionFactory factory, Class<T> type) {
        MapperFactoryBean<T> mapper = new MapperFactoryBean<>(type);
        mapper.setSqlSessionFactory(factory);
        return mapper;
    }
}
