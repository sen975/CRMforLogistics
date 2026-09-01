package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.entity.AiTopicGenerationAttemptEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicGenerationJobEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicGenerationAttemptMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AiTopicGenerationAuditService {
    private static final Pattern SENSITIVE_JSON_FIELD = Pattern.compile(
            "(?i)(\"(?:authorization|api[_-]?key|access[_-]?token|token|secret(?:[_-]?key)?|password)\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"");
    private final AiTopicGenerationAttemptMapper mapper;
    private final ObjectMapper objectMapper;

    public AiTopicGenerationAuditService(AiTopicGenerationAttemptMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Context begin(AiTopicGenerationJobEntity job, AiTopicModels.GenerationInput input, AiTopicConfig config) {
        AiTopicGenerationAttemptEntity entity = new AiTopicGenerationAttemptEntity();
        entity.setId(UUID.randomUUID());
        entity.setGenerationJobId(job.getId());
        entity.setContactId(job.getContactId());
        entity.setOwnerType(job.getOwnerType());
        entity.setOwnerId(job.getOwnerId());
        entity.setAttemptNumber((job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1);
        entity.setProviderHost(safeHost(config.baseUrl()));
        entity.setModel(config.model());
        String payload = redact(serialize(OpenAiCompatibleTopicGateway.buildRequest(config.model(), input, objectMapper)));
        String bounded = boundJson(payload, config.auditMaxRequestBytes());
        entity.setRequestPayload(bounded);
        entity.setRequestTruncated(!bounded.equals(payload));
        entity.setStage("REQUEST_BUILD");
        entity.setStatus("STARTED");
        entity.setCreatedAt(Instant.now());
        mapper.insertStarted(entity);
        return new Context(entity.getId(), entity.getCreatedAt());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void outcome(Context context, String stage, String status, Integer responseStatus, String headers,
                        String rawResponse, String parsedResponse, String errorCode, String diagnostic,
                        AiTopicConfig config) {
        AiTopicGenerationAttemptEntity entity = new AiTopicGenerationAttemptEntity();
        entity.setId(context.id());
        entity.setStage(stage); entity.setStatus(status); entity.setResponseStatus(responseStatus);
        String safeRaw = redact(rawResponse);
        String boundedRaw = bound(safeRaw, config.auditMaxResponseBytes());
        entity.setRawResponseBody(boundedRaw); entity.setResponseTruncated(safeRaw != null && !boundedRaw.equals(safeRaw));
        entity.setResponseHeaders(redact(headers)); entity.setParsedResponse(redact(parsedResponse));
        entity.setErrorCode(errorCode); entity.setErrorDiagnostic(bound(redact(diagnostic), 4000));
        entity.setDurationMs(Math.max(0, java.time.Duration.between(context.startedAt(), Instant.now()).toMillis()));
        entity.setCompletedAt("STARTED".equals(status) ? null : Instant.now());
        mapper.updateOutcome(entity);
    }

    private String serialize(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { return "{}"; } }
    private static String bound(String value, long maxBytes) {
        if (value == null) return null;
        int max = (int) Math.min(Integer.MAX_VALUE, Math.max(256, maxBytes));
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= max) return value;
        return new String(bytes, 0, max, java.nio.charset.StandardCharsets.UTF_8);
    }
    private String redact(String value) {
        if (value == null || value.isBlank()) return value;
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root == null) return value;
            redactNode(root);
            return objectMapper.writeValueAsString(root);
        } catch (Exception ignored) {
            return SENSITIVE_JSON_FIELD.matcher(value).replaceAll("$1\"***\"");
        }
    }
    private static void redactNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            var fields = object.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (isSensitiveField(field.getKey())) object.put(field.getKey(), "***");
                else redactNode(field.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) redactNode(child);
        }
    }
    private static boolean isSensitiveField(String fieldName) {
        String normalized = fieldName.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("authorization") || normalized.equals("apikey") || normalized.equals("accesstoken")
                || normalized.equals("token") || normalized.equals("secret") || normalized.equals("secretkey")
                || normalized.equals("password");
    }
    private String boundJson(String value, long maxBytes) {
        String bounded = bound(value, maxBytes);
        if (bounded == null || bounded.equals(value)) return bounded;
        try {
            return objectMapper.writeValueAsString(Map.of("truncated", true, "sha256", sha256(value), "prefix", bounded));
        } catch (Exception ignored) { return "{\"truncated\":true}"; }
    }
    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception ignored) { return "unavailable"; }
    }
    private static String safeHost(String baseUrl) {
        try { URI uri = URI.create(baseUrl); return uri.getHost() == null ? "" : uri.getHost(); }
        catch (Exception ignored) { return ""; }
    }
    public record Context(UUID id, Instant startedAt) {}
}
