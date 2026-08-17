package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.service.wecom.WeComAccessTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComChatDataGateway {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_ERRMSG_UTF8_BYTES = 4_096;
    private static final String SYNC_PROGRAM_PATH = "/cgi-bin/chatdata/sync_call_program";
    private static final Pattern HINT_PATTERN = Pattern.compile("hint: \\[([A-Za-z0-9_-]{1,128})\\]");
    private static final Set<String> OUTER_FIELDS = Set.of("errcode", "errmsg", "response_data");
    private static final Set<String> PAGE_FIELDS = Set.of(
            "errcode", "errmsg", "has_more", "next_cursor", "msg_list");
    private static final Set<String> MESSAGE_FIELDS = Set.of(
            "msgid", "sender", "receiver_list", "chatid", "send_time", "msgtype", "service_encrypt_info");
    private static final Set<String> PARTY_FIELDS = Set.of("type", "id");
    private static final Set<String> ENCRYPTION_FIELDS = Set.of("encrypted_secret_key", "public_key_ver");
    private final AppConfig config;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final AccessTokenProvider accessTokens;

    @Autowired
    public WeComChatDataGateway(AppConfig config, ObjectMapper objectMapper,
                                WeComAccessTokenService accessTokens) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.accessTokens = accessTokens::accessToken;
        this.restClient = RestClient.builder()
                .baseUrl(config.wecomApiBaseUrl())
                .build();
    }

    WeComChatDataGateway(AppConfig config, ObjectMapper objectMapper, RestClient restClient,
                         AccessTokenProvider accessTokens) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
        this.accessTokens = accessTokens;
    }

    public ProgramPage sync(ResolvedInstallation installation, String cursor, int limit,
                            Duration timeout) throws WeComChatDataException {
        requireText(config.wecomChatDataProgramId(), "program", 128);
        requireText(config.wecomChatDataAbilityId(), "ability", 128);
        requireOptional(cursor, "cursor", 128);
        if (limit < 1 || limit > 200) throw programError(null);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw timeout(null);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            ObjectNode input = objectMapper.createObjectNode();
            input.put("cursor", cursor == null ? "" : cursor);
            input.put("limit", limit);
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("program_id", config.wecomChatDataProgramId());
            requestBody.put("ability_id", config.wecomChatDataAbilityId());
            requestBody.put("request_data", objectMapper.writeValueAsString(input));
            String token = accessTokens.accessToken(installation, remaining(deadline));
            String path = SYNC_PROGRAM_PATH + "?access_token="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8);
            String json = objectMapper.writeValueAsString(requestBody);
            int httpStatus;
            String response;
            try {
                ResponseEntity<String> entity = restClient.post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json)
                        .retrieve()
                        .toEntity(String.class);
                httpStatus = entity.getStatusCode().value();
                response = entity.getBody();
            } catch (HttpStatusCodeException e) {
                httpStatus = e.getStatusCode().value();
                response = e.getResponseBodyAsString();
            }
            if (httpStatus < 200 || httpStatus >= 300 || response == null
                    || response.length() > MAX_RESPONSE_BYTES) {
                throw programError(null, SYNC_PROGRAM_PATH, httpStatus, null);
            }
            try {
                JsonNode outer = objectMapper.readTree(response);
                requireOnlyFields(outer, OUTER_FIELDS);
                int outerErrcode = integer(outer, "errcode");
                if (outerErrcode != 0) {
                    String errmsg = boundedErrmsg(outer, "errmsg");
                    emitDiagnostic(outerErrcode, httpStatus, errmsg, SYNC_PROGRAM_PATH);
                    throw programError(outerErrcode, SYNC_PROGRAM_PATH, httpStatus,
                            extractHint(errmsg), null);
                }
                String responseData = text(outer, "response_data", MAX_RESPONSE_BYTES);
                return parseProgramPage(responseData, limit, httpStatus);
            } catch (WeComChatDataException exception) {
                if (exception.upstreamPath() != null) throw exception;
                throw programError(exception.upstreamErrcode(), SYNC_PROGRAM_PATH,
                        httpStatus, exception.upstreamHint(), exception);
            } catch (Exception exception) {
                throw programError(null, SYNC_PROGRAM_PATH, httpStatus, exception);
            }
        } catch (WeComChatDataException exception) {
            throw exception;
        } catch (WeComException exception) {
            throw programError(exception.upstreamErrcode(), exception.upstreamPath(),
                    exception.upstreamHttpStatus(), exception.upstreamHint(), exception);
        } catch (Exception exception) {
            throw programError(exception);
        }
    }

    private ProgramPage parseProgramPage(String raw, int limit, int upstreamHttpStatus)
            throws WeComChatDataException {
        try {
            JsonNode body = objectMapper.readTree(raw);
            requireOnlyFields(body, PAGE_FIELDS);
            int programErrcode = integer(body, "errcode");
            if (programErrcode != 0) {
                String errmsg = boundedErrmsg(body, "errmsg");
                emitDiagnostic(programErrcode, upstreamHttpStatus, errmsg, SYNC_PROGRAM_PATH);
                throw programError(programErrcode, SYNC_PROGRAM_PATH, upstreamHttpStatus,
                        extractHint(errmsg), null);
            }
            int hasMoreValue = integer(body, "has_more");
            if (hasMoreValue != 0 && hasMoreValue != 1) throw programError(null);
            String nextCursor = optionalText(body, "next_cursor", 128);
            if (hasMoreValue == 1 && nextCursor.isBlank()) throw programError(null);
            ArrayNode list = body.has("msg_list") && body.get("msg_list").isArray()
                    ? (ArrayNode) body.get("msg_list") : objectMapper.createArrayNode();
            if (list.size() > limit || list.size() > 200) throw programError(null);
            List<EncryptedMessage> messages = new ArrayList<>(list.size());
            for (JsonNode element : list) {
                requireOnlyFields(element, MESSAGE_FIELDS);
                JsonNode sender = object(element, "sender");
                requireOnlyFields(sender, PARTY_FIELDS);
                ArrayNode receivers = array(element, "receiver_list", 100);
                List<Party> parties = new ArrayList<>(receivers.size());
                for (JsonNode receiver : receivers) {
                    requireOnlyFields(receiver, PARTY_FIELDS);
                    parties.add(new Party(integer(receiver, "type"), text(receiver, "id", 128)));
                }
                JsonNode encryption = object(element, "service_encrypt_info");
                requireOnlyFields(encryption, ENCRYPTION_FIELDS);
                messages.add(new EncryptedMessage(
                        text(element, "msgid", 256),
                        new Party(integer(sender, "type"), text(sender, "id", 128)),
                        List.copyOf(parties), optionalText(element, "chatid", 256),
                        longValue(element, "send_time"), integer(element, "msgtype"),
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

    private static JsonNode object(JsonNode node, String field) throws WeComChatDataException {
        if (!node.has(field) || !node.get(field).isObject()) throw programError(null);
        return node.get(field);
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowed)
            throws WeComChatDataException {
        if (node == null || !node.isObject()) throw programError(null);
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!allowed.containsAll(actual)) throw programError(null);
    }

    private static ArrayNode array(JsonNode node, String field, int maximum) throws WeComChatDataException {
        if (!node.has(field) || !node.get(field).isArray()) throw programError(null);
        ArrayNode result = (ArrayNode) node.get(field);
        if (result.size() > maximum) throw programError(null);
        return result;
    }

    private static int integer(JsonNode node, String field) throws WeComChatDataException {
        if (!node.has(field) || !node.get(field).isNumber()) throw programError(null);
        return node.get(field).asInt();
    }

    private static long longValue(JsonNode node, String field) throws WeComChatDataException {
        if (!node.has(field) || !node.get(field).isNumber()) throw programError(null);
        long value = node.get(field).asLong();
        if (value < 0) throw programError(null);
        return value;
    }

    private static String text(JsonNode node, String field, int maximum) throws WeComChatDataException {
        String value = optionalText(node, field, maximum);
        if (value.isBlank()) throw programError(null);
        return value;
    }

    private static String optionalText(JsonNode node, String field, int maximum)
            throws WeComChatDataException {
        if (!node.has(field)) return "";
        if (!node.get(field).isTextual()) throw programError(null);
        String value = node.get(field).asText();
        if (value.length() > maximum) throw programError(null);
        return value;
    }

    private String boundedErrmsg(JsonNode node, String field) {
        if (!node.has(field) || !node.get(field).isTextual()) return null;
        String value = node.get(field).asText();
        return value.getBytes(StandardCharsets.UTF_8).length <= MAX_ERRMSG_UTF8_BYTES ? value : null;
    }

    private static String extractHint(String errmsg) {
        if (errmsg == null) return null;
        Matcher matcher = HINT_PATTERN.matcher(errmsg);
        return matcher.find() ? matcher.group(1) : null;
    }

    private void emitDiagnostic(int errcode, int httpStatus, String errmsg, String path) {
        if (errmsg == null) return;
        try {
            if (!config.wecomChatDataDiagnostics()) return;
            ObjectNode event = objectMapper.createObjectNode();
            event.put("event", "wecom.chatdata.upstream_diagnostic");
            event.put("path", path);
            event.put("httpStatus", httpStatus);
            event.put("errcode", errcode);
            event.put("errmsg", errmsg);
            System.err.println(objectMapper.writeValueAsString(event));
        } catch (Exception ignored) {
            // Diagnostics must never replace the primary upstream error.
        }
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

    private static WeComChatDataException programError(Integer upstreamErrcode, String upstreamPath,
                                                        Integer upstreamHttpStatus, Throwable cause) {
        return programError(upstreamErrcode, upstreamPath, upstreamHttpStatus, null, cause);
    }

    private static WeComChatDataException programError(Integer upstreamErrcode, String upstreamPath,
                                                        Integer upstreamHttpStatus, String upstreamHint,
                                                        Throwable cause) {
        return new WeComChatDataException("WECOM_CHATDATA_PROGRAM_ERROR", 502,
                "企业微信专区程序调用失败", upstreamErrcode, upstreamPath,
                upstreamHttpStatus, upstreamHint, cause);
    }

    @FunctionalInterface
    interface AccessTokenProvider {
        String accessToken(ResolvedInstallation installation, Duration timeout);
    }

    public record ProgramPage(boolean hasMore, String nextCursor, List<EncryptedMessage> messages) {}
    public record EncryptedMessage(String msgid, Party sender, List<Party> receivers, String chatId,
                                   long sendTime, int msgType, String encryptedSecretKey,
                                   int publicKeyVersion) {}
    public record Party(int type, String id) {}
}
