package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void chatAppMessageAutoSyncIsEnabledByDefault() {
        assertTrue(new Config(new HashMap<>()).chatappMessageAutoSyncEnabled());
    }

    @Test
    void localWeComModeDefaultsOffAndBindsToLoopbackWhenEnabled() {
        Config defaults = new Config(Map.of());
        assertFalse(defaults.localDevMode());
        assertEquals("0.0.0.0", defaults.webBindAddress());

        Config local = new Config(Map.of("LOCAL_DEV_MODE", "true"));
        assertTrue(local.localDevMode());
        assertEquals("127.0.0.1", local.webBindAddress());
        assertEquals("fixture", local.localWeComDataSource());
    }

    @Test
    void localWeComModeRejectsNonLoopbackAndUnknownSource() {
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("LOCAL_DEV_MODE", "true", "WEB_BIND_ADDRESS", "0.0.0.0"))
                        .webBindAddress());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("LOCAL_WECOM_DATA_SOURCE", "upstream"))
                        .localWeComDataSource());
    }

    @Test
    void chatAppMessageAutoSyncCanBeDisabled() {
        assertFalse(new Config(new HashMap<>(Map.of(
                "CHATAPP_MESSAGE_AUTO_SYNC_ENABLED", "false"
        ))).chatappMessageAutoSyncEnabled());
    }

    @Test
    void chatAppMessageAutoSyncRejectsNonBooleanValues() {
        Config config = new Config(new HashMap<>(Map.of(
                "CHATAPP_MESSAGE_AUTO_SYNC_ENABLED", "yes"
        )));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                config::chatappMessageAutoSyncEnabled);
        assertEquals("CHATAPP_MESSAGE_AUTO_SYNC_ENABLED must be true or false", error.getMessage());
    }

    @Test
    void exposesBoundedLocalCallRecordAndFunAsrSettings() {
        Config config = new Config(Map.ofEntries(
                Map.entry("DATA_DIR", tempDir.toString()),
                Map.entry("CALL_RECORD_MAX_AUDIO_BYTES", "104857600"),
                Map.entry("CALL_RECORD_MAX_DURATION_SECONDS", "7200"),
                Map.entry("CALL_RECORD_STORAGE_MAX_BYTES", "10737418240"),
                Map.entry("CALL_RECORD_MAX_RECORDS", "10000"),
                Map.entry("CALL_RECORD_QUEUE_CAPACITY", "64"),
                Map.entry("CALL_RECORD_WORKER_CONCURRENCY", "1"),
                Map.entry("CALL_RECORD_LEASE_SECONDS", "2100"),
                Map.entry("CALL_RECORD_MAX_ATTEMPTS", "3"),
                Map.entry("CALL_RECORD_MAX_RESPONSE_BYTES", "10485760"),
                Map.entry("CALL_RECORD_MAX_SEGMENTS", "20000"),
                Map.entry("CALL_RECORD_MAX_REVISIONS", "20"),
                Map.entry("CALL_AUDIO_SESSION_TTL_SECONDS", "300"),
                Map.entry("CALL_AUDIO_SESSION_MAX_PER_ACTOR", "8"),
                Map.entry("CALL_AUDIO_SESSION_MAX_ACTIVE", "256"),
                Map.entry("FUNASR_BASE_URL", "http://funasr:8000"),
                Map.entry("FUNASR_MODEL", "sensevoice"),
                Map.entry("FUNASR_CONNECT_TIMEOUT_SECONDS", "3"),
                Map.entry("FUNASR_REQUEST_TIMEOUT_SECONDS", "1800")
        ));

        assertEquals(tempDir.resolve("call-records"), config.callRecordDataDir());
        assertEquals(104857600L, config.callRecordMaxAudioBytes());
        assertEquals(7200, config.callRecordMaxDurationSeconds());
        assertEquals(10737418240L, config.callRecordStorageMaxBytes());
        assertEquals(10000, config.callRecordMaxRecords());
        assertEquals(64, config.callRecordQueueCapacity());
        assertEquals(1, config.callRecordWorkerConcurrency());
        assertEquals(2100, config.callRecordLeaseSeconds());
        assertEquals(3, config.callRecordMaxAttempts());
        assertEquals(10485760L, config.callRecordMaxResponseBytes());
        assertEquals(20000, config.callRecordMaxSegments());
        assertEquals(20, config.callRecordMaxRevisions());
        assertEquals(300, config.callAudioSessionTtlSeconds());
        assertEquals(8, config.callAudioSessionMaxPerActor());
        assertEquals(256, config.callAudioSessionMaxActive());
        assertFalse(config.callAudioCookieSecure());
        assertEquals(java.net.URI.create("http://funasr:8000"), config.funAsrBaseUri());
        assertEquals("sensevoice", config.funAsrModel());
        assertEquals(java.time.Duration.ofSeconds(3), config.funAsrConnectTimeout());
        assertEquals(java.time.Duration.ofSeconds(1800), config.funAsrRequestTimeout());
    }

    @Test
    void acceptsOnlyPrivateOrSidecarFunAsrHosts() {
        for (String url : List.of(
                "http://localhost:8000",
                "http://funasr:8000",
                "https://speech.internal",
                "http://speech.local:8000",
                "http://127.0.0.1:8000",
                "http://10.20.30.40:8000",
                "http://172.16.0.1:8000",
                "http://172.31.255.254:8000",
                "http://192.168.1.10:8000")) {
            assertEquals(java.net.URI.create(url),
                    new Config(Map.of("FUNASR_BASE_URL", url)).funAsrBaseUri());
        }

        for (String url : List.of(
                "https://api.example.com",
                "http://172.15.0.1:8000",
                "http://172.32.0.1:8000",
                "http://169.254.1.1:8000",
                "http://[2001:db8::1]:8000",
                "http://user:pass@funasr:8000",
                "http://funasr:8000/v1",
                "http://funasr:8000?model=sensevoice")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Config(Map.of("FUNASR_BASE_URL", url)).funAsrBaseUri(), url);
        }
    }

    @Test
    void derivesCallAudioSecureCookieOnlyFromConfiguredLoginRedirectUri() {
        Config https = new Config(Map.of(
                "WECOM_ALLOWED_JSAPI_ORIGINS", "https://crm.example.com",
                "WECOM_LOGIN_REDIRECT_URI", "https://crm.example.com/"));
        assertTrue(https.callAudioCookieSecure());

        Config localHttp = new Config(Map.of(
                "WECOM_ALLOWED_JSAPI_ORIGINS", "http://localhost:8099",
                "WECOM_LOGIN_REDIRECT_URI", "http://localhost:8099/"));
        assertFalse(localHttp.callAudioCookieSecure());

        Config localDevelopment = new Config(Map.of(
                "LOCAL_DEV_MODE", "true",
                "WECOM_ALLOWED_JSAPI_ORIGINS", "https://crm.example.com",
                "WECOM_LOGIN_REDIRECT_URI", "https://crm.example.com/"));
        assertFalse(localDevelopment.callAudioCookieSecure(),
                "loopback HTTP development must not emit a Secure audio cookie");
    }

    @Test
    void rejectsUnsafeCallRecordAndFunAsrSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("CALL_RECORD_WORKER_CONCURRENCY", "0"))
                        .callRecordWorkerConcurrency());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("CALL_RECORD_MAX_AUDIO_BYTES", "104857601"))
                        .callRecordMaxAudioBytes());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("CALL_RECORD_MAX_DURATION_SECONDS", "7201"))
                        .callRecordMaxDurationSeconds());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("FUNASR_MODEL", "unknown")).funAsrModel());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("FUNASR_REQUEST_TIMEOUT_SECONDS", "3601"))
                        .funAsrRequestTimeout());
    }

    @Test
    void exposesBoundedChatAppTemplateAutoSyncSettings() {
        Config defaults = new Config(Map.of("CUST_SPACE_ID", "space-1"));
        assertEquals(true, defaults.chatappTemplateAutoSyncEnabled());
        assertEquals(300, defaults.chatappTemplateSyncIntervalSeconds());
        assertEquals(50, defaults.chatappTemplatePageSize());
        assertEquals(40, defaults.chatappTemplateMaxPages());
        assertEquals(true, defaults.hasChatAppTemplateSyncConfiguration());

        Config configured = new Config(Map.ofEntries(
                Map.entry("CUST_SPACE_ID", "space-1"),
                Map.entry("CHATAPP_TEMPLATE_AUTO_SYNC_ENABLED", "false"),
                Map.entry("CHATAPP_TEMPLATE_SYNC_INTERVAL_SECONDS", "600"),
                Map.entry("TEMPLATE_PAGE_SIZE", "25"),
                Map.entry("TEMPLATE_MAX_PAGES", "12")
        ));
        assertEquals(false, configured.chatappTemplateAutoSyncEnabled());
        assertEquals(600, configured.chatappTemplateSyncIntervalSeconds());
        assertEquals(25, configured.chatappTemplatePageSize());
        assertEquals(12, configured.chatappTemplateMaxPages());
        assertEquals(false, new Config(Map.of()).hasChatAppTemplateSyncConfiguration());
    }

    @Test
    void rejectsUnsafeChatAppTemplateAutoSyncSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("CHATAPP_TEMPLATE_AUTO_SYNC_ENABLED", "yes"))
                        .chatappTemplateAutoSyncEnabled());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("CHATAPP_TEMPLATE_SYNC_INTERVAL_SECONDS", "299"))
                        .chatappTemplateSyncIntervalSeconds());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("CHATAPP_TEMPLATE_SYNC_INTERVAL_SECONDS", "601"))
                        .chatappTemplateSyncIntervalSeconds());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("TEMPLATE_PAGE_SIZE", "51"))
                        .chatappTemplatePageSize());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("TEMPLATE_MAX_PAGES", "41"))
                        .chatappTemplateMaxPages());
    }

    @Test
    void exposesDatabaseMinioAndBoundedWorkerSettings() {
        Config config = new Config(Map.ofEntries(
                Map.entry("DATABASE_URL", "jdbc:postgresql://localhost:5432/message_center"),
                Map.entry("DATABASE_USER", "message_center"),
                Map.entry("DATABASE_PASSWORD_FILE", "/run/secrets/postgres_password"),
                Map.entry("CREDENTIAL_MASTER_KEY_FILE", "/run/secrets/credential_master_key"),
                Map.entry("MINIO_ENDPOINT", "http://localhost:9000"),
                Map.entry("MINIO_ACCESS_KEY_FILE", "/run/secrets/minio_access_key"),
                Map.entry("MINIO_SECRET_KEY_FILE", "/run/secrets/minio_secret_key"),
                Map.entry("MINIO_BUCKET", "message-center"),
                Map.entry("BOOTSTRAP_ADMIN_USERNAME", "local-owner"),
                Map.entry("BOOTSTRAP_ADMIN_PASSWORD_FILE", "/run/secrets/bootstrap_admin_password"),
                Map.entry("WORKER_BATCH_SIZE", "25"),
                Map.entry("WORKER_MAX_ATTEMPTS", "6")
        ));

        assertEquals("jdbc:postgresql://localhost:5432/message_center", config.databaseUrl());
        assertEquals("message_center", config.databaseUser());
        assertEquals(Path.of("/run/secrets/postgres_password"), config.databasePasswordFile());
        assertEquals(Path.of("/run/secrets/credential_master_key"), config.credentialMasterKeyFile());
        assertEquals("http://localhost:9000", config.minioEndpoint());
        assertEquals(Path.of("/run/secrets/minio_access_key"), config.minioAccessKeyFile());
        assertEquals(Path.of("/run/secrets/minio_secret_key"), config.minioSecretKeyFile());
        assertEquals("message-center", config.minioBucket());
        assertEquals("local-owner", config.bootstrapAdminUsername());
        assertEquals(Path.of("/run/secrets/bootstrap_admin_password"), config.bootstrapAdminPasswordFile());
        assertEquals(25, config.workerBatchSize());
        assertEquals(6, config.workerMaxAttempts());
    }

    @Test
    void rejectsWorkerSettingsOutsideTheirBounds() {
        Config config = new Config(Map.of(
                "WORKER_BATCH_SIZE", "101",
                "WORKER_MAX_ATTEMPTS", "0"
        ));

        assertThrows(IllegalArgumentException.class, config::workerBatchSize);
        assertThrows(IllegalArgumentException.class, config::workerMaxAttempts);
    }

    @Test
    void exposesWeComViewerSettingsWithBoundsAndOrigins() {
        Config config = new Config(Map.ofEntries(
                Map.entry("WECOM_CORP_ID", "ww-test-corp"),
                Map.entry("WECOM_AGENT_ID", "1000247"),
                Map.entry("WECOM_SECRET", "corp-secret"),
                Map.entry("WECOM_ALLOWED_JSAPI_ORIGINS", "http://localhost:8099,https://crm.example.com"),
                Map.entry("WECOM_VIEWER_SESSION_TTL_SECONDS", "300"),
                Map.entry("WECOM_VIEWER_MAX_MESSAGES", "20"),
                Map.entry("WECOM_VIEWER_SESSION_RATE_LIMIT", "12"),
                Map.entry("WECOM_VIEWER_AUDIT_FILE", "/tmp/wecom-viewer-audit.jsonl"),
                Map.entry("WECOM_VIEWER_AUDIT_MAX_BYTES", "1048576"),
                Map.entry("WECOM_TOKEN_REFRESH_SKEW_SECONDS", "300")
        ));

        assertEquals("ww-test-corp", config.wecomCorpId());
        assertEquals("1000247", config.wecomAgentId());
        assertEquals("corp-secret", config.wecomSecret());
        assertEquals(List.of("http://localhost:8099", "https://crm.example.com"),
                config.wecomAllowedJsapiOrigins());
        assertEquals(300, config.wecomViewerSessionTtlSeconds());
        assertEquals(20, config.wecomViewerMaxMessages());
        assertEquals(12, config.wecomViewerSessionRateLimit());
        assertEquals(Path.of("/tmp/wecom-viewer-audit.jsonl"), config.wecomViewerAuditFile());
        assertEquals(1048576L, config.wecomViewerAuditMaxBytes());
        assertEquals(300, config.wecomTokenRefreshSkewSeconds());
    }

    @Test
    void exposesBoundedWeComBrowserLoginSettings() {
        Config config = new Config(Map.of(
                "WECOM_ALLOWED_JSAPI_ORIGINS", "https://crm.example.com",
                "WECOM_LOGIN_REDIRECT_URI", "https://crm.example.com/",
                "WECOM_LOGIN_ATTEMPT_TTL_SECONDS", "300",
                "WECOM_LOGIN_MAX_PENDING", "256"
        ));

        assertEquals("https://crm.example.com/", config.wecomLoginRedirectUri());
        assertEquals(300, config.wecomLoginAttemptTtlSeconds());
        assertEquals(256, config.wecomLoginMaxPending());
    }

    @Test
    void exposesDelegatedAuthorizationSettings() {
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_SUITE_ID", "dk-suite",
                "WECOM_SUITE_SECRET", "suite-secret",
                "WECOM_TOKEN", "callback-token",
                "WECOM_ENCODING_AES_KEY", "encoding-key",
                "WECOM_CALLBACK_RECEIVE_ID", "ww-callback-owner",
                "WECOM_LOGIN_AUTH_CORP_ID", "ww-authorized",
                "WECOM_AUTHORIZATION_QUEUE_CAPACITY", "64"
        ));

        assertEquals("dk-suite", config.wecomSuiteId());
        assertEquals("suite-secret", config.wecomSuiteSecret());
        assertEquals("callback-token", config.wecomToken());
        assertEquals("encoding-key", config.wecomEncodingAesKey());
        assertEquals("ww-callback-owner", config.wecomCallbackReceiveId());
        assertEquals("ww-authorized", config.wecomLoginAuthCorpId());
        assertEquals(tempDir.resolve("wecom-authorization-installations.jsonl"),
                config.wecomAuthorizationInstallationsFile());
        assertEquals(tempDir.resolve("wecom-authorization-audit.jsonl"),
                config.wecomAuthorizationAuditFile());
        assertEquals(1_048_576L, config.wecomAuthorizationAuditMaxBytes());
        assertEquals(64, config.wecomAuthorizationQueueCapacity());

        assertEquals("dk-suite", new Config(Map.of("WECOM_SUITE_ID", "dk-suite"))
                .wecomCallbackReceiveId());
    }

    @Test
    void exposesIndependentWeComLoginSuiteSettings() {
        Config configured = new Config(Map.of(
                "WECOM_LOGIN_SUITE_ID", "ww-login-suite",
                "WECOM_LOGIN_SUITE_SECRET", "login-suite-secret"));

        assertEquals("ww-login-suite", configured.wecomLoginSuiteId());
        assertEquals("login-suite-secret", configured.wecomLoginSuiteSecret());
        assertTrue(configured.hasCompleteWeComLoginSuiteConfiguration());
        assertTrue(configured.hasAnyWeComLoginSuiteConfiguration());
        Config partial = new Config(Map.of("WECOM_LOGIN_SUITE_ID", "ww-login-suite"));
        assertFalse(partial.hasCompleteWeComLoginSuiteConfiguration());
        assertTrue(partial.hasAnyWeComLoginSuiteConfiguration());
    }

    @Test
    void exposesBoundedWeComChatDataSettings() {
        Config defaults = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_CHATDATA_PRIVATE_KEY_FILE", tempDir.resolve("chatdata-key.pem").toString()
        ));

        assertEquals("", defaults.wecomChatDataProgramId());
        assertEquals("", defaults.wecomChatDataAbilityId());
        assertEquals(tempDir.resolve("chatdata-key.pem"), defaults.wecomChatDataPrivateKeyFile());
        assertEquals(1, defaults.wecomChatDataPublicKeyVersion());
        assertEquals(false, defaults.wecomChatDataPublicKeyAutoRegister());
        assertEquals(tempDir.resolve("wecom-chatdata-public-key-registration.json"),
                defaults.wecomChatDataPublicKeyRegistrationFile());
        assertEquals(tempDir.resolve("wecom-chatdata-cursor.json"), defaults.wecomChatDataCursorFile());
        assertEquals(200, defaults.wecomChatDataSyncLimit());
        assertEquals(5, defaults.wecomChatDataSyncMaxPages());
        assertEquals(15, defaults.wecomChatDataSyncTimeoutSeconds());
        assertEquals(5000, defaults.wecomChatDataStoreMaxMessages());
        assertEquals(8_388_608L, defaults.wecomChatDataStoreMaxBytes());
    }

    @Test
    void exposesBoundedWeComDailySummarySettings() {
        Config defaults = new Config(Map.of());

        assertEquals(false, defaults.wecomDailySummaryEnabled());
        assertEquals("conversation_daily_summary", defaults.wecomDailySummaryAbilityId());
        assertEquals(0, defaults.wecomDailySummaryHour());
        assertEquals(5, defaults.wecomDailySummaryMinute());
        assertEquals(32, defaults.wecomDailySummaryMaxBatches());
        assertEquals(20, defaults.wecomDailySummaryMaxTransientAttempts());
        assertEquals(900, defaults.wecomDailySummaryMaxBackoffSeconds());
        assertEquals(24, defaults.wecomDailySummaryMaxWaitHours());

        Config configured = new Config(Map.ofEntries(
                Map.entry("WECOM_DAILY_SUMMARY_ENABLED", "true"),
                Map.entry("WECOM_DAILY_SUMMARY_ABILITY_ID", "conversation_daily_summary"),
                Map.entry("WECOM_DAILY_SUMMARY_HOUR", "1"),
                Map.entry("WECOM_DAILY_SUMMARY_MINUTE", "15"),
                Map.entry("WECOM_DAILY_SUMMARY_MAX_BATCHES", "8"),
                Map.entry("WECOM_DAILY_SUMMARY_MAX_TRANSIENT_ATTEMPTS", "10"),
                Map.entry("WECOM_DAILY_SUMMARY_MAX_BACKOFF_SECONDS", "600"),
                Map.entry("WECOM_DAILY_SUMMARY_MAX_WAIT_HOURS", "12")
        ));

        assertEquals(true, configured.wecomDailySummaryEnabled());
        assertEquals(1, configured.wecomDailySummaryHour());
        assertEquals(15, configured.wecomDailySummaryMinute());
        assertEquals(8, configured.wecomDailySummaryMaxBatches());
        assertEquals(10, configured.wecomDailySummaryMaxTransientAttempts());
        assertEquals(600, configured.wecomDailySummaryMaxBackoffSeconds());
        assertEquals(12, configured.wecomDailySummaryMaxWaitHours());
    }

    @Test
    void rejectsUnsafeWeComDailySummarySettings() {
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_DAILY_SUMMARY_ENABLED", "yes"))
                        .wecomDailySummaryEnabled());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_DAILY_SUMMARY_ABILITY_ID", "other"))
                        .wecomDailySummaryAbilityId());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_DAILY_SUMMARY_HOUR", "24"))
                        .wecomDailySummaryHour());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_DAILY_SUMMARY_MINUTE", "60"))
                        .wecomDailySummaryMinute());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_DAILY_SUMMARY_MAX_BATCHES", "33"))
                        .wecomDailySummaryMaxBatches());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_DAILY_SUMMARY_MAX_TRANSIENT_ATTEMPTS", "21"))
                        .wecomDailySummaryMaxTransientAttempts());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_DAILY_SUMMARY_MAX_BACKOFF_SECONDS", "901"))
                        .wecomDailySummaryMaxBackoffSeconds());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_DAILY_SUMMARY_MAX_WAIT_HOURS", "25"))
                        .wecomDailySummaryMaxWaitHours());
    }

    @Test
    void resolvesRelativeWeComChatDataFilesInsideDataDirectory() {
        Config config = new Config(Map.of(
                "DATA_DIR", tempDir.toString(),
                "WECOM_DATA_FILE", "viewer/messages.jsonl",
                "WECOM_CHATDATA_CURSOR_FILE", "viewer/cursor.json",
                "WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FILE", "viewer/public-key-registration.json",
                "WECOM_CHATDATA_PUBLIC_KEY_AUTO_REGISTER", "true"
        ));

        assertEquals(tempDir.resolve("viewer/messages.jsonl"), config.wecomDataFile());
        assertEquals(tempDir.resolve("viewer/cursor.json"), config.wecomChatDataCursorFile());
        assertEquals(tempDir.resolve("viewer/public-key-registration.json"),
                config.wecomChatDataPublicKeyRegistrationFile());
        assertEquals(true, config.wecomChatDataPublicKeyAutoRegister());
    }

    @Test
    void rejectsUnsafeWeComChatDataSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_CHATDATA_PRIVATE_KEY_FILE", "relative.pem"))
                        .wecomChatDataPrivateKeyFile());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_CHATDATA_PUBLIC_KEY_VERSION", "0"))
                        .wecomChatDataPublicKeyVersion());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_CHATDATA_PUBLIC_KEY_AUTO_REGISTER", "yes"))
                        .wecomChatDataPublicKeyAutoRegister());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_CHATDATA_SYNC_LIMIT", "201"))
                        .wecomChatDataSyncLimit());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_CHATDATA_SYNC_MAX_PAGES", "6"))
                        .wecomChatDataSyncMaxPages());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_CHATDATA_SYNC_TIMEOUT_SECONDS", "16"))
                        .wecomChatDataSyncTimeoutSeconds());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_CHATDATA_STORE_MAX_MESSAGES", "5001"))
                        .wecomChatDataStoreMaxMessages());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_CHATDATA_STORE_MAX_BYTES", "8388609"))
                        .wecomChatDataStoreMaxBytes());
    }

    @Test
    void rejectsDelegatedAuthorizationQueueOutsideBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_AUTHORIZATION_QUEUE_CAPACITY", "0"))
                        .wecomAuthorizationQueueCapacity());
        assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("WECOM_AUTHORIZATION_QUEUE_CAPACITY", "257"))
                        .wecomAuthorizationQueueCapacity());
    }

    @Test
    void rejectsUnsafeOrCrossOriginWeComBrowserLoginSettings() {
        Config insecure = new Config(Map.of(
                "WECOM_ALLOWED_JSAPI_ORIGINS", "https://crm.example.com",
                "WECOM_LOGIN_REDIRECT_URI", "http://crm.example.com/"
        ));
        Config crossOrigin = new Config(Map.of(
                "WECOM_ALLOWED_JSAPI_ORIGINS", "https://crm.example.com",
                "WECOM_LOGIN_REDIRECT_URI", "https://other.example.com/"
        ));
        Config fragmented = new Config(Map.of(
                "WECOM_ALLOWED_JSAPI_ORIGINS", "https://crm.example.com",
                "WECOM_LOGIN_REDIRECT_URI", "https://crm.example.com/#code"
        ));
        Config ttlTooSmall = new Config(Map.of("WECOM_LOGIN_ATTEMPT_TTL_SECONDS", "29"));
        Config pendingTooLarge = new Config(Map.of("WECOM_LOGIN_MAX_PENDING", "1025"));

        assertThrows(IllegalArgumentException.class, insecure::wecomLoginRedirectUri);
        assertThrows(IllegalArgumentException.class, crossOrigin::wecomLoginRedirectUri);
        assertThrows(IllegalArgumentException.class, fragmented::wecomLoginRedirectUri);
        assertThrows(IllegalArgumentException.class, ttlTooSmall::wecomLoginAttemptTtlSeconds);
        assertThrows(IllegalArgumentException.class, pendingTooLarge::wecomLoginMaxPending);
    }

    @Test
    void defaultsWeComViewerToRecentTenMessages() {
        Config config = new Config(Map.of());

        assertEquals(10, config.wecomViewerMaxMessages());
        assertEquals(10, config.wecomViewerSessionRateLimit());
    }

    @Test
    void rejectsWeComViewerSettingsOutsideBounds() {
        Config tooSmallTtl = new Config(Map.of("WECOM_VIEWER_SESSION_TTL_SECONDS", "29"));
        Config tooLargeTtl = new Config(Map.of("WECOM_VIEWER_SESSION_TTL_SECONDS", "3601"));
        Config tooManyMessages = new Config(Map.of("WECOM_VIEWER_MAX_MESSAGES", "21"));
        Config tooManySessions = new Config(Map.of("WECOM_VIEWER_SESSION_RATE_LIMIT", "61"));
        Config tooSmallAudit = new Config(Map.of("WECOM_VIEWER_AUDIT_MAX_BYTES", "4095"));
        Config tooSmallSkew = new Config(Map.of("WECOM_TOKEN_REFRESH_SKEW_SECONDS", "4"));

        assertThrows(IllegalArgumentException.class, tooSmallTtl::wecomViewerSessionTtlSeconds);
        assertThrows(IllegalArgumentException.class, tooLargeTtl::wecomViewerSessionTtlSeconds);
        assertThrows(IllegalArgumentException.class, tooManyMessages::wecomViewerMaxMessages);
        assertThrows(IllegalArgumentException.class, tooManySessions::wecomViewerSessionRateLimit);
        assertThrows(IllegalArgumentException.class, tooSmallAudit::wecomViewerAuditMaxBytes);
        assertThrows(IllegalArgumentException.class, tooSmallSkew::wecomTokenRefreshSkewSeconds);
    }

    @Test
    void readsSecretWithoutTrailingLineBreaks() throws Exception {
        Path secretFile = tempDir.resolve("service-secret");
        Files.writeString(secretFile, "value with spaces\r\n", StandardCharsets.UTF_8);

        assertEquals("value with spaces", new Config(Map.of()).readSecret(secretFile));
    }

    @Test
    void readsBootstrapPasswordAsBoundedClearableCharacters() throws Exception {
        Path secretFile = tempDir.resolve("bootstrap-password");
        Files.writeString(secretFile, "local-admin-password\r\n", StandardCharsets.UTF_8);

        char[] password = App.readSecretChars(secretFile);

        assertArrayEquals("local-admin-password".toCharArray(), password);
        Path oversized = tempDir.resolve("oversized-password");
        Files.writeString(oversized, "x".repeat(1025), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> App.readSecretChars(oversized));
    }
}
