package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppCamsConfigService.ConfigRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The AccessKey secret must never be recoverable from a ConfigRequest's string form. */
class WhatsAppCamsConfigServiceTest {
    private static final String SECRET = "SuperSecretAccessKeyValue1234";

    private static ConfigRequest request() {
        return new ConfigRequest("东南亚空间", "cust-1", "LTAI_ACCESS_KEY", SECRET,
                "ap-southeast-1", "cams.ap-southeast-1.aliyuncs.com", 7L, null, null);
    }

    @Test
    void toStringRedactsTheAccessKeySecret() {
        String rendered = request().toString();

        assertThat(rendered).doesNotContain(SECRET);
        assertThat(rendered).doesNotContain("SuperSecret");
        assertThat(rendered).doesNotContain("Value1234");
        assertThat(rendered).doesNotContain(SECRET.substring(0, 4));
        assertThat(rendered).doesNotContain(SECRET.substring(SECRET.length() - 4));
        assertThat(rendered).contains("accessKeySecret=[REDACTED]");
    }

    @Test
    void toStringKeepsTheNonSecretComponentsReadable() {
        String rendered = request().toString();

        assertThat(rendered).contains("东南亚空间", "cust-1", "LTAI_ACCESS_KEY",
                "ap-southeast-1", "cams.ap-southeast-1.aliyuncs.com", "7");
    }

    @Test
    void toStringRedactsSecretsForEveryConstructorAndBlankSecret() {
        String legacy = new ConfigRequest("cust-1", "LTAI_ACCESS_KEY", SECRET,
                "ap-southeast-1", "cams.ap-southeast-1.aliyuncs.com").toString();
        String blank = new ConfigRequest(null, "cust-1", null, "", null, null, null, null, null).toString();

        assertThat(legacy).doesNotContain(SECRET, "SuperSecret", "Value1234");
        assertThat(blank).doesNotContain(SECRET);
        assertThat(blank).contains("accessKeySecret=[REDACTED]");
    }
}
