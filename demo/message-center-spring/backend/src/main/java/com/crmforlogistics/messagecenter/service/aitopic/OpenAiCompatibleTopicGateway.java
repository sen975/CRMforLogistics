package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GenerationInput;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GenerationOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public OpenAiCompatibleTopicGateway(AiTopicConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.parser = new TopicAiResponseParser(mapper);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.timeoutSeconds())).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()));
        this.client = RestClient.builder().baseUrl(config.baseUrl().isBlank() ? "http://127.0.0.1" : resolveEndpoint(config.baseUrl()).toString()).requestFactory(factory).build();
    }

    @Override
    public GenerationOutput generate(GenerationInput input) {
        if (config.baseUrl().isBlank()) throw new AiTopicException("AI_NOT_CONFIGURED", false);
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.putAll(buildRequest(config.model(), input, mapper));
            RestClient.RequestBodySpec call = client.post().body(request);
            if (!config.apiKey().isBlank()) call.header("Authorization", "Bearer " + config.apiKey());
            String response = call.retrieve().body(String.class);
            JsonNode root = mapper.readTree(response);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) throw new AiTopicException("AI_RESPONSE_INVALID", false);
            return parser.parse(content, input.sources().stream().map(AiTopicModels.SourceItem::id).collect(java.util.stream.Collectors.toSet()));
        } catch (AiTopicException e) {
            throw e;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            throw new AiTopicException(status == 408 || status == 429 || status >= 500 ? "AI_PROVIDER_UNAVAILABLE" : rejectionCode(status),
                    status == 408 || status == 429 || status >= 500, "HTTP_" + status, e);
        } catch (Exception e) {
            throw new AiTopicException("AI_PROVIDER_UNAVAILABLE", true, providerDiagnostic(e), e);
        }
    }

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
        return Map.of("incremental", input.incremental(), "sources", sources, "existingTopics", topics);
    }

    private static String systemPrompt() {
        return "Group only supplied ChatApp, email, and phone sources into business topics. Return JSON only: {topics:[{topicKey,title,summary,relevance,sourceIds:[uuid]}]}. For an existing topic, topicKey MUST be its exact existingTopics.id UUID; for a new topic, use a new opaque key that is not a UUID. Never invent sources or include unsupported channels. Write title and summary in Chinese.";
    }
}
