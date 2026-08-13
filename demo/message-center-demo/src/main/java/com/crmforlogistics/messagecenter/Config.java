package com.crmforlogistics.messagecenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class Config {
    private final Map<String, String> values;

    public Config(Map<String, String> values) {
        this.values = new HashMap<>(values);
        rejectLegacyAuditKeys();
    }

    public static Config load(Path envPath) throws IOException {
        Map<String, String> loaded = new HashMap<>();
        if (Files.exists(envPath)) {
            for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                String[] parts = trimmed.split("=", 2);
                loaded.put(parts[0].trim(), stripQuotes(parts[1].trim()));
            }
        }
        System.getenv().forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                loaded.put(key, value);
            }
        });
        Config config = new Config(loaded);
        config.validateAuditConfiguration();
        return config;
    }

    static Config forTests(Path dataDir, Path emailDataDir, Path chatappDataFile, Path templateFile) {
        Map<String, String> values = new HashMap<>();
        values.put("DATA_DIR", dataDir.toString());
        values.put("EMAIL_DATA_DIR", emailDataDir.toString());
        values.put("CHATAPP_DATA_FILE", chatappDataFile.toString());
        values.put("CHATAPP_TEMPLATE_FILE", templateFile.toString());
        values.put("CONTACT_GROUP_FILE", dataDir.resolve("contact-groups.jsonl").toString());
        values.put("EMAIL_CONTACT_GROUP_FILE", emailDataDir.resolve("contact-groups.jsonl").toString());
        return new Config(values);
    }

    public String value(String key, String defaultValue) {
        String value = values.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public Path dataDir() { return Path.of(value("DATA_DIR", "data")); }
    public Path contactGroupFile() { return Path.of(value("CONTACT_GROUP_FILE", dataDir().resolve("contact-groups.jsonl").toString())); }
    public Path emailDataDir() { return Path.of(value("EMAIL_DATA_DIR", "../email-send-receive-demo/data")); }
    public int emailAttachmentMaxCount() { return boundedInt("EMAIL_ATTACHMENT_MAX_COUNT", 16, 1, 16); }
    public long emailAttachmentMaxTotalBytes() { return boundedLong("EMAIL_ATTACHMENT_MAX_TOTAL_BYTES", 20_971_520L, 1_048_576L, 20_971_520L); }
    public long emailAttachmentStorageMaxBytes() { return boundedLong("EMAIL_ATTACHMENT_STORAGE_MAX_BYTES", 10_737_418_240L, 20_971_520L, 1_099_511_627_776L); }
    public Path emailInboxFile() { return emailDataDir().resolve("inbox.jsonl"); }
    public Path emailContactGroupFile() { return Path.of(value("EMAIL_CONTACT_GROUP_FILE", emailDataDir().resolve("contact-groups.jsonl").toString())); }
    public Path chatappDataFile() { return Path.of(value("CHATAPP_DATA_FILE", "../chatapp-send-receive-demo/data/messages.jsonl")); }
    public Path chatappTemplateFile() { return Path.of(value("CHATAPP_TEMPLATE_FILE", "../chatapp-send-receive-demo/data/templates.json")); }
    public boolean chatappMessageAutoSyncEnabled() {
        return strictBoolean("CHATAPP_MESSAGE_AUTO_SYNC_ENABLED", true);
    }
    public boolean hasChatAppMessageSyncConfiguration() {
        return !value("CUST_SPACE_ID", "").isBlank();
    }
    public Path callRecordDataDir() {
        return Path.of(value("CALL_RECORD_DATA_DIR", dataDir().resolve("call-records").toString()));
    }
    public long callRecordMaxAudioBytes() {
        return boundedLong("CALL_RECORD_MAX_AUDIO_BYTES", 104_857_600L, 1_048_576L, 104_857_600L);
    }
    public int callRecordMaxDurationSeconds() {
        return boundedInt("CALL_RECORD_MAX_DURATION_SECONDS", 7_200, 1, 7_200);
    }
    public long callRecordStorageMaxBytes() {
        return boundedLong("CALL_RECORD_STORAGE_MAX_BYTES", 10_737_418_240L,
                104_857_600L, 1_099_511_627_776L);
    }
    public int callRecordMaxRecords() {
        return boundedInt("CALL_RECORD_MAX_RECORDS", 10_000, 1, 100_000);
    }
    public int callRecordQueueCapacity() {
        return boundedInt("CALL_RECORD_QUEUE_CAPACITY", 64, 1, 1_024);
    }
    public int callRecordWorkerConcurrency() {
        return boundedInt("CALL_RECORD_WORKER_CONCURRENCY", 1, 1, 4);
    }
    public int callRecordLeaseSeconds() {
        return boundedInt("CALL_RECORD_LEASE_SECONDS", 2_100, 60, 7_200);
    }
    public int callRecordMaxAttempts() {
        return boundedInt("CALL_RECORD_MAX_ATTEMPTS", 3, 1, 10);
    }
    public long callRecordMaxResponseBytes() {
        return boundedLong("CALL_RECORD_MAX_RESPONSE_BYTES", 10_485_760L, 1_024L, 10_485_760L);
    }
    public int callRecordMaxSegments() {
        return boundedInt("CALL_RECORD_MAX_SEGMENTS", 20_000, 1, 20_000);
    }
    public int callRecordMaxRevisions() {
        return boundedInt("CALL_RECORD_MAX_REVISIONS", 20, 1, 20);
    }
    public int callAudioSessionTtlSeconds() {
        return boundedInt("CALL_AUDIO_SESSION_TTL_SECONDS", 300, 60, 600);
    }
    public int callAudioSessionMaxPerActor() {
        return boundedInt("CALL_AUDIO_SESSION_MAX_PER_ACTOR", 8, 1, 32);
    }
    public int callAudioSessionMaxActive() {
        return boundedInt("CALL_AUDIO_SESSION_MAX_ACTIVE", 256, 8, 1_024);
    }
    public boolean callAudioCookieSecure() {
        return !localDevMode()
                && "https".equalsIgnoreCase(java.net.URI.create(wecomLoginRedirectUri()).getScheme());
    }
    public java.net.URI funAsrBaseUri() {
        final java.net.URI uri;
        try {
            uri = java.net.URI.create(value("FUNASR_BASE_URL", "http://funasr:8000"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "FUNASR_BASE_URL must be an absolute private HTTP(S) service URL", exception);
        }
        String scheme = uri.getScheme() == null ? ""
                : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost() == null ? ""
                : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        int port = uri.getPort();
        if (!(scheme.equals("http") || scheme.equals("https")) || host.isBlank()
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(path.isBlank() || path.equals("/")) || (port != -1 && (port < 1 || port > 65_535))
                || !isPrivateServiceHost(host)) {
            throw new IllegalArgumentException(
                    "FUNASR_BASE_URL must be an absolute private HTTP(S) service URL");
        }
        return uri;
    }
    public String funAsrModel() {
        String model = value("FUNASR_MODEL", "sensevoice").trim();
        if (!model.equals("sensevoice")) {
            throw new IllegalArgumentException("FUNASR_MODEL must be sensevoice");
        }
        return model;
    }
    public java.time.Duration funAsrConnectTimeout() {
        return java.time.Duration.ofSeconds(
                boundedInt("FUNASR_CONNECT_TIMEOUT_SECONDS", 3, 1, 30));
    }
    public java.time.Duration funAsrRequestTimeout() {
        return java.time.Duration.ofSeconds(
                boundedInt("FUNASR_REQUEST_TIMEOUT_SECONDS", 1_800, 30, 3_600));
    }
    public boolean chatappTemplateAutoSyncEnabled() {
        return strictBoolean("CHATAPP_TEMPLATE_AUTO_SYNC_ENABLED", true);
    }
    public int chatappTemplateSyncIntervalSeconds() {
        return boundedInt("CHATAPP_TEMPLATE_SYNC_INTERVAL_SECONDS", 300, 300, 600);
    }
    public int chatappTemplatePageSize() {
        return boundedInt("TEMPLATE_PAGE_SIZE", 50, 1, 50);
    }
    public int chatappTemplateMaxPages() {
        return boundedInt("TEMPLATE_MAX_PAGES", 40, 1, 40);
    }
    public boolean hasChatAppTemplateSyncConfiguration() {
        return !value("CUST_SPACE_ID", "").isBlank();
    }
    public Path wecomDataFile() {
        if (localDevMode()) {
            return dataFile("LOCAL_WECOM_TARGET_FILE", "local-wecom-messages.jsonl");
        }
        return dataFile("WECOM_DATA_FILE", "wecom-messages.jsonl");
    }
    public int webPort() { return Integer.parseInt(value("WEB_PORT", value("MESSAGE_CENTER_PORT", "8099"))); }
    public boolean localDevMode() {
        return strictBoolean("LOCAL_DEV_MODE", false);
    }
    public String localWeComDataSource() {
        String source = value("LOCAL_WECOM_DATA_SOURCE", "fixture").trim().toLowerCase(java.util.Locale.ROOT);
        if (!source.equals("fixture") && !source.equals("jsonl")) {
            throw new IllegalArgumentException("LOCAL_WECOM_DATA_SOURCE must be fixture or jsonl");
        }
        return source;
    }
    public Path localWeComDataFile() {
        return dataFile("LOCAL_WECOM_DATA_FILE", "local-wecom-source.jsonl");
    }
    public String webBindAddress() {
        String fallback = localDevMode() ? "127.0.0.1" : "0.0.0.0";
        String address = value("WEB_BIND_ADDRESS", fallback).trim();
        if (address.isBlank()) throw new IllegalArgumentException("WEB_BIND_ADDRESS is required");
        if (localDevMode() && !isLoopbackAddress(address)) {
            throw new IllegalArgumentException("LOCAL_DEV_MODE requires WEB_BIND_ADDRESS to be loopback");
        }
        return address;
    }
    public long mediaMaxBytes() { return Long.parseLong(value("MEDIA_MAX_BYTES", "20971520")); }
    public Path mediaCacheDir() { return Path.of(value("MEDIA_CACHE_DIR", dataDir().resolve("media-cache").toString())); }
    public String databaseUrl() { return value("DATABASE_URL", "jdbc:postgresql://localhost:5432/message_center"); }
    public String databaseUser() { return value("DATABASE_USER", "message_center"); }
    public Path databasePasswordFile() { return Path.of(value("DATABASE_PASSWORD_FILE", "/run/secrets/postgres_password")); }
    public Path credentialMasterKeyFile() { return Path.of(value("CREDENTIAL_MASTER_KEY_FILE", "/run/secrets/credential_master_key")); }
    public String minioEndpoint() { return value("MINIO_ENDPOINT", "http://localhost:9000"); }
    public Path minioAccessKeyFile() { return Path.of(value("MINIO_ACCESS_KEY_FILE", "/run/secrets/minio_access_key")); }
    public Path minioSecretKeyFile() { return Path.of(value("MINIO_SECRET_KEY_FILE", "/run/secrets/minio_secret_key")); }
    public String minioBucket() { return value("MINIO_BUCKET", "message-center"); }
    public String bootstrapAdminUsername() { return value("BOOTSTRAP_ADMIN_USERNAME", ""); }
    public Path bootstrapAdminPasswordFile() {
        return Path.of(value("BOOTSTRAP_ADMIN_PASSWORD_FILE", "/run/secrets/bootstrap_admin_password"));
    }
    public int workerBatchSize() { return boundedInt("WORKER_BATCH_SIZE", 25, 1, 100); }
    public int workerMaxAttempts() { return boundedInt("WORKER_MAX_ATTEMPTS", 6, 1, 20); }
    public String wecomCorpId() { return value("WECOM_CORP_ID", ""); }
    public String wecomAgentId() { return value("WECOM_AGENT_ID", ""); }
    public String wecomSecret() { return value("WECOM_SECRET", ""); }
    public String wecomSuiteId() { return value("WECOM_SUITE_ID", ""); }
    public String wecomSuiteSecret() { return value("WECOM_SUITE_SECRET", ""); }
    public String wecomToken() { return value("WECOM_TOKEN", ""); }
    public String wecomEncodingAesKey() { return value("WECOM_ENCODING_AES_KEY", ""); }
    public String wecomCallbackReceiveId() { return value("WECOM_CALLBACK_RECEIVE_ID", wecomSuiteId()); }
    public Path wecomAuthorizationInstallationsFile() {
        return Path.of(value("WECOM_AUTHORIZATION_INSTALLATIONS_FILE",
                dataDir().resolve("wecom-authorization-installations.jsonl").toString()));
    }
    public Path wecomAuthorizationAuditFile() {
        return Path.of(value("WECOM_AUTHORIZATION_AUDIT_FILE",
                dataDir().resolve("wecom-authorization-audit.jsonl").toString()));
    }
    public AuditFileSettings authorizationAuditSettings() {
        return auditSettings(wecomAuthorizationAuditFile());
    }
    public String wecomLoginAuthCorpId() { return value("WECOM_LOGIN_AUTH_CORP_ID", ""); }
    public String wecomLoginSuiteId() { return value("WECOM_LOGIN_SUITE_ID", ""); }
    public String wecomLoginSuiteSecret() { return value("WECOM_LOGIN_SUITE_SECRET", ""); }
    public boolean hasCompleteWeComLoginSuiteConfiguration() {
        return !wecomLoginSuiteId().isBlank() && !wecomLoginSuiteSecret().isBlank();
    }
    public boolean hasAnyWeComLoginSuiteConfiguration() {
        return !wecomLoginSuiteId().isBlank() || !wecomLoginSuiteSecret().isBlank();
    }
    public int wecomAuthorizationQueueCapacity() {
        return boundedInt("WECOM_AUTHORIZATION_QUEUE_CAPACITY", 64, 1, 256);
    }
    public List<String> wecomAllowedJsapiOrigins() {
        String raw = value("WECOM_ALLOWED_JSAPI_ORIGINS", "http://localhost:" + webPort());
        return splitCsv(raw);
    }
    public int wecomViewerAuthTtlSeconds() {
        return boundedInt("WECOM_VIEWER_AUTH_TTL_SECONDS", 28_800, 300, 86_400);
    }
    public int wecomViewerSessionTtlSeconds() {
        return boundedInt("WECOM_VIEWER_SESSION_TTL_SECONDS", 300, 30, 3600);
    }
    public int wecomViewerMaxMessages() {
        return boundedInt("WECOM_VIEWER_MAX_MESSAGES", 15, 1, 15);
    }
    public int wecomViewerSessionRateLimit() {
        return boundedInt("WECOM_VIEWER_SESSION_RATE_LIMIT", 10, 1, 60);
    }
    public Path wecomViewerAuditFile() {
        return Path.of(value("WECOM_VIEWER_AUDIT_FILE", dataDir().resolve("wecom-viewer-audit.jsonl").toString()));
    }
    public AuditFileSettings viewerAuditSettings() {
        return auditSettings(wecomViewerAuditFile());
    }
    public void validateAuditConfiguration() {
        AuditFileSettings viewer = viewerAuditSettings();
        AuditFileSettings authorization = authorizationAuditSettings();
        if (viewer.file().equals(authorization.file())) {
            throw new IllegalArgumentException("viewer and authorization audit files must differ");
        }
    }
    public int wecomTokenRefreshSkewSeconds() {
        return boundedInt("WECOM_TOKEN_REFRESH_SKEW_SECONDS", 300, 5, 1800);
    }
    public String wecomLoginRedirectUri() {
        String raw = value("WECOM_LOGIN_REDIRECT_URI", "http://localhost:" + webPort() + "/");
        if (raw.length() > 2048) {
            throw new IllegalArgumentException("WECOM_LOGIN_REDIRECT_URI must not exceed 2048 characters");
        }
        final java.net.URI uri;
        try {
            uri = java.net.URI.create(raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("WECOM_LOGIN_REDIRECT_URI must be an absolute URL", exception);
        }
        String scheme = uri.getScheme() == null ? ""
                : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost() == null ? ""
                : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean localHttp = "http".equals(scheme) && "localhost".equals(host);
        if ((!"https".equals(scheme) && !localHttp) || host.isBlank()
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "WECOM_LOGIN_REDIRECT_URI must use HTTPS or local http://localhost without user info or fragment");
        }
        String origin = scheme + "://" + host + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
        if (!wecomAllowedJsapiOrigins().contains(origin)) {
            throw new IllegalArgumentException(
                    "WECOM_LOGIN_REDIRECT_URI origin must be listed in WECOM_ALLOWED_JSAPI_ORIGINS");
        }
        return uri.toString();
    }
    public int wecomLoginAttemptTtlSeconds() {
        return boundedInt("WECOM_LOGIN_ATTEMPT_TTL_SECONDS", 300, 30, 600);
    }
    public int wecomLoginMaxPending() {
        return boundedInt("WECOM_LOGIN_MAX_PENDING", 256, 1, 1024);
    }
    public String wecomChatDataProgramId() {
        return boundedOptionalText("WECOM_CHATDATA_PROGRAM_ID", 128);
    }
    public String wecomChatDataAbilityId() {
        return boundedOptionalText("WECOM_CHATDATA_ABILITY_ID", 128);
    }
    public boolean wecomChatDataDiagnostics() {
        return strictBoolean("WECOM_CHATDATA_DIAGNOSTICS", false);
    }
    public Path wecomChatDataPrivateKeyFile() {
        String raw = value("WECOM_CHATDATA_PRIVATE_KEY_FILE", "");
        if (raw.isBlank()) return Path.of("");
        Path path = Path.of(raw);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("WECOM_CHATDATA_PRIVATE_KEY_FILE must be an absolute path");
        }
        return path;
    }
    public int wecomChatDataPublicKeyVersion() {
        return boundedInt("WECOM_CHATDATA_PUBLIC_KEY_VERSION", 1, 1, Integer.MAX_VALUE);
    }
    public boolean wecomChatDataPublicKeyAutoRegister() {
        return strictBoolean("WECOM_CHATDATA_PUBLIC_KEY_AUTO_REGISTER", false);
    }
    public Path wecomChatDataPublicKeyRegistrationFile() {
        return dataFile("WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FILE",
                "wecom-chatdata-public-key-registration.json");
    }
    public Path wecomChatDataCursorFile() {
        return dataFile("WECOM_CHATDATA_CURSOR_FILE", "wecom-chatdata-cursor.json");
    }
    public int wecomChatDataSyncLimit() {
        return boundedInt("WECOM_CHATDATA_SYNC_LIMIT", 200, 1, 200);
    }
    public int wecomChatDataSyncMaxPages() {
        return boundedInt("WECOM_CHATDATA_SYNC_MAX_PAGES", 5, 1, 5);
    }
    public int wecomChatDataSyncTimeoutSeconds() {
        return boundedInt("WECOM_CHATDATA_SYNC_TIMEOUT_SECONDS", 15, 1, 15);
    }
    public boolean wecomChatDataAutoSyncEnabled() {
        return strictBoolean("WECOM_CHATDATA_AUTO_SYNC_ENABLED", true);
    }
    public int wecomChatDataAutoSyncIntervalSeconds() {
        return boundedInt("WECOM_CHATDATA_AUTO_SYNC_INTERVAL_SECONDS", 60, 15, 3600);
    }
    public int wecomChatDataStoreMaxMessages() {
        return boundedInt("WECOM_CHATDATA_STORE_MAX_MESSAGES", 5000, 1, 5000);
    }
    public long wecomChatDataStoreMaxBytes() {
        return boundedLong("WECOM_CHATDATA_STORE_MAX_BYTES", 8_388_608L, 4_096L, 8_388_608L);
    }
    public boolean wecomDailySummaryEnabled() {
        return strictBoolean("WECOM_DAILY_SUMMARY_ENABLED", false);
    }
    public String wecomDailySummaryAbilityId() {
        String abilityId = value("WECOM_DAILY_SUMMARY_ABILITY_ID", "conversation_daily_summary").trim();
        if (!"conversation_daily_summary".equals(abilityId)) {
            throw new IllegalArgumentException(
                    "WECOM_DAILY_SUMMARY_ABILITY_ID must be conversation_daily_summary");
        }
        return abilityId;
    }
    public int wecomDailySummaryHour() {
        return boundedInt("WECOM_DAILY_SUMMARY_HOUR", 0, 0, 23);
    }
    public int wecomDailySummaryMinute() {
        return boundedInt("WECOM_DAILY_SUMMARY_MINUTE", 5, 0, 59);
    }
    public int wecomDailySummaryMaxBatches() {
        return boundedInt("WECOM_DAILY_SUMMARY_MAX_BATCHES", 32, 1, 32);
    }
    public int wecomDailySummaryMaxTransientAttempts() {
        return boundedInt("WECOM_DAILY_SUMMARY_MAX_TRANSIENT_ATTEMPTS", 20, 1, 20);
    }
    public int wecomDailySummaryMaxBackoffSeconds() {
        return boundedInt("WECOM_DAILY_SUMMARY_MAX_BACKOFF_SECONDS", 900, 1, 900);
    }
    public int wecomDailySummaryPollInitialSeconds() {
        return boundedInt("WECOM_DAILY_SUMMARY_POLL_INITIAL_SECONDS", 30, 5, 900);
    }
    public int wecomDailySummaryMaxWaitHours() {
        return boundedInt("WECOM_DAILY_SUMMARY_MAX_WAIT_HOURS", 24, 1, 24);
    }

    public String readSecret(Path path) throws IOException {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            int end = content.length();
            while (end > 0 && (content.charAt(end - 1) == '\n' || content.charAt(end - 1) == '\r')) {
                end--;
            }
            return content.substring(0, end);
        } catch (IOException ex) {
            throw new IOException("Unable to read secret file: " + path, ex);
        }
    }

    private Path dataFile(String key, String defaultFileName) {
        Path path = Path.of(value(key, defaultFileName));
        return path.isAbsolute() ? path : dataDir().resolve(path).normalize();
    }

    private AuditFileSettings auditSettings(Path file) {
        return new AuditFileSettings(file,
                boundedInt("AUDIT_RETENTION_DAYS", 7, 1, 365),
                boundedLong("AUDIT_FILE_MAX_BYTES", 1_048_576L, 4_096L, 20_971_520L),
                boundedLong("AUDIT_STREAM_MAX_BYTES", 8_388_608L, 4_096L, 1_073_741_824L),
                boundedLong("AUDIT_MIN_FREE_DISK_BYTES", 67_108_864L, 4_096L, 1_099_511_627_776L),
                Duration.ofSeconds(boundedInt("AUDIT_WARNING_INTERVAL_SECONDS", 3_600, 60, 86_400)));
    }

    private void rejectLegacyAuditKeys() {
        if (values.containsKey("WECOM_VIEWER_AUDIT_MAX_BYTES")) {
            throw new IllegalArgumentException("WECOM_VIEWER_AUDIT_MAX_BYTES is no longer supported");
        }
        if (values.containsKey("WECOM_AUTHORIZATION_AUDIT_MAX_BYTES")) {
            throw new IllegalArgumentException("WECOM_AUTHORIZATION_AUDIT_MAX_BYTES is no longer supported");
        }
    }

    private int boundedInt(String key, int fallback, int minimum, int maximum) {
        String raw = value(key, Integer.toString(fallback));
        final int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be an integer between " + minimum + " and " + maximum, ex);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private long boundedLong(String key, long fallback, long minimum, long maximum) {
        String raw = value(key, Long.toString(fallback));
        final long parsed;
        try {
            parsed = Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be an integer between " + minimum + " and " + maximum, ex);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private String boundedOptionalText(String key, int maximum) {
        String text = value(key, "").trim();
        if (text.length() > maximum) {
            throw new IllegalArgumentException(key + " must not exceed " + maximum + " characters");
        }
        return text;
    }

    private boolean strictBoolean(String key, boolean fallback) {
        String raw = value(key, Boolean.toString(fallback));
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }

    private static boolean isPrivateServiceHost(String host) {
        if (host.equals("localhost")) return true;
        if (isIpv4Literal(host)) {
            String[] octets = host.split("\\.");
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            return first == 10 || first == 127 || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        }
        if (!isDnsName(host)) return false;
        return !host.contains(".") || host.endsWith(".internal") || host.endsWith(".local");
    }

    private static boolean isLoopbackAddress(String address) {
        return address.equalsIgnoreCase("localhost") || address.equals("127.0.0.1")
                || address.equals("::1") || address.equals("0:0:0:0:0:0:0:1");
    }

    private static boolean isIpv4Literal(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) return false;
            for (int index = 0; index < octet.length(); index++) {
                if (!Character.isDigit(octet.charAt(index))) return false;
            }
            if (Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }

    private static boolean isDnsName(String host) {
        if (host.length() > 253) return false;
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63
                    || !Character.isLetterOrDigit(label.charAt(0))
                    || !Character.isLetterOrDigit(label.charAt(label.length() - 1))) {
                return false;
            }
            for (int index = 1; index < label.length() - 1; index++) {
                char current = label.charAt(index);
                if (!Character.isLetterOrDigit(current) && current != '-') return false;
            }
        }
        return true;
    }

    private static String stripQuotes(String value) {
        if (value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value == null ? "" : value;
    }

    private static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }
}
