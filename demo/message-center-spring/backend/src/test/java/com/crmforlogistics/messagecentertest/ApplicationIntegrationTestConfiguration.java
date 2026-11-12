package com.crmforlogistics.messagecentertest;

import io.minio.MinioClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

@TestConfiguration(proxyBeanMethods = false)
public class ApplicationIntegrationTestConfiguration {
    @Bean
    @Primary
    MinioClient testMinioClient() {
        return mock(MinioClient.class);
    }
}
