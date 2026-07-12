package com.crmforlogistics.chatappdemo;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.DefaultCredentialProvider;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationRequest;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationResponse;
import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationResponseBody;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailRequest;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailResponse;
import com.aliyun.sdk.service.cams20200606.models.GetChatappTemplateDetailResponseBody;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageRequest;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponse;
import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateRequest;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateResponse;
import com.aliyun.sdk.service.cams20200606.models.ListChatappTemplateResponseBody;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappPhoneNumbersRequest;
import com.aliyun.sdk.service.cams20200606.models.SendChatappMessageRequest;
import com.aliyun.sdk.service.cams20200606.models.SendChatappMessageResponse;
import com.aliyun.sdk.service.cams20200606.models.UpdateAccountWebhookRequest;
import com.aliyun.sdk.service.cams20200606.models.UpdatePhoneWebhookRequest;
import darabonba.core.client.ClientOverrideConfiguration;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class App {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson PRETTY_GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Type STRING_MAP = new TypeToken<Map<String, String>>() {}.getType();
    private static final Type TEMPLATE_RECORD_LIST = new TypeToken<List<TemplateRecord>>() {}.getType();
    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile(
            "\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*\\}\\}|\\$\\(\\s*([A-Za-z0-9_.-]+)\\s*\\)"
    );
    private static final long DEFAULT_MEDIA_MAX_BYTES = 20L * 1024L * 1024L;
    private static final long IDEMPOTENCY_RETENTION_MILLIS = Duration.ofHours(24).toMillis();
    private static final int MAX_IDEMPOTENCY_ENTRIES = 10000;

    public static void main(String[] args) throws Exception {
        Config config = Config.load(Path.of(".env"));
        String command = args.length == 0 ? "web" : args[0].trim();
        TemplateStore templateStore = new TemplateStore(config.templateFile());
        MessageStore store = new MessageStore(config.dataFile(), templateStore);

        switch (command) {
            case "send":
                StoredMessage sent = sendFromConfig(config, store, templateStore);
                System.out.println(GSON.toJson(sent));
                break;
            case "sync":
                SyncResult syncResult = syncMessages(config, store, templateStore);
                System.out.println(GSON.toJson(syncResult));
                break;
            case "sync-templates":
                TemplateSyncResult templateSyncResult = syncTemplates(config, templateStore);
                System.out.println(GSON.toJson(templateSyncResult));
                break;
            case "templates":
                System.out.println(PRETTY_GSON.toJson(templateStore.readAll()));
                break;
            case "phones":
                try (AsyncClient client = createClient(config)) {
                    Object body = client.queryChatappPhoneNumbers(QueryChatappPhoneNumbersRequest.builder()
                            .custSpaceId(config.required("CUST_SPACE_ID"))
                            .build()).get().getBody();
                    System.out.println(GSON.toJson(body));
                }
                break;
            case "set-phone-webhook":
                setPhoneWebhook(config);
                break;
            case "set-account-webhook":
                setAccountWebhook(config);
                break;
            case "mock-inbound":
                StoredMessage inbound = store.append(StoredMessage.inbound(
                        "mock-" + UUID.randomUUID(),
                        config.value("CHATAPP_TO", "13800000000"),
                        config.value("CHATAPP_FROM", "demo-business"),
                        "这是一条本地模拟的 ChatApp 入站消息",
                        "{}"
                ));
                System.out.println(GSON.toJson(inbound));
                break;
            case "web":
                startWeb(config, store, templateStore);
                break;
            default:
                throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    private static SyncResult syncMessages(Config config, MessageStore store, TemplateStore templateStore) throws Exception {
        int pageSize = Integer.parseInt(config.value("SYNC_PAGE_SIZE", "50"));
        int maxPages = Integer.parseInt(config.value("SYNC_MAX_PAGES", "10"));
        long startTime = syncStartTime(config);
        long endTime = syncEndTime(config);
        SyncResult result = new SyncResult();
        if (Boolean.parseBoolean(config.value("SYNC_TEMPLATES_BEFORE_MESSAGES", "true"))) {
            TemplateSyncResult templates = syncTemplates(config, templateStore);
            result.templatesFetched = templates.fetched;
            result.templatesSaved = templates.saved;
            result.templatesSkipped = templates.skipped;
        }

        try (AsyncClient client = createClient(config)) {
            for (int pageIndex = 1; pageIndex <= maxPages; pageIndex++) {
                ListChatappMessageRequest.Builder builder = ListChatappMessageRequest.builder()
                        .custSpaceId(config.required("CUST_SPACE_ID"))
                        .startTime(startTime)
                        .endTime(endTime)
                        .page(ListChatappMessageRequest.Page.builder()
                                .index((long) pageIndex)
                                .size((long) pageSize)
                                .build());

                putIfPresent(config, "CHATAPP_CHANNEL_TYPE", builder::channelType);
                putIfPresent(config, "CHATAPP_FROM", builder::businessNumber);
                putIfPresent(config, "SYNC_USER_NUMBER", builder::userNumber);
                putIfPresent(config, "SYNC_MESSAGE_STATUS", builder::messageStatus);
                putIfPresent(config, "SYNC_CLIENT_ACCEPT_STATUS", builder::clientAcceptStatus);

                ListChatappMessageResponse response = client.listChatappMessage(builder.build()).get();
                ListChatappMessageResponseBody body = response.getBody();
                if (body == null) {
                    break;
                }
                if (body.getCode() != null && !"OK".equalsIgnoreCase(body.getCode())) {
                    throw new IllegalStateException("ListChatappMessage failed: " + body.getCode() + " " + body.getMessage());
                }
                List<ListChatappMessageResponseBody.Data> rows = body.getData();
                if (rows == null || rows.isEmpty()) {
                    break;
                }
                result.fetched += rows.size();
                for (ListChatappMessageResponseBody.Data row : rows) {
                    StoredMessage message = fromHistoryRow(row, config, templateStore);
                    if (store.contains(message)) {
                        result.skipped++;
                        continue;
                    }
                    store.append(message);
                    result.saved++;
                }
                if (rows.size() < pageSize) {
                    break;
                }
            }
        }

        return result;
    }

    private static TemplateSyncResult syncTemplates(Config config, TemplateStore templateStore) throws Exception {
        int pageSize = Integer.parseInt(config.value("TEMPLATE_PAGE_SIZE", "50"));
        int maxPages = Integer.parseInt(config.value("TEMPLATE_MAX_PAGES", "10"));
        TemplateSyncResult result = new TemplateSyncResult();
        Map<String, TemplateRecord> merged = new LinkedHashMap<>();
        for (TemplateRecord existing : templateStore.readAll()) {
            merged.put(templateStore.key(existing), existing);
        }

        try (AsyncClient client = createClient(config)) {
            for (int pageIndex = 1; pageIndex <= maxPages; pageIndex++) {
                ListChatappTemplateRequest.Builder builder = ListChatappTemplateRequest.builder()
                        .custSpaceId(config.required("CUST_SPACE_ID"))
                        .page(ListChatappTemplateRequest.Page.builder()
                                .index(pageIndex)
                                .size(pageSize)
                                .build());

                putIfPresent(config, "TEMPLATE_LANGUAGE", builder::language);
                putIfPresent(config, "TEMPLATE_NAME", builder::name);
                putIfPresent(config, "TEMPLATE_CODE", builder::code);
                putIfPresent(config, "TEMPLATE_AUDIT_STATUS", builder::auditStatus);
                putIfPresent(config, "TEMPLATE_CATEGORY", builder::category);
                putIfPresent(config, "TEMPLATE_TYPE", builder::templateType);

                ListChatappTemplateResponse response = client.listChatappTemplate(builder.build()).get();
                ListChatappTemplateResponseBody body = response.getBody();
                if (body == null) {
                    break;
                }
                assertOk("ListChatappTemplate", body.getCode(), body.getMessage());
                if (Boolean.FALSE.equals(body.getSuccess())) {
                    throw new IllegalStateException("ListChatappTemplate failed: " + body.getMessage());
                }
                List<ListChatappTemplateResponseBody.ListTemplate> rows = body.getListTemplate();
                if (rows == null || rows.isEmpty()) {
                    break;
                }
                result.fetched += rows.size();
                for (ListChatappTemplateResponseBody.ListTemplate row : rows) {
                    TemplateRecord record = fetchTemplateDetail(client, config, row);
                    String key = templateStore.key(record);
                    TemplateRecord previous = merged.put(key, record);
                    if (previous == null || !sameTemplate(previous, record)) {
                        result.saved++;
                    } else {
                        result.skipped++;
                    }
                }
                if (rows.size() < pageSize) {
                    break;
                }
            }
        }

        templateStore.saveAll(new ArrayList<>(merged.values()));
        return result;
    }

    private static TemplateRecord fetchTemplateDetail(
            AsyncClient client,
            Config config,
            ListChatappTemplateResponseBody.ListTemplate row
    ) throws Exception {
        String language = firstNonBlank(row.getLanguage(), config.value("TEMPLATE_LANGUAGE", config.value("CHATAPP_LANGUAGE", "")));
        GetChatappTemplateDetailRequest.Builder builder = GetChatappTemplateDetailRequest.builder()
                .custSpaceId(config.required("CUST_SPACE_ID"));
        putIfNotBlank(row.getTemplateCode(), builder::templateCode);
        putIfNotBlank(row.getTemplateName(), builder::templateName);
        putIfNotBlank(language, builder::language);
        putIfNotBlank(row.getTemplateType(), builder::templateType);

        GetChatappTemplateDetailResponse response = client.getChatappTemplateDetail(builder.build()).get();
        GetChatappTemplateDetailResponseBody body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("GetChatappTemplateDetail returned empty body for " + row.getTemplateCode());
        }
        assertOk("GetChatappTemplateDetail", body.getCode(), body.getMessage());
        GetChatappTemplateDetailResponseBody.Data data = body.getData();
        if (data == null) {
            throw new IllegalStateException("GetChatappTemplateDetail returned empty data for " + row.getTemplateCode());
        }
        return templateRecord(row, data);
    }

    private static TemplateRecord templateRecord(
            ListChatappTemplateResponseBody.ListTemplate row,
            GetChatappTemplateDetailResponseBody.Data data
    ) {
        TemplateRecord record = new TemplateRecord();
        record.templateCode = firstNonBlank(data.getTemplateCode(), row.getTemplateCode());
        record.templateName = firstNonBlank(data.getName(), row.getTemplateName());
        record.languageCode = firstNonBlank(data.getLanguage(), row.getLanguage());
        record.body = templateBody(data.getComponents());
        record.placeholders = templatePlaceholders(record.body);
        record.raw = GSON.toJson(data);
        record.updatedAt = Instant.now().toString();
        return record;
    }

    private static String templateBody(List<GetChatappTemplateDetailResponseBody.Components> components) {
        if (components == null || components.isEmpty()) {
            return "";
        }
        List<String> fallbackText = new ArrayList<>();
        for (GetChatappTemplateDetailResponseBody.Components component : components) {
            String text = firstNonBlank(component.getText(), component.getCaption(), "");
            if (text.isBlank()) {
                continue;
            }
            String type = firstNonBlank(component.getType(), "");
            if ("BODY".equalsIgnoreCase(type)) {
                return text;
            }
            fallbackText.add(text);
        }
        return String.join("\n", fallbackText);
    }

    private static boolean sameTemplate(TemplateRecord left, TemplateRecord right) {
        return Objects.equals(left.templateCode, right.templateCode)
                && Objects.equals(left.templateName, right.templateName)
                && Objects.equals(left.languageCode, right.languageCode)
                && Objects.equals(left.body, right.body)
                && Objects.equals(left.placeholders, right.placeholders);
    }

    private static void assertOk(String apiName, String code, String message) {
        if (code != null && !code.isBlank() && !"OK".equalsIgnoreCase(code)) {
            throw new IllegalStateException(apiName + " failed: " + code + " " + message);
        }
    }

    private static long syncStartTime(Config config) {
        String configured = config.value("SYNC_START_TIME", "");
        if (!configured.isBlank()) {
            return Long.parseLong(configured);
        }
        String configuredText = config.value("SYNC_START_TIME_STR", "");
        if (!configuredText.isBlank()) {
            return parseTime(configuredText).toEpochMilli();
        }
        long lookbackDays = Long.parseLong(config.value("SYNC_LOOKBACK_DAYS", "7"));
        return Instant.now().minus(Duration.ofDays(lookbackDays)).toEpochMilli();
    }

    private static long syncEndTime(Config config) {
        String configured = config.value("SYNC_END_TIME", "");
        if (!configured.isBlank()) {
            return Long.parseLong(configured);
        }
        String configuredText = config.value("SYNC_END_TIME_STR", "");
        if (!configuredText.isBlank()) {
            return parseTime(configuredText).toEpochMilli();
        }
        return Instant.now().toEpochMilli();
    }

    private static Instant parseTime(String value) {
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (RuntimeException ignored) {
            // Try common local timestamp formats below.
        }
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        );
        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDateTime.parse(trimmed, formatter)
                        .atZone(ZoneId.systemDefault())
                        .toInstant();
            } catch (RuntimeException ignored) {
            }
        }
        return Instant.ofEpochMilli(Long.parseLong(trimmed));
    }

    private static StoredMessage fromHistoryRow(ListChatappMessageResponseBody.Data row, Config config, TemplateStore templateStore) {
        String raw = GSON.toJson(row);
        String id = firstNonBlank(row.getMessageId(), row.getUniqueMessageId(), "sync-" + UUID.randomUUID());
        String businessNumber = firstNonBlank(row.getBusinessNumber(), config.value("CHATAPP_FROM", ""));
        String userNumber = row.getUserNumber();
        String direction = inferDirection(row.getMessageSource(), row.getEventAction(), row.getType());
        String text = historyDisplayText(row, templateStore);
        StoredMessage message;
        if ("inbound".equals(direction)) {
            message = StoredMessage.inbound(id, userNumber, businessNumber, text, raw);
        } else {
            message = StoredMessage.outbound(id, businessNumber, userNumber, text, raw);
        }
        message.timestamp = normalizeTimestamp(row.getSendTime());
        message.status = firstNonBlank(row.getMessageStatusName(), row.getMessageStatus(), "");
        message.statusTimestamp = message.status.isBlank() ? "" : message.timestamp;
        return message;
    }

    private static String inferDirection(String messageSource, String eventAction, String type) {
        String marker = (firstNonBlank(messageSource, eventAction, type, "")).toLowerCase();
        if (marker.contains("in") || marker.contains("up") || marker.contains("receive")
                || marker.contains("user") || marker.contains("customer") || "mo".equals(marker)) {
            return "inbound";
        }
        return "outbound";
    }

    private static String normalizeTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now().toString();
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed).toString();
        } catch (RuntimeException ignored) {
            // Try common CAMS console timestamp formats below.
        }
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        );
        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDateTime.parse(trimmed, formatter)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toString();
            } catch (RuntimeException ignored) {
            }
        }
        return Instant.now().toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String historyDisplayText(ListChatappMessageResponseBody.Data row, TemplateStore templateStore) {
        String message = firstNonBlank(row.getMessage(), row.getFailReason(), "");
        String text = displayText(message);
        String type = firstNonBlank(row.getMessageTypeName(), row.getMessageType(), "");
        if (type.equalsIgnoreCase("template") || type.equalsIgnoreCase("TEMPLATE")) {
            return templateDisplayText(message, text, row.getTemplateName(), row.getTemplateCode(), row.getLanguageCode(), templateStore);
        }
        String mediaText = mediaDisplayTextFromRaw(GSON.toJson(row));
        if (!mediaText.isBlank()) {
            return mediaText;
        }
        return firstNonBlank(text, type, "");
    }

    private static String historyDisplayTextFromRaw(String raw, TemplateStore templateStore) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            JsonElement root = JsonParser.parseString(raw);
            String type = WebhookParser.first(root, "messageTypeName", "MessageTypeName", "messageType", "MessageType");
            String message = WebhookParser.first(root, "message", "Message");
            String text = displayText(message);
            if (type.equalsIgnoreCase("template") || type.equalsIgnoreCase("TEMPLATE")) {
                return templateDisplayText(message, text,
                        WebhookParser.first(root, "templateName", "TemplateName"),
                        WebhookParser.first(root, "templateCode", "TemplateCode"),
                        WebhookParser.first(root, "languageCode", "LanguageCode", "language", "Language"),
                        templateStore);
            }
            String mediaText = mediaDisplayTextFromRaw(root);
            if (!mediaText.isBlank()) {
                return mediaText;
            }
            return text;
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String mediaDisplayTextFromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            return mediaDisplayTextFromRaw(JsonParser.parseString(raw));
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String mediaDisplayTextFromRaw(JsonElement root) {
        MediaMetadata metadata = mediaMetadata(root);
        if (metadata.mediaType.isBlank()) {
            return "";
        }
        return mediaDisplayText(metadata.mediaType, metadata.caption, metadata.fileName);
    }

    private static String templateDisplayText(
            String message,
            String text,
            String templateName,
            String templateCode,
            String languageCode,
            TemplateStore templateStore
    ) {
        TemplateRecord template = templateStore == null ? null : templateStore.find(templateCode, languageCode);
        if (template != null && template.body != null && !template.body.isBlank()) {
            String rendered = renderTemplateBody(template.body, message);
            if (!rendered.isBlank()) {
                return rendered;
            }
        }
        String name = firstNonBlank(templateName, templateCode, "");
        if (!name.isBlank() && !text.isBlank()) {
            return "模板消息 " + name + ": " + text;
        }
        if (!text.isBlank()) {
            return "模板消息: " + text;
        }
        return firstNonBlank(name, "模板消息");
    }

    private static String renderTemplateBody(String body, String message) {
        JsonObject params = parseJsonObject(message);
        if (params == null) {
            return body;
        }
        Map<String, String> values = templateParameterMap(params);
        Matcher matcher = TEMPLATE_PLACEHOLDER.matcher(body);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String placeholder = placeholderName(matcher);
            String value = firstNonBlank(values.get(placeholder), values.get(placeholder.toLowerCase()), "");
            if (value.isBlank()) {
                value = matcher.group(0);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static Map<String, String> templateParameterMap(JsonObject params) {
        Map<String, String> values = new HashMap<>();
        List<Map.Entry<String, JsonElement>> ordered = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : params.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) {
                continue;
            }
            String key = entry.getKey();
            String value = entry.getValue().getAsString();
            if (value == null || value.isBlank()) {
                continue;
            }
            values.put(key, value);
            values.put(key.toLowerCase(), value);
            if (key.matches("(?i)text\\d+")) {
                String index = key.replaceAll("(?i)^text", "");
                values.put(index, value);
                ordered.add(entry);
            } else if (key.matches("\\d+")) {
                values.put(key, value);
                ordered.add(entry);
            }
        }

        ordered.sort(Comparator.comparingInt(entry -> textParamOrder(entry.getKey())));
        int index = 1;
        for (Map.Entry<String, JsonElement> entry : ordered) {
            values.putIfAbsent(String.valueOf(index), entry.getValue().getAsString());
            index++;
        }
        String text = jsonString(params, "text");
        if (!text.isBlank()) {
            values.put("text", text);
            values.put("TEXT", text);
            values.putIfAbsent(String.valueOf(index), text);
        }
        return values;
    }

    private static List<String> templatePlaceholders(String body) {
        LinkedHashSet<String> placeholders = new LinkedHashSet<>();
        if (body == null || body.isBlank()) {
            return new ArrayList<>();
        }
        Matcher matcher = TEMPLATE_PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            placeholders.add(placeholderName(matcher));
        }
        return new ArrayList<>(placeholders);
    }

    private static String placeholderName(Matcher matcher) {
        return firstNonBlank(matcher.group(1), matcher.group(2), "");
    }

    private static JsonObject parseJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(value.trim());
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String jsonString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return "";
        }
        return value.getAsString();
    }

    private static String displayText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        try {
            JsonElement parsed = JsonParser.parseString(trimmed);
            if (parsed.isJsonObject()) {
                String mediaText = mediaDisplayTextFromRaw(parsed);
                if (!mediaText.isBlank()) {
                    return mediaText;
                }
                String templateParams = templateParamText(parsed.getAsJsonObject());
                if (!templateParams.isBlank()) {
                    return templateParams;
                }
            }
            String text = WebhookParser.first(parsed, "text", "body", "content", "message");
            if (!text.isBlank()) {
                return text;
            }
        } catch (RuntimeException ignored) {
        }
        return trimmed;
    }

    private static String templateParamText(JsonObject object) {
        List<Map.Entry<String, JsonElement>> params = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            if (key.matches("(?i)text\\d*") && entry.getValue().isJsonPrimitive()) {
                params.add(entry);
            }
        }
        boolean hasNumberedParam = params.stream().anyMatch(entry -> entry.getKey().matches("(?i)text\\d+"));
        if (!hasNumberedParam) {
            return "";
        }
        params.sort(Comparator.comparingInt(entry -> textParamOrder(entry.getKey())));
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : params) {
            String value = entry.getValue().getAsString();
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return String.join(" / ", values);
    }

    private static int textParamOrder(String key) {
        String suffix = key.replaceAll("(?i)^text", "");
        if (suffix.isBlank()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(suffix);
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE - 1;
        }
    }

    private static String displayStatus(String value) {
        String text = displayText(value);
        if (text.startsWith("状态:")) {
            return text.substring("状态:".length()).trim();
        }
        return text;
    }

    private static String statusTargetId(String id) {
        if (id == null) {
            return "";
        }
        return id.endsWith("-status") ? id.substring(0, id.length() - "-status".length()) : id;
    }

    private static AsyncClient createClient(Config config) {
        ICredentialProvider credentialsProvider = createCredentialsProvider(config);
        return AsyncClient.builder()
                .region(config.value("CAMS_REGION", "ap-southeast-1"))
                .credentialsProvider(credentialsProvider)
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(config.value("CAMS_ENDPOINT", "cams.ap-southeast-1.aliyuncs.com")))
                .build();
    }

    private static ICredentialProvider createCredentialsProvider(Config config) {
        String accessKeyId = config.value("ALIYUN_ACCESS_KEY_ID", "");
        String accessKeySecret = config.value("ALIYUN_ACCESS_KEY_SECRET", "");
        if (!accessKeyId.isBlank() && !accessKeySecret.isBlank()) {
            return StaticCredentialProvider.create(Credential.builder()
                    .accessKeyId(accessKeyId)
                    .accessKeySecret(accessKeySecret)
                    .build());
        }
        return DefaultCredentialProvider.builder().build();
    }

    private static StoredMessage sendFromConfig(Config config, MessageStore store, TemplateStore templateStore) throws Exception {
        String to = config.required("CHATAPP_TO");
        String mode = config.value("CHATAPP_SEND_MODE", "text");
        if ("template".equalsIgnoreCase(mode)) {
            return sendTemplateMessage(
                    config,
                    store,
                    templateStore,
                    to,
                    config.required("CHATAPP_TEMPLATE_CODE"),
                    config.value("CHATAPP_TEMPLATE_NAME", ""),
                    config.value("CHATAPP_LANGUAGE", "en_US"),
                    parseStringMap(config.value("CHATAPP_TEMPLATE_PARAMS_JSON", "{}"))
            );
        }
        return sendMessage(config, store, to, config.value("CHATAPP_MESSAGE", "hello from ChatApp Java demo"));
    }

    private static StoredMessage sendMessage(Config config, MessageStore store, String to, String text) throws Exception {
        return sendMessage(config, store, to, text, "", "");
    }

    private static StoredMessage sendMessage(
            Config config,
            MessageStore store,
            String to,
            String text,
            String clientRequestId,
            String requestFingerprint
    ) throws Exception {
        SendChatappMessageRequest.Builder builder = SendChatappMessageRequest.builder()
                .custSpaceId(config.required("CUST_SPACE_ID"))
                .from(config.required("CHATAPP_FROM"))
                .to(requiredInline(to, "to"))
                .messageType(config.value("CHATAPP_MESSAGE_TYPE", "text"));

        putIfPresent(config, "CHATAPP_CHANNEL_TYPE", builder::channelType);
        putIfPresent(config, "CHATAPP_TYPE", builder::type);
        putIfNotBlank(clientRequestId, builder::taskId);
        builder.content(config.value("CHATAPP_CONTENT_JSON", textContentJson(text)));

        try (AsyncClient client = createClient(config)) {
            SendChatappMessageResponse response = client.sendChatappMessage(builder.build()).get();
            String messageId = response.getBody() == null ? "" : response.getBody().getMessageId();
            String raw = GSON.toJson(response.getBody());
            StoredMessage message = StoredMessage.outbound(
                    messageId == null || messageId.isBlank() ? "local-" + UUID.randomUUID() : messageId,
                    config.required("CHATAPP_FROM"),
                    to,
                    text,
                    raw
            );
            message.clientRequestId = clientRequestId;
            message.requestFingerprint = requestFingerprint;
            return store.append(message);
        }
    }

    private static StoredMessage sendMediaMessage(
            Config config,
            MessageStore store,
            String to,
            String requestedMediaType,
            UploadedFile file,
            String caption
    ) throws Exception {
        return sendMediaMessage(config, store, to, requestedMediaType, file, caption, "", "");
    }

    private static StoredMessage sendMediaMessage(
            Config config,
            MessageStore store,
            String to,
            String requestedMediaType,
            UploadedFile file,
            String caption,
            String clientRequestId,
            String requestFingerprint
    ) throws Exception {
        String mediaType = normalizeMediaType(requestedMediaType);
        if (file == null || file.bytes.length == 0) {
            throw new IllegalArgumentException("file is required");
        }
        String fileName = firstNonBlank(file.fileName, "upload.bin");
        String mimeType = firstNonBlank(file.contentType, mimeTypeFromName(fileName), "application/octet-stream");
        validateMediaMime(mediaType, mimeType);

        try (AsyncClient client = createClient(config)) {
            GetChatappUploadAuthorizationResponse authResponse = client.getChatappUploadAuthorization(
                    GetChatappUploadAuthorizationRequest.builder()
                            .custSpaceId(config.required("CUST_SPACE_ID"))
                            .build()
            ).get();
            GetChatappUploadAuthorizationResponseBody authBody = authResponse.getBody();
            if (authBody == null) {
                throw new IllegalStateException("GetChatappUploadAuthorization returned empty body");
            }
            assertOk("GetChatappUploadAuthorization", authBody.getCode(), authBody.getMessage());
            GetChatappUploadAuthorizationResponseBody.Data auth = authBody.getData();
            if (auth == null) {
                throw new IllegalStateException("GetChatappUploadAuthorization returned empty data");
            }

            String objectKey = uploadObjectKey(auth.getDir(), fileName);
            String mediaUrl = ossObjectUrl(auth.getEndPoint(), auth.getBucketName(), objectKey);
            uploadToOss(auth, objectKey, file.bytes, mimeType);

            String content = mediaContentJson(mediaType, mediaUrl, caption, fileName);
            SendChatappMessageRequest.Builder builder = SendChatappMessageRequest.builder()
                    .custSpaceId(config.required("CUST_SPACE_ID"))
                    .from(config.required("CHATAPP_FROM"))
                    .to(requiredInline(to, "to"))
                    .messageType(mediaType)
                    .content(content);

            putIfPresent(config, "CHATAPP_CHANNEL_TYPE", builder::channelType);
            putIfPresent(config, "CHATAPP_TYPE", builder::type);
            putIfNotBlank(clientRequestId, builder::taskId);

            SendChatappMessageResponse response = client.sendChatappMessage(builder.build()).get();
            String messageId = response.getBody() == null ? "" : response.getBody().getMessageId();
            String raw = outboundMediaRaw(response, mediaType, content, mediaUrl, objectKey, fileName, mimeType, caption);
            String localMediaPath = store.saveMedia(file.bytes, fileName);
            StoredMessage message = StoredMessage.outbound(
                    messageId == null || messageId.isBlank() ? "local-" + UUID.randomUUID() : messageId,
                    config.required("CHATAPP_FROM"),
                    to,
                    mediaDisplayText(mediaType, caption, fileName),
                    raw
            );
            message.mediaType = mediaType;
            message.mediaUrl = mediaUrl;
            message.objectKey = objectKey;
            message.mimeType = mimeType;
            message.fileName = fileName;
            message.caption = caption == null ? "" : caption;
            message.localMediaPath = localMediaPath;
            message.clientRequestId = clientRequestId;
            message.requestFingerprint = requestFingerprint;
            return store.append(message);
        }
    }

    private static StoredMessage sendTemplateMessage(
            Config config,
            MessageStore store,
            TemplateStore templateStore,
            String to,
            String templateCode,
            String templateName,
            String languageCode,
            Map<String, String> templateParams
    ) throws Exception {
        return sendTemplateMessage(config, store, templateStore, to, templateCode, templateName, languageCode,
                templateParams, "", "");
    }

    private static StoredMessage sendTemplateMessage(
            Config config,
            MessageStore store,
            TemplateStore templateStore,
            String to,
            String templateCode,
            String templateName,
            String languageCode,
            Map<String, String> templateParams,
            String clientRequestId,
            String requestFingerprint
    ) throws Exception {
        String language = firstNonBlank(languageCode, config.value("CHATAPP_LANGUAGE", "en_US"));
        SendChatappMessageRequest.Builder builder = SendChatappMessageRequest.builder()
                .custSpaceId(config.required("CUST_SPACE_ID"))
                .from(config.required("CHATAPP_FROM"))
                .to(requiredInline(to, "to"))
                .messageType(config.value("CHATAPP_MESSAGE_TYPE", "text"))
                .templateCode(requiredInline(templateCode, "templateCode"))
                .language(language)
                .templateParams(templateParams == null ? Map.of() : templateParams);

        putIfPresent(config, "CHATAPP_CHANNEL_TYPE", builder::channelType);
        putIfPresent(config, "CHATAPP_TYPE", builder::type);
        putIfNotBlank(templateName, builder::templateName);
        putIfNotBlank(clientRequestId, builder::taskId);

        try (AsyncClient client = createClient(config)) {
            SendChatappMessageResponse response = client.sendChatappMessage(builder.build()).get();
            String messageId = response.getBody() == null ? "" : response.getBody().getMessageId();
            String paramsJson = GSON.toJson(templateParams == null ? Map.of() : templateParams);
            String raw = outboundTemplateRaw(response, templateCode, templateName, language, paramsJson);
            String text = renderOutboundTemplateText(templateStore, templateCode, templateName, language, paramsJson);
            StoredMessage message = StoredMessage.outbound(
                    messageId == null || messageId.isBlank() ? "local-" + UUID.randomUUID() : messageId,
                    config.required("CHATAPP_FROM"),
                    to,
                    text,
                    raw
            );
            message.clientRequestId = clientRequestId;
            message.requestFingerprint = requestFingerprint;
            return store.append(message);
        }
    }

    static String renderOutboundTemplateText(
            TemplateStore templateStore,
            String templateCode,
            String templateName,
            String languageCode,
            String templateParamsJson
    ) {
        return templateDisplayText(templateParamsJson, displayText(templateParamsJson), templateName, templateCode, languageCode, templateStore);
    }

    private static String outboundTemplateRaw(
            SendChatappMessageResponse response,
            String templateCode,
            String templateName,
            String languageCode,
            String templateParamsJson
    ) {
        JsonObject raw = new JsonObject();
        raw.addProperty("messageTypeName", "template");
        raw.addProperty("messageType", "TEMPLATE");
        raw.addProperty("templateCode", templateCode);
        raw.addProperty("templateName", templateName == null ? "" : templateName);
        raw.addProperty("languageCode", languageCode == null ? "" : languageCode);
        raw.addProperty("message", templateParamsJson == null ? "{}" : templateParamsJson);
        if (response.getBody() != null) {
            JsonElement body = GSON.toJsonTree(response.getBody());
            raw.add("response", body);
            String messageId = response.getBody().getMessageId();
            if (messageId != null && !messageId.isBlank()) {
                raw.addProperty("messageId", messageId);
            }
        }
        return GSON.toJson(raw);
    }

    static String mediaContentJson(String mediaType, String link, String caption, String fileName) {
        String normalizedType = normalizeMediaType(mediaType);
        JsonObject content = new JsonObject();
        content.addProperty("link", requiredInline(link, "link"));
        if (caption != null && !caption.isBlank()) {
            content.addProperty("caption", caption);
        }
        if ("document".equals(normalizedType) && fileName != null && !fileName.isBlank()) {
            content.addProperty("fileName", fileName);
        }
        return GSON.toJson(content);
    }

    static String uploadObjectKey(String dir, String fileName) {
        String prefix = dir == null ? "" : dir.trim().replace('\\', '/');
        while (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        if (!prefix.isBlank() && !prefix.endsWith("/")) {
            prefix += "/";
        }
        return prefix + UUID.randomUUID().toString().replace("-", "") + safeExtension(fileName);
    }

    private static String safeExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.trim();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String extension = name.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,12}") ? extension : "";
    }

    private static String outboundMediaRaw(
            SendChatappMessageResponse response,
            String mediaType,
            String content,
            String mediaUrl,
            String objectKey,
            String fileName,
            String mimeType,
            String caption
    ) {
        JsonObject raw = new JsonObject();
        raw.addProperty("messageTypeName", mediaType);
        raw.addProperty("messageType", mediaType);
        raw.addProperty("message", content);
        raw.add("content", JsonParser.parseString(content));
        raw.addProperty("link", mediaUrl);
        raw.addProperty("objectKey", objectKey);
        raw.addProperty("fileName", fileName);
        raw.addProperty("mimeType", mimeType);
        raw.addProperty("caption", caption == null ? "" : caption);
        if (response.getBody() != null) {
            JsonElement body = GSON.toJsonTree(response.getBody());
            raw.add("response", body);
            String messageId = response.getBody().getMessageId();
            if (messageId != null && !messageId.isBlank()) {
                raw.addProperty("messageId", messageId);
            }
        }
        return GSON.toJson(raw);
    }

    private static MediaMetadata mediaMetadata(JsonElement root) {
        MediaMetadata metadata = new MediaMetadata();
        if (root == null || root.isJsonNull()) {
            return metadata;
        }
        String type = WebhookParser.first(root, "mediaType", "messageTypeName", "MessageTypeName", "messageType", "MessageType");
        metadata.mediaUrl = WebhookParser.first(root, "mediaUrl", "link", "Link", "url", "Url");
        metadata.objectKey = WebhookParser.first(root, "objectKey", "ObjectKey");
        metadata.fileName = WebhookParser.first(root, "fileName", "FileName", "filename");
        metadata.caption = WebhookParser.first(root, "caption", "Caption");
        metadata.mimeType = WebhookParser.first(root, "mimeType", "MimeType", "contentType", "ContentType");

        JsonObject content = parseJsonObject(WebhookParser.first(root, "content", "Content", "message", "Message"));
        if (content != null) {
            metadata.mediaUrl = firstNonBlank(metadata.mediaUrl, jsonString(content, "link"), jsonString(content, "url"));
            metadata.fileName = firstNonBlank(metadata.fileName, jsonString(content, "fileName"), jsonString(content, "filename"));
            metadata.caption = firstNonBlank(metadata.caption, jsonString(content, "caption"));
            metadata.mimeType = firstNonBlank(metadata.mimeType, jsonString(content, "mimeType"), jsonString(content, "contentType"));
        }

        metadata.mediaType = normalizeMediaTypeOrBlank(type);
        if (metadata.mediaType.isBlank()) {
            metadata.mediaType = inferMediaType(metadata.mimeType, metadata.fileName, metadata.mediaUrl);
        }
        if (metadata.mediaType.isBlank()
                || (metadata.mediaUrl.isBlank() && metadata.fileName.isBlank()
                && metadata.caption.isBlank() && !isKnownMediaType(type))) {
            metadata.mediaType = "";
        }
        return metadata;
    }

    private static String normalizeMediaTypeOrBlank(String mediaType) {
        try {
            return normalizeMediaType(mediaType);
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static boolean isKnownMediaType(String mediaType) {
        return !normalizeMediaTypeOrBlank(mediaType).isBlank();
    }

    private static String inferMediaType(String mimeType, String fileName, String link) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (mime.startsWith("image/")) {
            return "image";
        }
        if (mime.startsWith("video/")) {
            return "video";
        }
        String name = firstNonBlank(fileName, link, "").toLowerCase(Locale.ROOT);
        if (name.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp)$")) {
            return "image";
        }
        if (name.matches(".*\\.(mp4|mov|m4v|avi|webm|mkv)$")) {
            return "video";
        }
        return name.isBlank() ? "" : "document";
    }

    private static String normalizeMediaType(String mediaType) {
        String value = mediaType == null ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "image":
            case "photo":
                return "image";
            case "video":
                return "video";
            case "file":
            case "document":
                return "document";
            default:
                throw new IllegalArgumentException("Unsupported mediaType: " + mediaType);
        }
    }

    private static void validateMediaMime(String mediaType, String mimeType) {
        String value = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if ("image".equals(mediaType) && !value.startsWith("image/")) {
            throw new IllegalArgumentException("图片消息只能上传 image/* 文件，当前是: " + mimeType);
        }
        if ("video".equals(mediaType) && !value.startsWith("video/")) {
            throw new IllegalArgumentException("视频消息只能上传 video/* 文件，当前是: " + mimeType);
        }
    }

    private static String mimeTypeFromName(String fileName) {
        String guessed = java.net.URLConnection.guessContentTypeFromName(fileName);
        return guessed == null ? "" : guessed;
    }

    private static String fileExtension(String fileName) {
        String value = fileName == null ? "" : fileName.trim();
        int index = value.lastIndexOf('.');
        if (index < 0 || index == value.length() - 1) {
            return "";
        }
        String extension = value.substring(index).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : "";
    }

    private static String mediaDisplayText(String mediaType, String caption, String fileName) {
        String label = mediaLabel(mediaType);
        String suffix = firstNonBlank(caption, fileName, "");
        return suffix.isBlank() ? "[" + label + "]" : "[" + label + "] " + suffix;
    }

    private static String mediaLabel(String mediaType) {
        String value;
        try {
            value = normalizeMediaType(mediaType);
        } catch (RuntimeException ex) {
            value = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        }
        switch (value) {
            case "image":
                return "图片";
            case "video":
                return "视频";
            case "document":
            case "file":
                return "文件";
            default:
                return "附件";
        }
    }

    private static String ossObjectUrl(String endpoint, String bucketName, String objectKey) {
        String bucket = requiredInline(bucketName, "bucketName");
        String host = requiredInline(endpoint, "endpoint").trim()
                .replaceFirst("(?i)^https?://", "")
                .replaceAll("/+$", "");
        if (!host.startsWith(bucket + ".")) {
            host = bucket + "." + host;
        }
        return "https://" + host + "/" + objectKey;
    }

    static String mediaProxyUrl(StoredMessage message) {
        if (message == null || message.id == null || message.id.isBlank()) {
            return "";
        }
        if (message.localMediaPath == null || message.localMediaPath.isBlank()) {
            return "";
        }
        return "/api/media?id=" + URLEncoder.encode(message.id, StandardCharsets.UTF_8);
    }

    private static void uploadToOss(
            GetChatappUploadAuthorizationResponseBody.Data auth,
            String objectKey,
            byte[] bytes,
            String mimeType
    ) throws Exception {
        String bucketName = requiredInline(auth.getBucketName(), "bucketName");
        String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC));
        String resource = "/" + bucketName + "/" + objectKey;
        String securityToken = auth.getSecurityToken();
        String canonicalOssHeaders = securityToken == null || securityToken.isBlank()
                ? ""
                : "x-oss-security-token:" + securityToken + "\n";
        String stringToSign = "PUT\n\n" + mimeType + "\n" + date + "\n" + canonicalOssHeaders + resource;
        String authorization = "OSS " + requiredInline(auth.getAccessKeyId(), "accessKeyId")
                + ":" + hmacSha1Base64(requiredInline(auth.getAccessKeySecret(), "accessKeySecret"), stringToSign);

        URL url = new URL(ossObjectUrl(auth.getEndPoint(), bucketName, objectKey));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("Date", date);
        connection.setRequestProperty("Content-Type", mimeType);
        connection.setRequestProperty("Authorization", authorization);
        if (securityToken != null && !securityToken.isBlank()) {
            connection.setRequestProperty("x-oss-security-token", securityToken);
        }
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            String error = readResponseText(connection);
            throw new IllegalStateException("OSS upload failed: HTTP " + status + " " + error);
        }
    }

    static MediaContent loadMediaContent(MessageStore store, StoredMessage message) throws Exception {
        Path local = store.resolveLocalMedia(message);
        if (local != null) {
            return new MediaContent(Files.readAllBytes(local), firstNonBlank(message.mimeType, mimeTypeFromName(message.fileName)));
        }
        throw new MediaUnavailableException("historical media was not retained locally");
    }

    private static String hmacSha1Base64(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String readResponseText(HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getErrorStream();
        if (stream == null) {
            stream = connection.getInputStream();
        }
        try (InputStream input = stream) {
            return input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void setPhoneWebhook(Config config) throws Exception {
        try (AsyncClient client = createClient(config)) {
            Object body = client.updatePhoneWebhook(UpdatePhoneWebhookRequest.builder()
                    .custSpaceId(config.required("CUST_SPACE_ID"))
                    .phoneNumber(config.required("CHATAPP_FROM"))
                    .httpFlag("Y")
                    .queueFlag("N")
                    .upCallbackUrl(config.required("WEBHOOK_URL"))
                    .statusCallbackUrl(config.required("WEBHOOK_URL"))
                    .build()).get().getBody();
            System.out.println(GSON.toJson(body));
        }
    }

    private static void setAccountWebhook(Config config) throws Exception {
        try (AsyncClient client = createClient(config)) {
            Object body = client.updateAccountWebhook(UpdateAccountWebhookRequest.builder()
                    .custSpaceId(config.required("CUST_SPACE_ID"))
                    .httpFlag("Y")
                    .queueFlag("N")
                    .statusCallbackUrl(config.required("WEBHOOK_URL"))
                    .build()).get().getBody();
            System.out.println(GSON.toJson(body));
        }
    }

    private static void startWeb(Config config, MessageStore store, TemplateStore templateStore) throws IOException {
        int port = Integer.parseInt(config.value("SERVER_PORT", "8077"));
        EventHub events = new EventHub();
        IdempotentRequestExecutor requestExecutor = new IdempotentRequestExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            respond(exchange, 200, "text/html; charset=utf-8", html());
        });

        server.createContext("/api/messages", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
                return;
            }
            respond(exchange, 200, "application/json; charset=utf-8", GSON.toJson(store.readAll()));
        });

        server.createContext("/api/templates", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
                return;
            }
            respond(exchange, 200, "application/json; charset=utf-8", GSON.toJson(templateStore.readAll()));
        });

        server.createContext("/api/media", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
                return;
            }
            try {
                String id = parseForm(exchange.getRequestURI().getRawQuery()).get("id");
                StoredMessage message = store.findMediaById(requiredInline(id, "id"));
                if (message == null) {
                    respond(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"media_not_found\"}");
                    return;
                }
                MediaContent media = loadMediaContent(store, message);
                respondBytes(exchange, 200, firstNonBlank(media.mimeType, "application/octet-stream"), media.bytes);
            } catch (MediaUnavailableException ex) {
                respondError(exchange, 410, ex);
            } catch (IllegalArgumentException ex) {
                respondError(exchange, 400, ex);
            } catch (Exception ex) {
                respondError(exchange, 502, ex);
            }
        });

        server.createContext("/api/send", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
                return;
            }
            try {
                Map<String, String> form = parseForm(readBody(exchange));
                String clientRequestId = requiredClientRequestId(form.get("clientRequestId"));
                String fingerprint = requestFingerprint(form);
                StoredMessage sent = executeIdempotently(store, requestExecutor, clientRequestId, fingerprint, () -> {
                    if ("template".equalsIgnoreCase(form.get("mode"))) {
                        return sendTemplateMessage(
                                config,
                                store,
                                templateStore,
                                form.get("to"),
                                form.get("templateCode"),
                                form.get("templateName"),
                                form.get("language"),
                                parseStringMap(form.getOrDefault("templateParamsJson", "{}")),
                                clientRequestId,
                                fingerprint
                        );
                    }
                    return sendMessage(config, store, form.get("to"), form.get("text"), clientRequestId, fingerprint);
                });
                events.publish(sent);
                respond(exchange, 200, "application/json; charset=utf-8", GSON.toJson(sent));
            } catch (IdempotencyConflictException ex) {
                respondError(exchange, 409, ex);
            } catch (IllegalArgumentException ex) {
                respondError(exchange, 400, ex);
            } catch (Exception ex) {
                respondError(exchange, 500, ex);
            }
        });

        server.createContext("/api/send-media", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
                return;
            }
            try {
                MultipartForm form = parseMultipart(exchange, config.mediaMaxBytes() + 65536);
                String clientRequestId = requiredClientRequestId(form.field("clientRequestId"));
                String fingerprint = requestFingerprint(form);
                StoredMessage sent = executeIdempotently(store, requestExecutor, clientRequestId, fingerprint, () ->
                        sendMediaMessage(
                                config,
                                store,
                                form.field("to"),
                                form.field("mediaType"),
                                form.file("file"),
                                form.field("caption"),
                                clientRequestId,
                                fingerprint
                        ));
                events.publish(sent);
                respond(exchange, 200, "application/json; charset=utf-8", GSON.toJson(sent));
            } catch (PayloadTooLargeException ex) {
                respondError(exchange, 413, ex);
            } catch (IdempotencyConflictException ex) {
                respondError(exchange, 409, ex);
            } catch (IllegalArgumentException ex) {
                respondError(exchange, 400, ex);
            } catch (Exception ex) {
                respondError(exchange, 500, ex);
            }
        });

        server.createContext("/webhook/chatapp", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 200, "text/plain", "ok");
                return;
            }
            String raw = readBody(exchange);
            StoredMessage message = WebhookParser.parse(raw);
            StoredMessage saved = store.append(message);
            events.publish(saved);
            respond(exchange, 200, "text/plain", "ok");
        });

        server.createContext("/events", exchange -> events.connect(exchange));

        server.start();
        System.out.println("ChatApp demo started: http://localhost:" + port);
        System.out.println("Webhook endpoint: http://localhost:" + port + "/webhook/chatapp");
    }

    private static void putIfPresent(Config config, String key, StringSetter setter) {
        String value = config.value(key, "");
        if (!value.isBlank()) {
            setter.set(value);
        }
    }

    private static void putIfNotBlank(String value, StringSetter setter) {
        if (value != null && !value.isBlank()) {
            setter.set(value);
        }
    }

    private static Map<String, String> parseStringMap(String json) {
        try {
            Map<String, String> parsed = GSON.fromJson(json, STRING_MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("CHATAPP_TEMPLATE_PARAMS_JSON 不是合法 JSON 对象: " + json, ex);
        }
    }

    static StoredMessage executeIdempotently(
            MessageStore store,
            IdempotentRequestExecutor executor,
            String clientRequestId,
            String fingerprint,
            Callable<StoredMessage> action
    ) throws Exception {
        StoredMessage existing = store.findByClientRequestId(clientRequestId);
        if (existing != null) {
            assertMatchingFingerprint(existing, fingerprint);
            return existing;
        }
        return executor.execute(clientRequestId, fingerprint, () -> {
            StoredMessage concurrentExisting = store.findByClientRequestId(clientRequestId);
            if (concurrentExisting != null) {
                assertMatchingFingerprint(concurrentExisting, fingerprint);
                return concurrentExisting;
            }
            return action.call();
        });
    }

    private static void assertMatchingFingerprint(StoredMessage message, String fingerprint) {
        if (!Objects.equals(message.requestFingerprint, fingerprint)) {
            throw new IdempotencyConflictException("clientRequestId already belongs to a different message payload");
        }
    }

    private static String requiredClientRequestId(String value) {
        String requestId = requiredInline(value, "clientRequestId");
        if (!requestId.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new IllegalArgumentException("clientRequestId must be 8-128 safe characters");
        }
        return requestId;
    }

    private static String requestFingerprint(Map<String, String> form) {
        Map<String, String> values = new LinkedHashMap<>(form);
        values.remove("clientRequestId");
        return sha256(GSON.toJson(values));
    }

    private static String requestFingerprint(MultipartForm form) {
        JsonObject value = new JsonObject();
        form.fields.entrySet().stream()
                .filter(entry -> !"clientRequestId".equals(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> value.addProperty(entry.getKey(), entry.getValue()));
        UploadedFile file = form.file("file");
        if (file != null) {
            value.addProperty("fileName", file.fileName);
            value.addProperty("contentType", file.contentType);
            value.addProperty("fileSha256", sha256(file.bytes));
        }
        return sha256(GSON.toJson(value));
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static void respondError(HttpExchange exchange, int status, Exception ex) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("error", ex.getClass().getSimpleName());
        error.addProperty("message", ex.getMessage());
        respond(exchange, status, "application/json; charset=utf-8", GSON.toJson(error));
    }

    private static String textContentJson(String text) {
        JsonObject content = new JsonObject();
        content.addProperty("text", requiredInline(text, "text"));
        return GSON.toJson(content);
    }

    private static String requiredInline(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> result = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            String value = parts.length > 1 ? decode(parts[1]) : "";
            result.put(key, value);
        }
        return result;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static MultipartForm parseMultipart(HttpExchange exchange, long maxBytes) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String boundary = multipartBoundary(contentType);
        byte[] body = readBodyBytes(exchange, maxBytes);
        return parseMultipartBody(body, boundary);
    }

    private static String multipartBoundary(String contentType) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            throw new IllegalArgumentException("Content-Type must be multipart/form-data");
        }
        for (String item : contentType.split(";")) {
            String part = item.trim();
            if (part.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                return stripQuotes(part.substring("boundary=".length()).trim());
            }
        }
        throw new IllegalArgumentException("multipart boundary is missing");
    }

    private static byte[] readBodyBytes(HttpExchange exchange, long maxBytes) throws IOException {
        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new PayloadTooLargeException("附件超过大小限制，当前上限是 " + maxBytes + " bytes");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    static MultipartForm parseMultipartBody(byte[] body, String boundary) {
        MultipartForm form = new MultipartForm();
        String raw = new String(body, StandardCharsets.ISO_8859_1);
        String marker = "--" + boundary;
        int position = raw.indexOf(marker);
        while (position >= 0) {
            int partStart = position + marker.length();
            if (raw.startsWith("--", partStart)) {
                break;
            }
            if (raw.startsWith("\r\n", partStart)) {
                partStart += 2;
            } else if (raw.startsWith("\n", partStart)) {
                partStart += 1;
            }

            int headerEnd = raw.indexOf("\r\n\r\n", partStart);
            int separatorLength = 4;
            if (headerEnd < 0) {
                headerEnd = raw.indexOf("\n\n", partStart);
                separatorLength = 2;
            }
            if (headerEnd < 0) {
                break;
            }
            int contentStart = headerEnd + separatorLength;
            int contentEnd = raw.indexOf("\r\n" + marker, contentStart);
            if (contentEnd < 0) {
                contentEnd = raw.indexOf("\n" + marker, contentStart);
            }
            if (contentEnd < 0) {
                break;
            }

            Map<String, String> headers = multipartHeaders(raw.substring(partStart, headerEnd));
            String disposition = headers.getOrDefault("content-disposition", "");
            String name = dispositionValue(disposition, "name");
            String fileName = multipartFileName(disposition);
            byte[] content = Arrays.copyOfRange(body, contentStart, contentEnd);
            if (!name.isBlank()) {
                if (!fileName.isBlank()) {
                    form.files.put(name, new UploadedFile(fileName,
                            headers.getOrDefault("content-type", "application/octet-stream"), content));
                } else {
                    form.fields.put(name, new String(content, StandardCharsets.UTF_8));
                }
            }
            position = raw.indexOf(marker, contentEnd);
        }
        return form;
    }

    private static Map<String, String> multipartHeaders(String value) {
        Map<String, String> headers = new HashMap<>();
        for (String line : value.split("\\r?\\n")) {
            int index = line.indexOf(':');
            if (index <= 0) {
                continue;
            }
            headers.put(line.substring(0, index).trim().toLowerCase(Locale.ROOT),
                    line.substring(index + 1).trim());
        }
        return headers;
    }

    private static String dispositionValue(String disposition, String key) {
        for (String part : disposition.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith(key.toLowerCase(Locale.ROOT) + "=")) {
                return stripQuotes(trimmed.substring(key.length() + 1).trim());
            }
        }
        return "";
    }

    private static String multipartFileName(String disposition) {
        String encoded = dispositionValue(disposition, "filename*");
        if (!encoded.isBlank()) {
            int separator = encoded.indexOf("''");
            String value = separator >= 0 ? encoded.substring(separator + 2) : encoded;
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        }
        String value = dispositionValue(disposition, "filename");
        if (value.isBlank()) {
            return "";
        }
        byte[] rawBytes = value.getBytes(StandardCharsets.ISO_8859_1);
        String utf8 = new String(rawBytes, StandardCharsets.UTF_8);
        return utf8.indexOf('\uFFFD') >= 0 ? value : utf8;
    }

    private static String stripQuotes(String value) {
        if (value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value == null ? "" : value;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void respondBytes(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "private, no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    static String emojiCatalogJson() {
        List<Map<String, Object>> categories = new ArrayList<>();
        categories.add(emojiCategory("Smileys", "😀", "😃", "😄", "😁", "😊", "🙂", "😉", "😍", "😘", "😎", "🤔", "😅", "😂", "🤣", "😇", "😐", "😕", "🙁", "😭", "😤"));
        categories.add(emojiCategory("Gestures", "👍", "👎", "👌", "👏", "🙏", "🤝", "💪", "👀", "✍️", "🙌", "👋", "🤟", "☝️", "👇", "👈", "👉"));
        categories.add(emojiCategory("Business", "📦", "🚚", "🚢", "✈️", "🏭", "🏢", "📍", "🧾", "📄", "📎", "📌", "📅", "⏰", "✅", "❌", "⚠️", "💬", "📞", "💰", "📊"));
        categories.add(emojiCategory("Symbols", "❤️", "💙", "💚", "💛", "⭐", "🔥", "💡", "🎉", "🔔", "🔒", "🔗", "➡️", "⬅️", "⬆️", "⬇️", "✔️"));
        return GSON.toJson(categories);
    }

    private static Map<String, Object> emojiCategory(String name, String... items) {
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("name", name);
        category.put("items", List.of(items));
        return category;
    }

    static String html() {
        return String.join("",
                "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">",
                "<title>ChatApp Demo</title><style>",
                "*,*::before,*::after{box-sizing:border-box}body{margin:0;font-family:Arial,'Microsoft YaHei',sans-serif;background:#f5f7fb;color:#172033}main{height:100vh;display:grid;grid-template-columns:320px minmax(0,1fr) minmax(280px,360px);overflow:hidden}",
                ".side,.detail{min-width:0;background:#fff;border-right:1px solid #dde3ee;overflow:auto}.detail{border-left:1px solid #dde3ee;border-right:0}.top{height:54px;display:flex;align-items:center;padding:0 16px;border-bottom:1px solid #dde3ee;font-weight:700}",
                ".contact{padding:12px 16px;border-bottom:1px solid #edf1f7;cursor:pointer}.contact:hover,.contact.active{background:#edf4ff}.contact b{display:block}.contact span{display:block;color:#667085;font-size:12px;margin-top:4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
                ".chat{display:grid;grid-template-rows:54px minmax(0,1fr) auto;height:100vh;min-width:0}.messages{overflow:auto;padding:18px;min-width:0}.msg{max-width:72%;min-width:0;margin:0 0 12px;padding:10px 12px;border-radius:8px;background:#fff;border:1px solid #dde3ee;cursor:pointer;overflow-wrap:anywhere;word-break:break-word}.msg-text{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word}.msg.outbound{margin-left:auto;background:#e8f2ff;border-color:#c7defc}.meta{font-size:12px;color:#667085;margin-bottom:6px}.state{font-size:12px;color:#667085;margin-top:8px;text-align:right;overflow-wrap:anywhere}.media-preview{display:block;max-width:100%;max-height:260px;margin-top:8px;border-radius:6px;border:1px solid #d5deeb;background:#f8fafd}.media-link{display:flex;align-items:center;gap:8px;margin-top:8px;padding:9px 10px;border:1px solid #d5deeb;border-radius:6px;background:#fff;color:#1769e0;text-decoration:none;font-weight:700}.media-unavailable{margin-top:8px;padding:9px 10px;border:1px dashed #d0a04a;border-radius:6px;background:#fff8e8;color:#8a5a00;font-size:12px}",
                ".compose{display:flex;flex-direction:column;gap:10px;max-height:38vh;overflow:auto;padding:12px;border-top:1px solid #dde3ee;background:#fff}.compose-toolbar,.compose-actions{display:flex;align-items:center;justify-content:space-between;gap:12px}.tool-group,.mode-group{display:flex;align-items:center;gap:8px;min-width:0}.icon-btn{width:32px;height:32px;border:1px solid #cfd8e6;border-radius:6px;background:#fff;color:#667085;font-size:17px;font-weight:700}.icon-btn:hover,.icon-btn.active{background:#f5f7fb;color:#172033;border-color:#9db2d0}.media-panel{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:8px 10px;border:1px solid #dde3ee;border-radius:6px;background:#f8fafd}.media-panel[hidden]{display:none}.media-file{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#344054;font-weight:700}.emoji-panel{display:grid;grid-template-columns:132px minmax(0,1fr);min-height:160px;max-height:220px;overflow:hidden;border:1px solid #dde3ee;border-radius:8px;background:#fff;box-shadow:0 8px 24px rgba(23,32,51,.08)}.emoji-panel[hidden]{display:none}.emoji-tabs{overflow:auto;border-right:1px solid #edf1f7;background:#f8fafd}.emoji-tab{display:block;width:100%;height:36px;padding:0 10px;border:0;border-radius:0;background:transparent;color:#344054;text-align:left;font-weight:700}.emoji-tab.active{background:#e8f2ff;color:#1769e0}.emoji-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(34px,1fr));gap:4px;align-content:start;overflow:auto;padding:10px}.emoji-item{height:32px;border:0;border-radius:6px;background:#fff;font-size:20px}.emoji-item:hover{background:#edf4ff}.seg{height:32px;padding:0 12px;border:1px solid #cfd8e6;background:#fff;color:#344054;font-weight:700}.seg.active{background:#1769e0;border-color:#1769e0;color:#fff}textarea{resize:none;height:70px;min-width:0;padding:10px;border:1px solid #cfd8e6;border-radius:6px;font:inherit}button.primary{min-width:96px;height:40px;border:0;border-radius:6px;background:#1769e0;color:#fff;font-weight:700}.hint{color:#718096;font-size:12px}.template-panel{display:flex;flex-direction:column;gap:10px;padding:10px;border:1px solid #dde3ee;border-radius:6px;background:#f8fafd}.template-panel[hidden]{display:none}.template-row{display:grid;grid-template-columns:minmax(0,1fr);gap:8px}select,input{height:34px;min-width:0;padding:0 10px;border:1px solid #cfd8e6;border-radius:6px;background:#fff;font:inherit}.template-fields{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:8px}.template-fields label{display:flex;flex-direction:column;gap:4px;font-size:12px;color:#667085}.template-preview{min-height:42px;max-height:96px;overflow:auto;white-space:pre-wrap;word-break:break-word;padding:8px;border:1px dashed #b8c4d6;border-radius:6px;background:#fff;color:#344054}",
                ".detail pre{white-space:pre-wrap;word-break:break-word;overflow-wrap:anywhere;max-width:100%;overflow:auto;margin:16px;padding:12px;background:#f5f7fb;border:1px solid #dde3ee;border-radius:6px}@media(max-width:760px){main{height:auto;min-height:100vh;grid-template-columns:1fr;grid-template-rows:minmax(160px,32vh) minmax(520px,1fr) minmax(220px,40vh);overflow:auto}.side,.detail,.chat{height:auto;min-height:0}.chat{min-height:520px}.messages{min-height:0}.msg{max-width:92%}.compose{max-height:none}.compose-toolbar,.compose-actions{align-items:stretch;flex-direction:column}.tool-group,.mode-group{width:100%;flex-wrap:wrap}.seg{flex:1}.detail pre{max-height:32vh}}.toast{position:fixed;right:16px;bottom:16px;background:#172033;color:#fff;padding:10px 14px;border-radius:6px;display:none;z-index:10}",
                "</style></head><body><main><section class=\"side\"><div class=\"top\">联系人</div><div id=\"contacts\"></div></section><section class=\"chat\"><div class=\"top\" id=\"title\">ChatApp 消息</div><div class=\"messages\" id=\"messages\"></div>",
                "<form class=\"compose\" id=\"form\"><div class=\"compose-toolbar\"><div class=\"tool-group\"><button type=\"button\" class=\"icon-btn\" data-tool=\"emoji\" id=\"emojiButton\" title=\"表情\">☺</button><button type=\"button\" class=\"icon-btn\" data-media=\"image\" title=\"图片\">▧</button><button type=\"button\" class=\"icon-btn\" data-media=\"video\" title=\"视频\">▤</button><button type=\"button\" class=\"icon-btn\" data-media=\"document\" title=\"文件\">□</button></div><div class=\"mode-group\"><button type=\"button\" class=\"seg active\" id=\"modeText\">普通文本</button><button type=\"button\" class=\"seg\" id=\"modeTemplate\">模板消息</button></div></div>",
                "<div class=\"emoji-panel\" id=\"emojiPanel\" hidden></div>",
                "<input type=\"file\" id=\"fileInput\" hidden><div class=\"media-panel\" id=\"mediaPanel\" hidden><span class=\"media-file\" id=\"mediaFile\"></span><button type=\"button\" class=\"icon-btn\" id=\"clearMedia\" title=\"移除附件\">×</button></div>",
                "<div class=\"template-panel\" id=\"templatePanel\" hidden><div class=\"template-row\"><select id=\"templateSelect\"></select></div><div class=\"template-fields\" id=\"templateFields\"></div><div class=\"template-preview\" id=\"templatePreview\"></div></div>",
                "<textarea name=\"text\" id=\"textInput\" placeholder=\"输入消息\"></textarea><div class=\"compose-actions\"><span class=\"hint\">Enter 发送 / Ctrl+Enter 换行</span><button class=\"primary\" id=\"sendButton\" type=\"submit\">发送</button></div></form></section>",
                "<section class=\"detail\"><div class=\"top\">消息详情</div><pre id=\"detail\">点击一条消息查看原始内容</pre></section></main><div class=\"toast\" id=\"toast\">收到新消息</div>",
                "<script>",
                "let all=[],active='',selected=null,templates=[],sendMode='text',lastInputTarget=null,activeEmojiCategory='',pendingMediaType='',pendingFile=null,sendInFlight=false,pendingRequestKey='',pendingRequestSignature='';const $=id=>document.getElementById(id);",
                "const nativeFetch=window.fetch.bind(window);function requestId(){return 'web-'+Date.now().toString(36)+'-'+crypto.getRandomValues(new Uint32Array(2)).join('-')}function bodySignature(body){if(body instanceof URLSearchParams)return body.toString();if(body instanceof FormData)return [...body.entries()].map(([k,v])=>v instanceof File?`${k}:${v.name}:${v.size}:${v.type}:${v.lastModified}`:`${k}:${v}`).join('&');return String(body||'')}function guardedFetch(url,options={}){if(url!=='/api/send'&&url!=='/api/send-media')return nativeFetch(url,options);const signature=url+'|'+bodySignature(options.body);if(signature!==pendingRequestSignature){pendingRequestSignature=signature;pendingRequestKey=requestId()}if(options.body instanceof URLSearchParams||options.body instanceof FormData)options.body.set('clientRequestId',pendingRequestKey);sendInFlight=true;$('sendButton').disabled=true;return nativeFetch(url,options).then(response=>{if(response.ok&&signature===pendingRequestSignature){pendingRequestKey='';pendingRequestSignature=''}return response}).finally(()=>{sendInFlight=false;$('sendButton').disabled=false})}window.fetch=guardedFetch;",
                "const emojiCatalog=", emojiCatalogJson(), ";",
                "function peer(m){return m.direction==='inbound'?m.from:(m.to||m.from)}",
                "function load(options={}){const contactScroll=$('contacts').scrollTop;fetch('/api/messages').then(r=>r.json()).then(d=>{all=d;render({...options,contactScroll})})}",
                "function loadTemplates(){fetch('/api/templates').then(r=>r.json()).then(d=>{templates=Array.isArray(d)?d:[];renderTemplateSelect()}).catch(()=>{templates=[];renderTemplateSelect()})}",
                "function render(options={}){all.sort((a,b)=>a.timestamp.localeCompare(b.timestamp));const peers=[...new Set(all.map(peer).filter(Boolean))];if(!active&&peers.length)active=peers[0];const contacts=$('contacts');contacts.innerHTML=peers.map(p=>{const ms=all.filter(m=>peer(m)===p);const last=ms[ms.length-1]||{};return `<div class=\"contact ${p===active?'active':''}\" data-peer=\"${escapeHtml(p)}\"><b>${escapeHtml(p)}</b><span>${escapeHtml(last.text||'')}</span></div>`}).join('');contacts.scrollTop=options.contactScroll||0;$('title').textContent=active||'ChatApp 消息';const box=$('messages');const stick=options.forceBottom||box.scrollHeight-box.scrollTop-box.clientHeight<80;const ms=all.filter(m=>peer(m)===active);box.innerHTML=ms.map(m=>`<div class=\"msg ${m.direction}\" data-id=\"${escapeHtml(m.id)}\"><div class=\"meta\">${escapeHtml(m.direction)} · ${new Date(m.timestamp).toLocaleString()}</div><div class=\"msg-text\">${escapeHtml(m.text||'(无文本)')}</div>${mediaMarkup(m)}${m.status?`<div class=\"state\">${escapeHtml(m.status)}</div>`:''}</div>`).join('');if(stick)box.scrollTop=box.scrollHeight}",
                "function show(id){selected=all.find(m=>m.id===id);$('detail').textContent=JSON.stringify(selected,null,2)}",
                "function escapeHtml(s){return String(s??'').replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]))}",
                "function mediaMarkup(m){const type=String(m.mediaType||'').toLowerCase();const retained=Boolean(m.localMediaPath);const known=Boolean(m.mediaUrl||m.objectKey||m.localMediaPath);if(!type&&!known)return '';const label=escapeHtml(m.fileName||m.caption||'附件');if(!retained)return `<div class=\"media-unavailable\">历史${type==='image'?'图片':'附件'}未在本地留存，请重新发送</div>`;const url='/api/media?id='+encodeURIComponent(m.id||'');const safeUrl=escapeHtml(url);if(type==='image')return `<img class=\"media-preview\" src=\"${safeUrl}\" alt=\"${label}\">`;if(type==='video')return `<video class=\"media-preview\" controls src=\"${safeUrl}\"></video>`;return `<a class=\"media-link\" href=\"${safeUrl}\" target=\"_blank\" rel=\"noopener\">附件 ${label}</a>`}",
                "function toast(text){const t=$('toast');t.textContent=text;t.style.display='block';clearTimeout(t.timer);t.timer=setTimeout(()=>t.style.display='none',1800)}",
                "function renderEmojiPanel(){const panel=$('emojiPanel');if(!Array.isArray(emojiCatalog)||!emojiCatalog.length){panel.innerHTML='<div class=\"hint\">没有可用表情</div>';return}if(!activeEmojiCategory)activeEmojiCategory=emojiCatalog[0].name;let cat=emojiCatalog.find(c=>c.name===activeEmojiCategory)||emojiCatalog[0];activeEmojiCategory=cat.name;const tabs=emojiCatalog.map(c=>`<button type=\"button\" class=\"emoji-tab ${c.name===activeEmojiCategory?'active':''}\" data-emoji-category=\"${escapeHtml(c.name)}\">${escapeHtml(c.name)}</button>`).join('');const items=(cat.items||[]).map(item=>`<button type=\"button\" class=\"emoji-item\" data-emoji=\"${escapeHtml(item)}\">${escapeHtml(item)}</button>`).join('');panel.innerHTML=`<div class=\"emoji-tabs\">${tabs}</div><div class=\"emoji-grid\">${items}</div>`}",
                "function toggleEmojiPanel(){const panel=$('emojiPanel');const next=panel.hidden;renderEmojiPanel();panel.hidden=!next;$('emojiButton').classList.toggle('active',next)}",
                "function hideEmojiPanel(){if(!$('emojiPanel').hidden){$('emojiPanel').hidden=true;$('emojiButton').classList.remove('active')}}",
                "function mediaAccept(type){return type==='image'?'image/*':type==='video'?'video/*':'*/*'}",
                "function formatBytes(n){if(!Number.isFinite(n))return '';if(n<1024)return n+' B';if(n<1024*1024)return (n/1024).toFixed(1)+' KB';return (n/1024/1024).toFixed(1)+' MB'}",
                "function chooseMedia(type){hideEmojiPanel();setMode('text');pendingMediaType=type;const input=$('fileInput');input.accept=mediaAccept(type);input.value='';input.click()}",
                "function clearPendingMedia(){pendingMediaType='';pendingFile=null;$('fileInput').value='';updateMediaPanel()}",
                "function updateMediaPanel(){const panel=$('mediaPanel');if(!pendingFile){panel.hidden=true;$('mediaFile').textContent='';$('textInput').placeholder='输入消息';return}panel.hidden=false;$('mediaFile').textContent=`${pendingFile.name} · ${formatBytes(pendingFile.size)}`;$('textInput').placeholder='输入附件说明，可留空'}",
                "function rememberInputTarget(el){if(el&&((el.tagName==='TEXTAREA')||el.matches('[data-param]')))lastInputTarget=el}",
                "function insertTextAtCursor(text){let el=lastInputTarget;if(!el||el.disabled||el.hidden){el=$('textInput')}if(el.hidden){toast('先点一下要填写的输入框');return}const start=typeof el.selectionStart==='number'?el.selectionStart:el.value.length;const end=typeof el.selectionEnd==='number'?el.selectionEnd:start;el.value=el.value.slice(0,start)+text+el.value.slice(end);el.selectionStart=el.selectionEnd=start+text.length;el.focus();el.dispatchEvent(new Event('input',{bubbles:true}))}",
                "function setMode(mode){sendMode=mode;if(mode==='template')clearPendingMedia();$('modeText').classList.toggle('active',mode==='text');$('modeTemplate').classList.toggle('active',mode==='template');$('templatePanel').hidden=mode!=='template';$('textInput').hidden=mode==='template';updateTemplatePreview()}",
                "function templateLabel(t){return `${t.templateName||'(未命名模板)'} / ${t.languageCode||'default'} / ${t.templateCode||''}`}",
                "function renderTemplateSelect(){const s=$('templateSelect');if(!templates.length){s.innerHTML='<option value=\"\">未同步模板</option>'}else{s.innerHTML=templates.map((t,i)=>`<option value=\"${i}\">${escapeHtml(templateLabel(t))}</option>`).join('')}renderTemplateFields()}",
                "function selectedTemplate(){const i=Number($('templateSelect').value);return Number.isInteger(i)?templates[i]:null}",
                "function templatePlaceholders(t){let ps=Array.isArray(t?.placeholders)?t.placeholders.filter(Boolean):[];if(!ps.length&&t?.body){ps=[...t.body.matchAll(/\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*\\}\\}|\\$\\(\\s*([A-Za-z0-9_.-]+)\\s*\\)/g)].map(m=>m[1]||m[2]).filter(Boolean)}return [...new Set(ps)]}",
                "function renderTemplateFields(){const t=selectedTemplate();const fields=$('templateFields');if(!t){fields.innerHTML='<div class=\"hint\">先运行 sync-templates 同步模板</div>';$('templatePreview').textContent='';return}const ps=templatePlaceholders(t);fields.innerHTML=ps.length?ps.map(p=>`<label><span>${escapeHtml(p)}</span><input data-param=\"${escapeHtml(p)}\" placeholder=\"${escapeHtml(p)}\"></label>`).join(''):'<div class=\"hint\">这个模板没有参数</div>';fields.querySelectorAll('input').forEach(input=>input.addEventListener('input',updateTemplatePreview));updateTemplatePreview()}",
                "function collectTemplateParams(){const params={};document.querySelectorAll('[data-param]').forEach(input=>{params[input.dataset.param]=input.value.trim()});return params}",
                "function updateTemplatePreview(){if(sendMode!=='template')return;const t=selectedTemplate();if(!t){$('templatePreview').textContent='';return}const params=collectTemplateParams();$('templatePreview').textContent=String(t.body||'').replace(/\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*\\}\\}|\\$\\(\\s*([A-Za-z0-9_.-]+)\\s*\\)/g,(all,a,b)=>params[a||b]||all)}",
                "$('contacts').addEventListener('click',e=>{const item=e.target.closest('.contact');if(!item)return;active=item.dataset.peer;render({contactScroll:$('contacts').scrollTop,forceBottom:true})});",
                "$('messages').addEventListener('click',e=>{const item=e.target.closest('.msg');if(item)show(item.dataset.id)});",
                "$('modeText').addEventListener('click',()=>setMode('text'));$('modeTemplate').addEventListener('click',()=>setMode('template'));$('templateSelect').addEventListener('change',renderTemplateFields);",
                "document.addEventListener('focusin',e=>rememberInputTarget(e.target));",
                "$('emojiPanel').addEventListener('click',e=>{const tab=e.target.closest('[data-emoji-category]');if(tab){activeEmojiCategory=tab.dataset.emojiCategory;renderEmojiPanel();return}const item=e.target.closest('[data-emoji]');if(item){insertTextAtCursor(item.dataset.emoji)}});",
                "document.querySelectorAll('[data-tool]').forEach(btn=>btn.addEventListener('click',()=>{if(btn.dataset.tool==='emoji')toggleEmojiPanel()}));",
                "document.querySelectorAll('[data-media]').forEach(btn=>btn.addEventListener('click',()=>chooseMedia(btn.dataset.media)));",
                "$('fileInput').addEventListener('change',e=>{pendingFile=e.target.files&&e.target.files[0]?e.target.files[0]:null;if(pendingFile&&!pendingMediaType)pendingMediaType='document';updateMediaPanel()});$('clearMedia').addEventListener('click',clearPendingMedia);",
                "$('textInput').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.ctrlKey&&!e.shiftKey){e.preventDefault();$('form').requestSubmit()}else if(e.key==='Enter'&&e.ctrlKey){const el=e.target;const pos=el.selectionStart;el.value=el.value.slice(0,pos)+'\\n'+el.value.slice(el.selectionEnd);el.selectionStart=el.selectionEnd=pos+1;e.preventDefault()}});",
                "$('form').addEventListener('submit',e=>{if(sendInFlight){e.preventDefault();e.stopImmediatePropagation()}},true);",
                "$('form').addEventListener('submit',e=>{e.preventDefault();if(!active)return alert('请选择联系人');if(pendingFile){const mediaFd=new FormData();mediaFd.set('to',active);mediaFd.set('mediaType',pendingMediaType||'document');mediaFd.set('caption',$('textInput').value.trim());mediaFd.set('file',pendingFile,pendingFile.name);fetch('/api/send-media',{method:'POST',body:mediaFd}).then(async r=>{if(!r.ok)throw new Error(await r.text());$('textInput').value='';clearPendingMedia();load({forceBottom:true})}).catch(err=>alert(err.message));return}const fd=new URLSearchParams();fd.set('to',active);fd.set('mode',sendMode);if(sendMode==='template'){const t=selectedTemplate();if(!t)return alert('请先同步并选择模板');fd.set('templateCode',t.templateCode||'');fd.set('templateName',t.templateName||'');fd.set('language',t.languageCode||'');fd.set('templateParamsJson',JSON.stringify(collectTemplateParams()))}else{const text=$('textInput').value.trim();if(!text)return;fd.set('text',text)}fetch('/api/send',{method:'POST',body:fd}).then(async r=>{if(!r.ok)throw new Error(await r.text());if(sendMode==='text')$('textInput').value='';load({forceBottom:true})}).catch(err=>alert(err.message))});",
                "new EventSource('/events').onmessage=e=>{load({forceBottom:true});toast('收到新消息')};load({forceBottom:true});loadTemplates();setInterval(()=>load(),10000);",
                "</script></body></html>");
    }

    interface StringSetter {
        void set(String value);
    }

    static class Config {
        private final Map<String, String> values;

        Config(Map<String, String> values) {
            this.values = values;
        }

        static Config load(Path envPath) throws IOException {
            Map<String, String> loaded = new HashMap<>();
            if (Files.exists(envPath)) {
                for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                        continue;
                    }
                    String[] parts = trimmed.split("=", 2);
                    loaded.put(parts[0].trim(), parts[1].trim());
                }
            }
            System.getenv().forEach((key, value) -> {
                if (value != null && !value.isBlank()) {
                    loaded.put(key, value);
                }
            });
            return new Config(loaded);
        }

        String value(String key, String fallback) {
            String value = values.get(key);
            return value == null || value.isBlank() ? fallback : value;
        }

        String required(String key) {
            String value = value(key, "");
            if (value.isBlank()) {
                throw new IllegalArgumentException("Missing config: " + key);
            }
            return value;
        }

        Path dataFile() {
            return Path.of(value("DATA_FILE", "data/messages.jsonl"));
        }

        Path templateFile() {
            return Path.of(value("TEMPLATE_FILE", "data/templates.json"));
        }

        long mediaMaxBytes() {
            return Long.parseLong(value("MEDIA_MAX_BYTES", String.valueOf(DEFAULT_MEDIA_MAX_BYTES)));
        }
    }

    static class MultipartForm {
        final Map<String, String> fields = new HashMap<>();
        final Map<String, UploadedFile> files = new HashMap<>();

        String field(String name) {
            return fields.getOrDefault(name, "");
        }

        UploadedFile file(String name) {
            return files.get(name);
        }
    }

    static class UploadedFile {
        final String fileName;
        final String contentType;
        final byte[] bytes;

        UploadedFile(String fileName, String contentType, byte[] bytes) {
            this.fileName = fileName == null ? "" : fileName;
            this.contentType = contentType == null ? "" : contentType;
            this.bytes = bytes == null ? new byte[0] : bytes;
        }
    }

    static class PayloadTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        PayloadTooLargeException(String message) {
            super(message);
        }
    }

    static class IdempotencyConflictException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        IdempotencyConflictException(String message) {
            super(message);
        }
    }

    static class MediaUnavailableException extends IOException {
        private static final long serialVersionUID = 1L;

        MediaUnavailableException(String message) {
            super(message);
        }
    }

    static class IdempotentRequestExecutor {
        private final ConcurrentHashMap<String, RequestExecution> executions = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        <T> T execute(String requestId, Callable<T> action) throws Exception {
            return execute(requestId, "", action);
        }

        @SuppressWarnings("unchecked")
        <T> T execute(String requestId, String fingerprint, Callable<T> action) throws Exception {
            evictExpired();
            String key = requiredInline(requestId, "requestId");
            RequestExecution created = new RequestExecution(fingerprint);
            RequestExecution existing = executions.putIfAbsent(key, created);
            if (existing != null) {
                if (!Objects.equals(existing.fingerprint, fingerprint)) {
                    throw new IdempotencyConflictException("clientRequestId already belongs to a different message payload");
                }
                return (T) await(existing.result);
            }
            try {
                T result = action.call();
                created.result.complete(result);
                return result;
            } catch (Throwable ex) {
                created.result.completeExceptionally(ex);
                if (ex instanceof Exception) {
                    throw (Exception) ex;
                }
                throw ex;
            }
        }

        private void evictExpired() {
            long cutoff = System.currentTimeMillis() - IDEMPOTENCY_RETENTION_MILLIS;
            executions.entrySet().removeIf(entry -> entry.getValue().createdAt < cutoff);
            if (executions.size() <= MAX_IDEMPOTENCY_ENTRIES) {
                return;
            }
            executions.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(item -> item.createdAt)))
                    .limit(executions.size() - MAX_IDEMPOTENCY_ENTRIES)
                    .map(Map.Entry::getKey)
                    .forEach(executions::remove);
        }

        private static Object await(CompletableFuture<Object> future) throws Exception {
            try {
                return future.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw ex;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw new IllegalStateException(cause);
            }
        }

        static class RequestExecution {
            final long createdAt = System.currentTimeMillis();
            final String fingerprint;
            final CompletableFuture<Object> result = new CompletableFuture<>();

            RequestExecution(String fingerprint) {
                this.fingerprint = fingerprint == null ? "" : fingerprint;
            }
        }
    }

    static class StoredMessage {
        String id;
        String direction;
        String timestamp;
        String from;
        String to;
        String text;
        String status;
        String statusTimestamp;
        String mediaType;
        String mediaUrl;
        String objectKey;
        String mimeType;
        String fileName;
        String caption;
        String localMediaPath;
        String clientRequestId;
        String requestFingerprint;
        String raw;

        static StoredMessage inbound(String id, String from, String to, String text, String raw) {
            return create(id, "inbound", from, to, text, raw);
        }

        static StoredMessage outbound(String id, String from, String to, String text, String raw) {
            return create(id, "outbound", from, to, text, raw);
        }

        static StoredMessage create(String id, String direction, String from, String to, String text, String raw) {
            StoredMessage message = new StoredMessage();
            message.id = requiredInline(id, "id");
            message.direction = direction;
            message.timestamp = Instant.now().toString();
            message.from = from == null ? "" : from;
            message.to = to == null ? "" : to;
            message.text = displayText(text);
            message.raw = raw == null ? "" : raw;
            return message;
        }
    }

    static class MediaMetadata {
        String mediaType = "";
        String mediaUrl = "";
        String objectKey = "";
        String mimeType = "";
        String fileName = "";
        String caption = "";
    }

    static class MediaContent {
        final byte[] bytes;
        final String mimeType;

        MediaContent(byte[] bytes, String mimeType) {
            this.bytes = bytes == null ? new byte[0] : bytes;
            this.mimeType = mimeType == null ? "" : mimeType;
        }
    }

    static class TemplateRecord {
        String templateCode;
        String templateName;
        String languageCode;
        String body;
        List<String> placeholders = new ArrayList<>();
        String raw;
        String updatedAt;
    }

    static class TemplateStore {
        private final Path file;

        TemplateStore(Path file) {
            this.file = file;
        }

        static TemplateStore empty() {
            return new TemplateStore(null);
        }

        synchronized List<TemplateRecord> readAll() throws IOException {
            if (file == null || !Files.exists(file)) {
                return new ArrayList<>();
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return new ArrayList<>();
            }
            List<TemplateRecord> records = GSON.fromJson(json, TEMPLATE_RECORD_LIST);
            if (records == null) {
                return new ArrayList<>();
            }
            for (TemplateRecord record : records) {
                if ((record.placeholders == null || record.placeholders.isEmpty())
                        && record.body != null && !record.body.isBlank()) {
                    record.placeholders = templatePlaceholders(record.body);
                }
            }
            return records;
        }

        synchronized void saveAll(List<TemplateRecord> records) throws IOException {
            if (file == null) {
                return;
            }
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            records.sort(Comparator
                    .comparing((TemplateRecord item) -> firstNonBlank(item.templateName, ""))
                    .thenComparing(item -> firstNonBlank(item.languageCode, ""))
                    .thenComparing(item -> firstNonBlank(item.templateCode, "")));
            Files.writeString(file, PRETTY_GSON.toJson(records), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        }

        synchronized TemplateRecord find(String templateCode, String languageCode) {
            if (templateCode == null || templateCode.isBlank()) {
                return null;
            }
            try {
                String normalizedCode = normalizeKey(templateCode);
                String normalizedLanguage = normalizeKey(languageCode);
                TemplateRecord codeOnly = null;
                for (TemplateRecord record : readAll()) {
                    if (!normalizedCode.equals(normalizeKey(record.templateCode))) {
                        continue;
                    }
                    if (normalizedLanguage.isBlank()
                            || normalizedLanguage.equals(normalizeKey(record.languageCode))
                            || normalizeKey(record.languageCode).isBlank()) {
                        return record;
                    }
                    if (codeOnly == null) {
                        codeOnly = record;
                    }
                }
                return codeOnly;
            } catch (IOException ex) {
                return null;
            }
        }

        String key(TemplateRecord record) {
            return normalizeKey(record.templateCode) + "|" + normalizeKey(record.languageCode);
        }

        private static String normalizeKey(String value) {
            return value == null ? "" : value.trim().toLowerCase();
        }
    }

    static class MessageStore {
        private final Path file;
        private final TemplateStore templateStore;

        MessageStore(Path file) {
            this(file, TemplateStore.empty());
        }

        MessageStore(Path file, TemplateStore templateStore) {
            this.file = file;
            this.templateStore = templateStore == null ? TemplateStore.empty() : templateStore;
        }

        synchronized StoredMessage append(StoredMessage message) throws IOException {
            Files.createDirectories(file.getParent());
            if ("status".equals(message.direction)) {
                List<StoredMessage> raw = readRawAll();
                boolean hasTarget = raw.stream().anyMatch(item -> Objects.equals(item.id, statusTargetId(message.id)));
                if (!hasTarget && !raw.stream().anyMatch(item -> Objects.equals(item.id, message.id))) {
                    Files.writeString(file, GSON.toJson(message) + System.lineSeparator(), StandardCharsets.UTF_8,
                            Files.exists(file) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
                    return message;
                }
                List<StoredMessage> merged = mergeMessages(raw);
                applyStatus(merged, message);
                rewrite(merged);
                return merged.stream()
                        .filter(item -> Objects.equals(item.id, statusTargetId(message.id)))
                        .findFirst()
                        .orElse(message);
            }
            normalizeMessage(message);
            if (!contains(message)) {
                Files.writeString(file, GSON.toJson(message) + System.lineSeparator(), StandardCharsets.UTF_8,
                        Files.exists(file) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
            }
            return message;
        }

        synchronized List<StoredMessage> readAll() throws IOException {
            return mergeMessages(readRawAll());
        }

        synchronized StoredMessage findByClientRequestId(String clientRequestId) throws IOException {
            if (clientRequestId == null || clientRequestId.isBlank()) {
                return null;
            }
            return readAll().stream()
                    .filter(item -> Objects.equals(clientRequestId, item.clientRequestId))
                    .findFirst()
                    .orElse(null);
        }

        synchronized StoredMessage findMediaById(String id) throws IOException {
            if (id == null || id.isBlank()) {
                return null;
            }
            return readAll().stream()
                    .filter(item -> Objects.equals(id, item.id))
                    .filter(item -> (item.objectKey != null && !item.objectKey.isBlank())
                            || (item.mediaUrl != null && !item.mediaUrl.isBlank())
                            || (item.localMediaPath != null && !item.localMediaPath.isBlank()))
                    .findFirst()
                    .orElse(null);
        }

        synchronized String saveMedia(byte[] bytes, String fileName) throws IOException {
            Path mediaDir = file.getParent().resolve("media").toAbsolutePath().normalize();
            Files.createDirectories(mediaDir);
            String extension = fileExtension(fileName);
            String storedName = UUID.randomUUID().toString().replace("-", "") + extension;
            Path target = mediaDir.resolve(storedName).normalize();
            if (!target.startsWith(mediaDir)) {
                throw new IOException("invalid media target path");
            }
            Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
            return storedName;
        }

        Path resolveLocalMedia(StoredMessage message) throws IOException {
            if (message == null || message.localMediaPath == null || message.localMediaPath.isBlank()) {
                return null;
            }
            Path mediaDir = file.getParent().resolve("media").toAbsolutePath().normalize();
            Path target = mediaDir.resolve(message.localMediaPath).normalize();
            if (!target.startsWith(mediaDir)) {
                throw new IOException("invalid stored media path");
            }
            return Files.isRegularFile(target) ? target : null;
        }

        synchronized boolean contains(StoredMessage message) throws IOException {
            return readAll().stream().anyMatch(item -> sameMessage(item, message));
        }

        private List<StoredMessage> readRawAll() throws IOException {
            List<StoredMessage> result = new ArrayList<>();
            if (!Files.exists(file)) {
                return result;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    StoredMessage message = GSON.fromJson(line, StoredMessage.class);
                    normalizeMessage(message);
                    result.add(message);
                }
            }
            return result;
        }

        private void normalizeMessage(StoredMessage message) {
            message.text = displayText(message.text);
            enrichFromRaw(message);
            String rawText = historyDisplayTextFromRaw(message.raw, templateStore);
            if (!rawText.isBlank() && !"status".equals(message.direction)) {
                message.text = rawText;
            }
        }

        private List<StoredMessage> mergeMessages(List<StoredMessage> raw) {
            Map<String, StoredMessage> byId = new LinkedHashMap<>();
            Map<String, StoredMessage> pendingStatus = new HashMap<>();
            for (StoredMessage message : raw) {
                if ("status".equals(message.direction)) {
                    StoredMessage target = byId.get(statusTargetId(message.id));
                    if (target != null) {
                        target.status = displayStatus(message.text);
                        target.statusTimestamp = message.timestamp;
                    } else {
                        pendingStatus.put(statusTargetId(message.id), message);
                    }
                    continue;
                }
                StoredMessage existing = byId.get(message.id);
                if (existing == null) {
                    byId.put(message.id, message);
                    StoredMessage status = pendingStatus.get(message.id);
                    if (status != null) {
                        message.status = displayStatus(status.text);
                        message.statusTimestamp = status.timestamp;
                    }
                    continue;
                }
                mergeMessage(existing, message);
            }

            List<StoredMessage> result = new ArrayList<>();
            for (StoredMessage message : byId.values()) {
                boolean duplicate = result.stream().anyMatch(item -> sameMessage(item, message));
                if (!duplicate) {
                    result.add(message);
                }
            }
            result.sort(Comparator.comparing(item -> item.timestamp));
            return result;
        }

        private void applyStatus(List<StoredMessage> messages, StoredMessage statusMessage) {
            String targetId = statusTargetId(statusMessage.id);
            for (StoredMessage message : messages) {
                if (Objects.equals(message.id, targetId)) {
                    message.status = displayStatus(statusMessage.text);
                    message.statusTimestamp = statusMessage.timestamp;
                    return;
                }
            }
        }

        private void mergeMessage(StoredMessage target, StoredMessage source) {
            if ((target.text == null || target.text.isBlank()) && source.text != null) {
                target.text = source.text;
            }
            if ((target.raw == null || target.raw.isBlank()) && source.raw != null) {
                target.raw = source.raw;
            }
            if (target.status == null || target.status.isBlank()) {
                target.status = source.status;
                target.statusTimestamp = source.statusTimestamp;
            }
        }

        private boolean sameMessage(StoredMessage left, StoredMessage right) {
            if (Objects.equals(left.id, right.id)) {
                return true;
            }
            return Objects.equals(left.direction, right.direction)
                    && Objects.equals(left.from, right.from)
                    && Objects.equals(left.to, right.to)
                    && Objects.equals(displayText(left.text), displayText(right.text))
                    && Objects.equals(timestampSecond(left.timestamp), timestampSecond(right.timestamp));
        }

        private String timestampSecond(String timestamp) {
            if (timestamp == null || timestamp.length() < 19) {
                return timestamp == null ? "" : timestamp;
            }
            return timestamp.substring(0, 19);
        }

        private void rewrite(List<StoredMessage> messages) throws IOException {
            Files.createDirectories(file.getParent());
            StringBuilder output = new StringBuilder();
            for (StoredMessage message : mergeMessages(messages)) {
                output.append(GSON.toJson(message)).append(System.lineSeparator());
            }
            Files.writeString(file, output.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private static void enrichFromRaw(StoredMessage message) {
        if (message == null || message.raw == null || message.raw.isBlank()) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(message.raw);
            String rawMessageId = WebhookParser.first(root, "messageId", "MessageId");
            String status = firstNonBlank(
                    WebhookParser.first(root, "clientReadStatusName", "ClientReadStatusName"),
                    WebhookParser.first(root, "clientAcceptStatusName", "ClientAcceptStatusName"),
                    WebhookParser.first(root, "messageStatusName", "MessageStatusName"),
                    WebhookParser.first(root, "Status", "status")
            );
            String failReason = WebhookParser.first(root, "failReason", "FailReason", "ErrorDescription");
            if (!failReason.isBlank()) {
                status = status.isBlank() ? failReason : status + " - " + failReason;
            }
            if (!rawMessageId.isBlank()) {
                if ("status".equals(message.direction)) {
                    message.id = rawMessageId + "-status";
                } else {
                    message.id = rawMessageId;
                }
            }
            if (!status.isBlank() && !"status".equals(message.direction)) {
                message.status = displayStatus(status);
                message.statusTimestamp = firstNonBlank(message.statusTimestamp, message.timestamp);
            }
            MediaMetadata media = mediaMetadata(root);
            if (!media.mediaType.isBlank() && !"status".equals(message.direction)) {
                message.mediaType = firstNonBlank(message.mediaType, media.mediaType);
                message.mediaUrl = firstNonBlank(message.mediaUrl, media.mediaUrl);
                message.objectKey = firstNonBlank(message.objectKey, media.objectKey);
                message.mimeType = firstNonBlank(message.mimeType, media.mimeType);
                message.fileName = firstNonBlank(message.fileName, media.fileName);
                message.caption = firstNonBlank(message.caption, media.caption);
                if (message.text == null || message.text.isBlank()) {
                    message.text = mediaDisplayText(message.mediaType, message.caption, message.fileName);
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    static class SyncResult {
        int fetched;
        int saved;
        int skipped;
        int templatesFetched;
        int templatesSaved;
        int templatesSkipped;
    }

    static class TemplateSyncResult {
        int fetched;
        int saved;
        int skipped;
    }

    static class WebhookParser {
        static StoredMessage parse(String raw) {
            try {
                JsonElement root = JsonParser.parseString(raw);
                String id = first(root, "MessageId", "messageId", "message_id", "wamid", "id", "TaskId");
                String from = first(root, "From", "from", "sender", "wa_id", "phoneNumber");
                String to = first(root, "To", "to", "recipient", "businessPhoneNumber");
                String text = first(root, "Message", "message", "text", "content", "body", "ErrorDescription");
                String noticeType = first(root, "NoticeType", "noticeType");
                String status = first(root, "Status", "status");
                if (id.isBlank()) {
                    id = "webhook-" + UUID.randomUUID();
                }
                if (!status.isBlank() && noticeType.isBlank()) {
                    String statusText = "状态: " + status + (text.isBlank() ? "" : " - " + text);
                    return StoredMessage.create(id + "-status", "status", from, to, statusText, raw);
                }
                return StoredMessage.inbound(id, from, to, text, raw);
            } catch (RuntimeException ex) {
                return StoredMessage.inbound("webhook-" + UUID.randomUUID(), "", "", raw, raw);
            }
        }

        private static String first(JsonElement root, String... keys) {
            for (String key : keys) {
                String value = find(root, key);
                if (!value.isBlank()) {
                    return value;
                }
            }
            return "";
        }

        private static String find(JsonElement element, String key) {
            if (element == null || element.isJsonNull()) {
                return "";
            }
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                if (object.has(key) && object.get(key).isJsonPrimitive()) {
                    return object.get(key).getAsString();
                }
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(key) && entry.getValue().isJsonPrimitive()) {
                        return entry.getValue().getAsString();
                    }
                    String value = find(entry.getValue(), key);
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) {
                    String value = find(child, key);
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
            return "";
        }
    }

    static class EventHub {
        private final List<HttpExchange> clients = new CopyOnWriteArrayList<>();

        void connect(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream; charset=utf-8");
            headers.set("Cache-Control", "no-cache");
            headers.set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);
            clients.add(exchange);
            exchange.getResponseBody().write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
        }

        void publish(StoredMessage message) {
            String payload = "data: " + GSON.toJson(message) + "\n\n";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            for (HttpExchange client : clients) {
                try {
                    client.getResponseBody().write(bytes);
                    client.getResponseBody().flush();
                } catch (IOException ex) {
                    clients.remove(client);
                    client.close();
                }
            }
        }
    }
}
