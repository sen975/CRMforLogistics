package com.crmforlogistics.messagecenter;

import com.google.gson.JsonArray;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class WeComSummaryGateway {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_SUMMARY_BYTES = 65_536;
    private static final int MAX_MESSAGES = 1_000;
    private static final Set<String> OUTER_FIELDS = Set.of("errcode", "errmsg", "response_data");
    private static final Set<String> RESULT_FIELDS = Set.of("errcode", "errmsg", "status", "jobid", "summary");

    private final Config config;
    private final HttpClient client;
    private final URI apiBase;
    private final AccessTokenProvider accessTokens;

    public WeComSummaryGateway(Config config, WeComAccessTokenService accessTokens) {
        this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                URI.create(config.value("WECOM_API_BASE_URL", "https://qyapi.weixin.qq.com")),
                accessTokens::accessToken);
    }

    private WeComSummaryGateway(Config config, HttpClient client, URI apiBase,
                                AccessTokenProvider accessTokens) {
        this.config = config;
        this.client = client;
        this.apiBase = apiBase;
        this.accessTokens = accessTokens;
    }

    static WeComSummaryGateway forTests(Config config, HttpClient client, URI apiBase,
                                        AccessTokenProvider accessTokens) {
        return new WeComSummaryGateway(config, client, apiBase, accessTokens);
    }

    public SubmitResult submit(WeComAuthorizationStore.ResolvedInstallation installation,
                               List<MessageReference> messages, Duration timeout)
            throws WeComSummaryException {
        validateMessages(messages);
        JsonObject input = new JsonObject();
        input.addProperty("operation", "submit");
        input.addProperty("jobid", "");
        JsonArray messageList = new JsonArray();
        for (MessageReference message : messages) {
            JsonObject reference = new JsonObject();
            reference.addProperty("msgid", message.msgid());
            reference.addProperty("secret_key", message.secretKey());
            messageList.add(reference);
        }
        input.add("msg_list", messageList);
        ProgramResult result = call(installation, input, timeout);
        return new SubmitResult(result.errcode(), result.status(), result.jobId());
    }

    public PollResult poll(WeComAuthorizationStore.ResolvedInstallation installation,
                           String jobId, Duration timeout) throws WeComSummaryException {
        requireText(jobId, 256);
        JsonObject input = new JsonObject();
        input.addProperty("operation", "poll");
        input.addProperty("jobid", jobId);
        input.add("msg_list", new JsonArray());
        ProgramResult result = call(installation, input, timeout);
        return new PollResult(result.errcode(), result.status(), result.jobId(), result.summary());
    }

    private ProgramResult call(WeComAuthorizationStore.ResolvedInstallation installation,
                               JsonObject input, Duration timeout) throws WeComSummaryException {
        requireText(config.wecomChatDataProgramId(), 128);
        String abilityId;
        try {
            abilityId = config.wecomDailySummaryAbilityId();
        } catch (IllegalArgumentException exception) {
            throw notConfigured(exception);
        }
        requireText(abilityId, 128);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw timeout(null);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("program_id", config.wecomChatDataProgramId());
            requestBody.addProperty("ability_id", abilityId);
            requestBody.addProperty("request_data", input.toString());
            String token = accessTokens.accessToken(installation, remaining(deadline));
            URI uri = apiBase.resolve("/cgi-bin/chatdata/sync_call_program?access_token="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(remaining(deadline))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (response.statusCode() < 200 || response.statusCode() >= 300
                        || bytes.length > MAX_RESPONSE_BYTES) {
                    throw programError(null);
                }
                JsonObject outer = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                requireOnlyFields(outer, OUTER_FIELDS);
                if (integer(outer, "errcode") != 0) throw programError(null);
                return parseProgramResult(text(outer, "response_data", MAX_RESPONSE_BYTES));
            }
        } catch (WeComSummaryException exception) {
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

    private static ProgramResult parseProgramResult(String raw) throws WeComSummaryException {
        try {
            JsonObject body = JsonParser.parseString(raw).getAsJsonObject();
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

    private static void requireOnlyFields(JsonObject object, Set<String> allowed)
            throws WeComSummaryException {
        if (!allowed.containsAll(object.keySet())) throw programError(null);
    }

    private static int integer(JsonObject object, String field) throws WeComSummaryException {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.getAsJsonPrimitive(field).isNumber()) {
            throw programError(null);
        }
        return object.get(field).getAsInt();
    }

    private static String text(JsonObject object, String field, int maximum)
            throws WeComSummaryException {
        String value = optionalText(object, field, maximum);
        if (value.isBlank()) throw programError(null);
        return value;
    }

    private static String optionalText(JsonObject object, String field, int maximum)
            throws WeComSummaryException {
        if (!object.has(field)) return "";
        if (!object.get(field).isJsonPrimitive() || !object.getAsJsonPrimitive(field).isString()) {
            throw programError(null);
        }
        String value = object.get(field).getAsString();
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

    private static WeComSummaryException notConfigured(Throwable cause) {
        return new WeComSummaryException("WECOM_SUMMARY_NOT_CONFIGURED", 503,
                "企业微信每日摘要尚未配置", cause);
    }

    private static WeComSummaryException timeout(Throwable cause) {
        return new WeComSummaryException("WECOM_SUMMARY_TIMEOUT", 504,
                "企业微信每日摘要调用超时", cause);
    }

    private static WeComSummaryException programError(Throwable cause) {
        return new WeComSummaryException("WECOM_SUMMARY_PROGRAM_ERROR", 502,
                "企业微信每日摘要调用失败", cause);
    }

    interface AccessTokenProvider {
        String accessToken(WeComAuthorizationStore.ResolvedInstallation installation, Duration timeout)
                throws Exception;
    }

    public record MessageReference(String msgid, String secretKey) {}
    public record SubmitResult(int errcode, int status, String jobId) {}
    public record PollResult(int errcode, int status, String jobId, String summary) {}
    private record ProgramResult(int errcode, int status, String jobId, String summary) {}
}
