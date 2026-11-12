package com.crmforlogistics.messagecenter.channel.email;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class EmailSyncSettingsDiagnosticTest {

    @Test
    void imapLoginFailureUsesStructuredAuthenticationCodeWithoutCredentials() throws Exception {
        Method method = OpenSslImapClient.class.getDeclaredMethod("cleanLoginFailure", String.class);
        method.setAccessible(true);

        String response = (String) method.invoke(null,
                "A002 NO LOGIN errno:1310, spsvr return error code(sp return:2220693)");

        assertThat(response).contains("A002 NO LOGIN")
                .doesNotContain("password")
                .doesNotContain("imapPassword");
    }
}
