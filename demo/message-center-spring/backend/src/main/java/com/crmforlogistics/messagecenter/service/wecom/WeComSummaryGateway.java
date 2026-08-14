package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComSummaryException;
import com.crmforlogistics.messagecenter.config.AppConfig;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComSummaryGateway {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_SUMMARY_BYTES = 65_536;
    private static final int MAX_MESSAGES = 1_000;
    private static final String PROGRAM_PATH = "/cgi-bin/chatdata/sync_call_program";
    private static final Set<String> OUTER_FIELDS = Set.of("errcode", "errmsg", "response_data");
    private static final Set<String> RESULT_FIELDS = Set.of("errcode", "errmsg", "status", "jobid", "summary");

    private final AppConfig config;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final AccessTokenProvider accessTokens;

    @Autowired
    public WeComSummaryGateway(AppConfig config, ObjectMapper objectMapper,
                               WeComAccessTokenService accessTokens) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.accessTokens = accessTokens::accessToken;
        this.restClient = RestClient.builder()
                .baseUrl(config.wecomApiBaseUrl())
                .build();
    }

    WeComSummaryGateway(AppConfig config, ObjectMapper objectMapper, RestClient restClient,
                        AccessTokenProvider accessTokens) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
        this.accessTokens = accessTokens;
    }

    public SubmitResult submit(ResolvedInstallation installation,
                               List<MessageReference> messages, Duration timeout)
            throws WeComSummaryException {
        validateMessages(messages);
        ObjectNode input = objectMapper.createObjectNode();
        input.put("operation", "submit");
        input.put("jobid", "");
        ArrayNode messageList = input.putArray("msg_list");
        for (MessageReference message : messages) {
            ObjectNode reference = messageList.addObject();
            reference.put("msgid", message.msgid());
            reference.put("secret_key", message.secretKey());
        }
        ProgramResult result = call(installation, input, timeout);
        return new SubmitResult(result.errcode(), result.status(), result.jobId());
    }

    public PollResult poll(ResolvedInstallation installation, String jobId, Duration timeout)
            throws WeComSummaryException {
        requireText(jobId, 256);
        ObjectNode input = objectMapper.createObjectNode();
        input.put("operation", "poll");
        input.put("jobid", jobId);
        input.putArray("msg_list");
        ProgramResult result = call(installation, input, timeout);
        return new PollResult(result.errcode(), result.status(), result.jobId(), result.summary());
    }

    private ProgramResult call(ResolvedInstallation installation, ObjectNode input, Duration timeout)
            throws WeComSummaryException {
        requireText(config.wecomChatDataProgramId(), 128);
        String abilityId = config.wecomDailySummaryAbilityId();
        requireText(abilityId, 128);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw timeout(null);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("program_id", config.wecomChatDataProgramId());
            requestBody.put("ability_id", abilityId);
            requestBody.put("request_data", objectMapper.writeValueAsString(input));
            String token = accessTokens.accessToken(installation, remaining(deadline));
            String path = PROGRAM_PATH + "?access_token="
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
                throw programError(null);
            }
            JsonNode outer = objectMapper.readTree(response);
            requireOnlyFields(outer, OUTER_FIELDS);
            if (integer(outer, "errcode") != 0) throw programError(null);
            return parseProgramResult(text(outer, "response_data", MAX_RESPONSE_BYTES));
        } catch (WeComSummaryException exception) {
            throw exception;
        } catch (Exception exception) {
            throw programError(exception);
        }
    }

    private ProgramResult parseProgramResult(String raw) throws WeComSummaryException {
        try {
            JsonNode body = objectMapper.readTree(raw);
            requireOnlyFields(body, RESULT_FIELDS);
            int errcode = integer(body, "errcode");
            int status = integer(body, "status");
            if (status < 0 || status > 2) throw programError(null);
            String jobId = optionalText(body, "jobid", 256);
            String summary = optionalText(body, "summary", MAX_SUMMARY_BYTES);
            if (summary.getBytes(StandardCharsets.UTF_8).length > MAX_SUMMARY_BYTES) {
                throw programError(null);
            }
            if (errcode == 0 && jobId.isBlank()) throw programError(null);
            if (errcode == 0 && status == 1 && summary.isBlank()) throw programError(null);
            if (status != 1 && !summary.isBlank()) throw programError(null);
            return new ProgramResult(errcode, status, jobId, summary);
        } catch (WeComSummaryException exception) {
            throw exception;
        } catch (Exception exception) {
            throw programError(exception);
        }
    }

    private static void validateMessages(List<MessageReference> messages) throws WeComSummaryException {
        if (messages == null || messages.isEmpty() || messages.size() > MAX_MESSAGES) {
            throw programError(null);
        }
        Set<String> msgids = new HashSet<>();
        for (MessageReference message : messages) {
            if (message == null) throw programError(null);
            requireText(message.msgid(), 256);
            requireText(message.secretKey(), 4096);
            if (!msgids.add(message.msgid())) throw programError(null);
        }
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowed)
            throws WeComSummaryException {
        if (node == null || !node.isObject()) throw programError(null);
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!allowed.containsAll(actual)) throw programError(null);
    }

    private static int integer(JsonNode node, String field) throws WeComSummaryException {
        if (!node.has(field) || !node.get(field).isNumber()) throw programError(null);
        return node.get(field).asInt();
    }

    private static String text(JsonNode node, String field, int maximum)
            throws WeComSummaryException {
        String value = optionalText(node, field, maximum);
        if (value.isBlank()) throw programError(null);
        return value;
    }

    private static String optionalText(JsonNode node, String field, int maximum)
            throws WeComSummaryException {
        if (!node.has(field)) return "";
        if (!node.get(field).isTextual()) throw programError(null);
        String value = node.get(field).asText();
        if (value.length() > maximum) throw programError(null);
        return value;
    }

    private static void requireText(String value, int maximum) throws WeComSummaryException {
        if (value == null || value.isBlank() || value.length() > maximum) throw programError(null);
    }

    private static Duration remaining(long deadline) throws WeComSummaryException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw timeout(null);
        return Duration.ofNanos(remaining);
    }

    private static WeComSummaryException timeout(Throwable cause) {
        return new WeComSummaryException("WECOM_SUMMARY_TIMEOUT", 504,
                "企业微信每日摘要调用超时", cause);
    }

    private static WeComSummaryException programError(Throwable cause) {
        return new WeComSummaryException("WECOM_SUMMARY_PROGRAM_ERROR", 502,
                "企业微信每日摘要调用失败", cause);
    }

    @FunctionalInterface
    interface AccessTokenProvider {
        String accessToken(ResolvedInstallation installation, Duration timeout);
    }

    public record MessageReference(String msgid, String secretKey) {}
    public record SubmitResult(int errcode, int status, String jobId) {}
    public record PollResult(int errcode, int status, String jobId, String summary) {}
    private record ProgramResult(int errcode, int status, String jobId, String summary) {}
}
