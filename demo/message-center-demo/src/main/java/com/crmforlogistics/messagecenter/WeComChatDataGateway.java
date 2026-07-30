package com.crmforlogistics.messagecenter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class WeComChatDataGateway {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final Set<String> OUTER_FIELDS = Set.of("errcode", "errmsg", "response_data");
    private static final Set<String> PAGE_FIELDS = Set.of(
            "errcode", "errmsg", "has_more", "next_cursor", "msg_list");
    private static final Set<String> MESSAGE_FIELDS = Set.of(
            "msgid", "sender", "receiver_list", "chatid", "send_time", "msgtype", "service_encrypt_info");
    private static final Set<String> PARTY_FIELDS = Set.of("type", "id");
    private static final Set<String> ENCRYPTION_FIELDS = Set.of("encrypted_secret_key", "public_key_ver");
    private final Config config;
    private final HttpClient client;
    private final URI apiBase;
    private final AccessTokenProvider accessTokens;

    public WeComChatDataGateway(Config config, WeComAccessTokenService accessTokens) {
        this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                URI.create(config.value("WECOM_API_BASE_URL", "https://qyapi.weixin.qq.com")),
                accessTokens::accessToken);
    }

    private WeComChatDataGateway(Config config, HttpClient client, URI apiBase,
                                 AccessTokenProvider accessTokens) {
        this.config = config;
        this.client = client;
        this.apiBase = apiBase;
        this.accessTokens = accessTokens;
    }

    static WeComChatDataGateway forTests(Config config, HttpClient client, URI apiBase,
                                         AccessTokenProvider accessTokens) {
        return new WeComChatDataGateway(config, client, apiBase, accessTokens);
    }

    public ProgramPage sync(WeComAuthorizationStore.ResolvedInstallation installation,
                            String cursor, int limit, Duration timeout) throws WeComChatDataException {
        requireText(config.wecomChatDataProgramId(), "program", 128);
        requireText(config.wecomChatDataAbilityId(), "ability", 128);
        requireOptional(cursor, "cursor", 128);
        if (limit < 1 || limit > 200) throw programError(null);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw timeout(null);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            JsonObject input = new JsonObject();
            if (cursor != null && !cursor.isBlank()) input.addProperty("cursor", cursor);
            input.addProperty("limit", limit);
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("program_id", config.wecomChatDataProgramId());
            requestBody.addProperty("ability_id", config.wecomChatDataAbilityId());
            requestBody.addProperty("request_data", input.toString());
            String token = accessTokens.accessToken(installation, remaining(deadline));
            Duration requestTimeout = remaining(deadline);
            URI uri = apiBase.resolve("/cgi-bin/chatdata/sync_call_program?access_token="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (response.statusCode() < 200 || response.statusCode() >= 300
                        || bytes.length > MAX_RESPONSE_BYTES) throw programError(null);
                JsonObject outer = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                requireOnlyFields(outer, OUTER_FIELDS);
                if (integer(outer, "errcode") != 0) throw programError(null);
                String responseData = text(outer, "response_data", MAX_RESPONSE_BYTES);
                return parseProgramPage(responseData, limit);
            }
        } catch (WeComChatDataException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw timeout(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw timeout(exception);
        } catch (Exception exception) {
            throw programError(exception);
        }
    }

    private ProgramPage parseProgramPage(String raw, int limit) throws WeComChatDataException {
        try {
            JsonObject body = JsonParser.parseString(raw).getAsJsonObject();
            requireOnlyFields(body, PAGE_FIELDS);
            if (integer(body, "errcode") != 0) throw programError(null);
            int hasMoreValue = integer(body, "has_more");
            if (hasMoreValue != 0 && hasMoreValue != 1) throw programError(null);
            String nextCursor = optionalText(body, "next_cursor", 128);
            if (hasMoreValue == 1 && nextCursor.isBlank()) throw programError(null);
            JsonArray list = body.has("msg_list") && body.get("msg_list").isJsonArray()
                    ? body.getAsJsonArray("msg_list") : new JsonArray();
            if (list.size() > limit || list.size() > 200) throw programError(null);
            List<EncryptedMessage> messages = new ArrayList<>(list.size());
            for (JsonElement element : list) {
                JsonObject message = element.getAsJsonObject();
                requireOnlyFields(message, MESSAGE_FIELDS);
                JsonObject sender = object(message, "sender");
                requireOnlyFields(sender, PARTY_FIELDS);
                JsonArray receivers = array(message, "receiver_list", 100);
                List<Party> parties = new ArrayList<>(receivers.size());
                for (JsonElement receiver : receivers) {
                    JsonObject party = receiver.getAsJsonObject();
                    requireOnlyFields(party, PARTY_FIELDS);
                    parties.add(new Party(integer(party, "type"), text(party, "id", 128)));
                }
                JsonObject encryption = object(message, "service_encrypt_info");
                requireOnlyFields(encryption, ENCRYPTION_FIELDS);
                messages.add(new EncryptedMessage(
                        text(message, "msgid", 256),
                        new Party(integer(sender, "type"), text(sender, "id", 128)),
                        List.copyOf(parties), optionalText(message, "chatid", 256),
                        longValue(message, "send_time"), integer(message, "msgtype"),
                        text(encryption, "encrypted_secret_key", 4096),
                        integer(encryption, "public_key_ver")));
            }
            return new ProgramPage(hasMoreValue == 1, nextCursor, List.copyOf(messages));
        } catch (WeComChatDataException exception) {
            throw exception;
        } catch (Exception exception) {
            throw programError(exception);
        }
    }

    private static JsonObject object(JsonObject object, String field) throws WeComChatDataException {
        if (!object.has(field) || !object.get(field).isJsonObject()) throw programError(null);
        return object.getAsJsonObject(field);
    }

    private static void requireOnlyFields(JsonObject object, Set<String> allowed)
            throws WeComChatDataException {
        if (!allowed.containsAll(object.keySet())) throw programError(null);
    }

    private static JsonArray array(JsonObject object, String field, int maximum) throws WeComChatDataException {
        if (!object.has(field) || !object.get(field).isJsonArray()) throw programError(null);
        JsonArray result = object.getAsJsonArray(field);
        if (result.size() > maximum) throw programError(null);
        return result;
    }

    private static int integer(JsonObject object, String field) throws WeComChatDataException {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.getAsJsonPrimitive(field).isNumber()) throw programError(null);
        return object.get(field).getAsInt();
    }

    private static long longValue(JsonObject object, String field) throws WeComChatDataException {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.getAsJsonPrimitive(field).isNumber()) throw programError(null);
        long value = object.get(field).getAsLong();
        if (value < 0) throw programError(null);
        return value;
    }

    private static String text(JsonObject object, String field, int maximum) throws WeComChatDataException {
        String value = optionalText(object, field, maximum);
        if (value.isBlank()) throw programError(null);
        return value;
    }

    private static String optionalText(JsonObject object, String field, int maximum)
            throws WeComChatDataException {
        if (!object.has(field)) return "";
        if (!object.get(field).isJsonPrimitive() || !object.getAsJsonPrimitive(field).isString()) {
            throw programError(null);
        }
        String value = object.get(field).getAsString();
        if (value.length() > maximum) throw programError(null);
        return value;
    }

    private static void requireText(String value, String field, int maximum) throws WeComChatDataException {
        if (value == null || value.isBlank() || value.length() > maximum) throw notConfigured();
    }

    private static void requireOptional(String value, String field, int maximum) throws WeComChatDataException {
        if (value != null && value.length() > maximum) throw programError(null);
    }

    private static WeComChatDataException notConfigured() {
        return new WeComChatDataException("WECOM_CHATDATA_NOT_CONFIGURED", 503,
                "企业微信会话同步尚未配置");
    }

    private static WeComChatDataException timeout(Throwable cause) {
        return new WeComChatDataException("WECOM_CHATDATA_TIMEOUT", 504,
                "企业微信会话同步超时", cause);
    }

    private static Duration remaining(long deadline) throws WeComChatDataException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw timeout(null);
        return Duration.ofNanos(remaining);
    }

    private static WeComChatDataException programError(Throwable cause) {
        return new WeComChatDataException("WECOM_CHATDATA_PROGRAM_ERROR", 502,
                "企业微信专区程序调用失败", cause);
    }

    interface AccessTokenProvider {
        String accessToken(WeComAuthorizationStore.ResolvedInstallation installation, Duration timeout)
                throws Exception;
    }

    public record ProgramPage(boolean hasMore, String nextCursor, List<EncryptedMessage> messages) {}
    public record EncryptedMessage(String msgid, Party sender, List<Party> receivers, String chatId,
                                   long sendTime, int msgType, String encryptedSecretKey,
                                   int publicKeyVersion) {}
    public record Party(int type, String id) {}
}
