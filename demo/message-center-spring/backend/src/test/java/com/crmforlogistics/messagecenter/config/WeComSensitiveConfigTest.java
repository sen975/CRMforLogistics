package com.crmforlogistics.messagecenter.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class WeComSensitiveConfigTest {
    private static final Path MAIN_YAML = Path.of("src/main/resources/application.yml");
    private static final Path DEV_YAML = Path.of("src/main/resources/application-dev.yml");
    private static final Pattern SECRET_LINE = Pattern.compile(
            "(?m)^\\s*wecom-(?:suite-secret|login-suite-secret|secret|token|encoding-aes-key):"
                    + "([^#\\r\\n]*)$");

    @Test
    void baseYamlOwnsEnvironmentOnlyWeComContracts() throws IOException {
        String yaml = Files.readString(MAIN_YAML);

        assertThat(yaml).doesNotContain("active: dev");
        assertThat(yaml).contains("wecom-suite-id: ${WECOM_SUITE_ID:}");
        assertThat(yaml).contains("wecom-suite-secret: ${WECOM_SUITE_SECRET:}");
        assertThat(yaml).contains("wecom-token: ${WECOM_TOKEN:}");
        assertThat(yaml).contains("wecom-encoding-aes-key: ${WECOM_ENCODING_AES_KEY:}");
        assertThat(yaml).contains("wecom-callback-receive-id: ${WECOM_CALLBACK_RECEIVE_ID:}");
        assertThat(yaml).doesNotContain("wecom-login-suite-id");
        assertThat(yaml).doesNotContain("wecom-login-suite-secret");
        assertThat(yaml).doesNotContain("WECOM_LOGIN_SUITE_ID");
        assertThat(yaml).doesNotContain("WECOM_LOGIN_SUITE_SECRET");
        assertThat(yaml).contains("wecom-login-auth-corp-id: ${WECOM_LOGIN_AUTH_CORP_ID:}");
        assertThat(yaml).contains("wecom-login-redirect-uri: ${WECOM_LOGIN_REDIRECT_URI:http://localhost:5173/login}");
        assertThat(yaml).contains("wecom-allowed-jsapi-origins: ${WECOM_ALLOWED_JSAPI_ORIGINS:}");
        assertThat(yaml).contains("wecom-chatdata-program-id: ${WECOM_CHATDATA_PROGRAM_ID:}");
        assertThat(yaml).contains("wecom-chatdata-ability-id: ${WECOM_CHATDATA_ABILITY_ID:conversation_viewer_sync}");
        assertThat(yaml).contains("wecom-chatdata-private-key-file: ${WECOM_CHATDATA_PRIVATE_KEY_FILE:}");
        assertThat(yaml).contains("org.springframework.web.client: INFO");
        assertThat(yaml).contains("com.aliyun: ERROR");
    }

    @Test
    void productionYamlImportsLegacyDotEnvChannelSettings() throws IOException {
        String yaml = Files.readString(MAIN_YAML);

        assertThat(yaml).contains("import: optional:file:.env[.properties]");
        assertThat(yaml).contains("cust-space-id: ${APP_CUST_SPACE_ID:${CAMS_CUST_SPACE_ID:}}");
        assertThat(yaml).contains("chatapp-from: ${APP_CHATAPP_FROM:${CHATAPP_FROM:}}");
        assertThat(yaml).contains("aliyun-access-key-id: ${APP_ALIYUN_ACCESS_KEY_ID:${ALIYUN_ACCESS_KEY_ID:}}");
        assertThat(yaml).contains("aliyun-access-key-secret: ${APP_ALIYUN_ACCESS_KEY_SECRET:${ALIYUN_ACCESS_KEY_SECRET:}}");
        assertThat(yaml).contains("email-sync-enabled: ${APP_EMAIL_SYNC_ENABLED:false}");
        assertThat(yaml).contains("smtp-user: ${APP_SMTP_USER:${SMTP_USER:}}");
        assertThat(yaml).contains("imap-user: ${APP_IMAP_USER:${IMAP_USER:}}");
    }

    @Test
    void devProfileDoesNotBlockRuntimeChannelOverrides() throws IOException {
        String yaml = Files.readString(DEV_YAML);

        assertThat(yaml).contains("local-dev-mode: ${APP_LOCAL_DEV_MODE:true}");
        assertThat(yaml).contains("email-sync-enabled: ${APP_EMAIL_SYNC_ENABLED:false}");
        assertThat(yaml).contains(
                "wecom-chatdata-auto-sync-enabled: ${WECOM_CHATDATA_AUTO_SYNC_ENABLED:false}");
    }

    @Test
    void trackedYamlContainsNoLiteralWeComSecretsOrStoredCredentials() throws IOException {
        String yaml = Files.readString(MAIN_YAML) + "\n" + Files.readString(DEV_YAML);

        boolean containsLiteral = SECRET_LINE.matcher(yaml).results()
                .map(result -> result.group(1).trim())
                .anyMatch(value -> !value.isEmpty() && !value.startsWith("${"));
        assertThat(containsLiteral).isFalse();
        boolean containsStoredCredentialField = Pattern.compile(
                        "(?m)^\\s*(?:permanent_code|secret_key):")
                .matcher(yaml.toLowerCase())
                .find();
        assertThat(containsStoredCredentialField).isFalse();
    }
}
