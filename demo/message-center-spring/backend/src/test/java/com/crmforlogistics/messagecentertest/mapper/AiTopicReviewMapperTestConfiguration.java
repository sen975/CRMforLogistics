package com.crmforlogistics.messagecentertest.mapper;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.crmforlogistics.messagecenter.mapper.AiTopicReviewMapper;
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
public class AiTopicReviewMapperTestConfiguration {
    @Bean
    MapperFactoryBean<AiTopicReviewMapper> aiTopicReviewMapper(SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<AiTopicReviewMapper> mapperFactory =
                new MapperFactoryBean<>(AiTopicReviewMapper.class);
        mapperFactory.setSqlSessionFactory(sqlSessionFactory);
        return mapperFactory;
    }
}
