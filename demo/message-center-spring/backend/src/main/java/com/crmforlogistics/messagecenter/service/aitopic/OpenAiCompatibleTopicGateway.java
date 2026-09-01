package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GenerationInput;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GenerationOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.SSLException;

@Component
public class OpenAiCompatibleTopicGateway implements TopicAiGateway {
    private final AiTopicConfig config;
    private final ObjectMapper mapper;
    private final TopicAiResponseParser parser;
    private final RestClient client;
    private final AiTopicGenerationAuditService audit;

    public OpenAiCompatibleTopicGateway(AiTopicConfig config, ObjectMapper mapper) {
        this(config, mapper, null);
    }

    @Autowired
    public OpenAiCompatibleTopicGateway(AiTopicConfig config, ObjectMapper mapper, AiTopicGenerationAuditService audit) {
        this.config = config;
        this.mapper = mapper;
        this.audit = audit;
        this.parser = new TopicAiResponseParser(mapper);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.timeoutSeconds())).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()));
        this.client = RestClient.builder().baseUrl(config.baseUrl().isBlank() ? "http://127.0.0.1" : resolveEndpoint(config.baseUrl()).toString()).requestFactory(factory).build();
    }

    @Override
    public GenerationOutput generate(GenerationInput input) {
        return generate(input, null);
    }

    @Override
    public GenerationOutput generate(GenerationInput input, AiTopicGenerationAuditService.Context auditContext) {
        String rawResponse = null;
        Integer responseStatus = null;
        String responseHeaders = null;
        try {
            if (config.baseUrl().isBlank()) throw new AiTopicException("AI_NOT_CONFIGURED", false, "BASE_URL_MISSING", null);
            Map<String, Object> request = new LinkedHashMap<>();
            request.putAll(buildRequest(config.model(), input, mapper));
            RestClient.RequestBodySpec call = client.post().body(request);
            if (!config.apiKey().isBlank()) call.header("Authorization", "Bearer " + config.apiKey());
            HttpResult http = call.exchange((req, res) -> new HttpResult(res.getStatusCode().value(),
                    res.getHeaders().entrySet().stream().filter(e -> e.getKey() != null && !e.getKey().equalsIgnoreCase("authorization") && !e.getKey().equalsIgnoreCase("set-cookie"))
                            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().stream().limit(5).toList())),
                    new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            rawResponse = http.body();
            responseStatus = http.status();
            responseHeaders = mapper.writeValueAsString(http.headers());
            if (auditContext != null && audit != null) audit.outcome(auditContext, "PROVIDER_CALL", "STARTED", http.status(), responseHeaders, rawResponse, null, null, null, config);
            if (http.status() >= 400) throw new org.springframework.web.client.HttpServerErrorException(org.springframework.http.HttpStatusCode.valueOf(http.status()), "provider response", http.body().getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
            String response = http.body();
            JsonNode root = mapper.readTree(response);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) throw new AiTopicException("AI_RESPONSE_INVALID", false, "EMPTY_CONTENT", null);
            if (auditContext != null && audit != null) audit.outcome(auditContext, "RESPONSE_PARSE", "STARTED", http.status(), responseHeaders, response, null, null, null, config);
            GenerationOutput output = parser.parse(content, input.sources().stream().map(AiTopicModels.SourceItem::id).collect(java.util.stream.Collectors.toSet()));
            if (auditContext != null && audit != null) audit.outcome(auditContext, "RESPONSE_VALIDATE", "SUCCEEDED", http.status(), responseHeaders, response, mapper.writeValueAsString(output), null, null, config);
            return output;
        } catch (AiTopicException e) {
            if (auditContext != null && audit != null) audit.outcome(auditContext,
                    e.code().startsWith("AI_RESPONSE_") ? (e.code().equals("AI_RESPONSE_PARSE_FAILED") ? "RESPONSE_PARSE" : "RESPONSE_VALIDATE") : "PROVIDER_CALL",
                    "FAILED", responseStatus, responseHeaders, rawResponse, null, e.code(), e.diagnostic(), config);
            throw e;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String code = status == 408 || status == 429 || status >= 500 ? "AI_PROVIDER_UNAVAILABLE" : rejectionCode(status);
            if (auditContext != null && audit != null) audit.outcome(auditContext, "PROVIDER_CALL", "FAILED", status, null, e.getResponseBodyAsString(), null, code, "HTTP_" + status, config);
            throw new AiTopicException(code, status == 408 || status == 429 || status >= 500, "HTTP_" + status, e);
        } catch (Exception e) {
            if (auditContext != null && audit != null) audit.outcome(auditContext, "PROVIDER_CALL", "FAILED", null, null, null, null, "AI_PROVIDER_UNAVAILABLE", providerDiagnostic(e), config);
            throw new AiTopicException("AI_PROVIDER_UNAVAILABLE", true, providerDiagnostic(e), e);
        }
    }

    private record HttpResult(int status, Map<String, List<String>> headers, String body) {}

    static URI resolveEndpoint(String configuredBaseUrl) {
        String value = configuredBaseUrl.trim();
        if (value.endsWith("/v1/chat/completions")) return URI.create(value);
        if (value.endsWith("/v1") || value.endsWith("/v1/")) return URI.create(value.replaceAll("/+$", "") + "/chat/completions");
        return URI.create(value.replaceAll("/+$", "") + "/v1/chat/completions");
    }

    static String rejectionCode(int status) {
        if (status == 401 || status == 403) return "AI_PROVIDER_AUTH_FAILED";
        if (status == 404) return "AI_PROVIDER_ENDPOINT_OR_MODEL_NOT_FOUND";
        return "AI_PROVIDER_REQUEST_INVALID";
    }

    static String providerDiagnostic(Throwable failure) {
        Throwable current = failure;
        java.util.Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof UnknownHostException) return "DNS_ERROR";
            if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException) return "TIMEOUT";
            if (current instanceof ConnectException) return "CONNECT_ERROR";
            if (current instanceof SSLException) return "TLS_ERROR";
            current = current.getCause();
        }
        return "CLIENT_ERROR";
    }

    static Map<String, Object> buildRequest(String model, GenerationInput input, ObjectMapper mapper) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("temperature", 0.1);
        request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt()),
                Map.of("role", "user", "content", writePayload(input, mapper))));
        return request;
    }

    private static String writePayload(GenerationInput input, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(toPayload(input));
        } catch (Exception error) {
            throw new AiTopicException("AI_REQUEST_INVALID", false, error);
        }
    }

    private static Map<String, Object> toPayload(GenerationInput input) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (var source : input.sources()) {
            sources.add(Map.of("id", source.id(), "sourceType", source.sourceType(), "channelType", source.channelType(),
                    "occurredAt", source.occurredAt(), "direction", source.direction(), "subject", source.subject(), "text", source.text()));
        }
        List<Map<String, Object>> topics = input.existingTopics().stream().map(topic -> Map.of(
                "id", topic.id(), "title", topic.title(), "summary", topic.summary(),
                "firstOccurredAt", topic.firstOccurredAt(), "lastOccurredAt", topic.lastOccurredAt(),
                "sourceIds", topic.sourceIds())).toList();
        return Map.of("owner", Map.of("type", input.owner().type(), "id", input.owner().id()),
                "incremental", input.incremental(), "sources", sources, "existingTopics", topics);
    }

    private static String systemPrompt() {
        return "Group only supplied ChatApp, email, phone, and WeCom summaries into business topics. WeCom summaries contain the official summary only, never infer an unavailable original message. All supplied sources and existing topics share one owner scope; never associate another owner. Return JSON only: {topics:[{topicKey,title,summary,relevance,sourceIds:[uuid]}]}. Every topic MUST have a non-empty sourceIds array; assign every supplied source to exactly one topic, never leave a source ungrouped, and never emit a topic with an empty sourceIds. For an existing topic, topicKey MUST be its exact existingTopics.id UUID; for a new topic, use a new opaque key that is not a UUID. Never invent sources or include unsupported channels. Write title and summary in Chinese.";
    }
}
