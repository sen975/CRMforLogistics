package com.crmforlogistics.messagecentertest.whatsapp;

import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateGateway;
import io.minio.MinioClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

@TestConfiguration(proxyBeanMethods = false)
public class WhatsAppTemplateMediaUploadTestConfiguration {
    @Bean
    @Primary
    WhatsAppTemplateGateway mediaUploadGateway() {
        return mock(WhatsAppTemplateGateway.class);
    }

    @Bean
    @Primary
    MinioClient testMinioClient() {
        return mock(MinioClient.class);
    }
}
