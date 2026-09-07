package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecentertest.mapper.AiTopicReviewMapperTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AiTopicReviewMapperTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AiTopicReviewMapperIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Autowired
    AiTopicReviewMapper mapper;

    @Test
    void executesEverySourceUnionQueryAgainstTheMigratedSchema() {
        UUID missingId = UUID.randomUUID();

        assertThat(mapper.listSources(missingId, null, null, null, 201)).isEmpty();
        assertThat(mapper.listSourcesByIds(missingId, null, List.of(missingId))).isEmpty();
        assertThat(mapper.listTopicSources(List.of(missingId))).isEmpty();
    }
}
