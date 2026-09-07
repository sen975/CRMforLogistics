package com.crmforlogistics.messagecenter.service.contact;

import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicItemMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppAccountResolver;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicOwnerActivityService;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicSplitReconciler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ContactServiceBeanInstantiationTest {

    @Test
    void beanFactoryUsesTheAutowiredConstructorWhenOptionalTagMapperIsPresent() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor());
        factory.registerSingleton("contactMapper", mock(ContactMapper.class));
        factory.registerSingleton("contactIdentityMapper", mock(ContactIdentityMapper.class));
        factory.registerSingleton("conversationMapper", mock(ConversationMapper.class));
        factory.registerSingleton("messageMapper", mock(MessageMapper.class));
        factory.registerSingleton("chatAppAccountResolver", mock(ChatAppAccountResolver.class));
        factory.registerSingleton("contactTagMapper", mock(ContactTagMapper.class));
        factory.registerBeanDefinition("contactService", new RootBeanDefinition(ContactService.class));

        ContactService service = factory.getBean(ContactService.class);

        assertThat(service).isNotNull();
    }

    @Test
    void beanFactoryUsesTheAutowiredConstructorForContactGroupService() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor());
        factory.registerSingleton("contactMapper", mock(ContactMapper.class));
        factory.registerSingleton("contactIdentityMapper", mock(ContactIdentityMapper.class));
        factory.registerSingleton("contactTagMapper", mock(ContactTagMapper.class));
        factory.registerSingleton("aiTopicMapper", mock(AiTopicMapper.class));
        factory.registerSingleton("aiTopicItemMapper", mock(AiTopicItemMapper.class));
        factory.registerSingleton("aiTopicOwnerActivityService", mock(AiTopicOwnerActivityService.class));
        factory.registerSingleton("aiTopicSplitReconciler", mock(AiTopicSplitReconciler.class));
        factory.registerBeanDefinition("contactGroupService", new RootBeanDefinition(ContactGroupService.class));

        ContactGroupService service = factory.getBean(ContactGroupService.class);

        assertThat(service).isNotNull();
    }
}
