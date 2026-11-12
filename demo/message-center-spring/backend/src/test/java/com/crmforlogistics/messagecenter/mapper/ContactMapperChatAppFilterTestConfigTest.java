package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.session.SqlSessionFactory;
import com.crmforlogistics.messagecentertest.mapper.ContactMapperChatAppFilterTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ContactMapperChatAppFilterTestConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(
                    "mybatis-plus.type-handlers-package=com.crmforlogistics.messagecenter.typehandler")
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withUserConfiguration(ContactMapperChatAppFilterTestConfiguration.class);

    @Test
    void providesMyBatisSessionInfrastructureForContactMapper() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SqlSessionFactory.class);
            assertThat(context).hasSingleBean(ContactMapper.class);
        });
    }
}
