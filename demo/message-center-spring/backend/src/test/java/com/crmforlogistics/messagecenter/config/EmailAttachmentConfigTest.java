package com.crmforlogistics.messagecenter.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailAttachmentConfigTest {

    @Test
    void bindsAttachmentLimitsWithProductionDefaults() {
        var environment = new MockEnvironment()
                .withProperty("app.email-attachment-max-count", "16")
                .withProperty("app.email-attachment-max-total-bytes", "20971520");
        var config = Binder.get(environment)
                .bind("app", AppConfig.class).orElseThrow(AssertionError::new);

        assertEquals(16, config.emailAttachmentMaxCount());
        assertEquals(20_971_520L, config.emailAttachmentMaxTotalBytes());
    }
}
