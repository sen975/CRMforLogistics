package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.CallTranscriptRevisionEntity;
import com.crmforlogistics.messagecenter.entity.ContactAiLabelEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEntity;
import com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.net.ssl.SSLException;

@Component
public class OpenAiCompatibleContactMemoryGateway implements ContactMemoryLlmGateway {
    private static final int MAX_LABEL_CHANGES = 20;
    private static final int MAX_REASON_CHARS = 500;
    private static final int MAX_RAW_DIAGNOSTICS = 500;

    private final AiTopicConfig config;
    private final ObjectMapper mapper;
    private final RestClient client;

    public OpenAiCompatibleContactMemoryGateway(AiTopicConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()));
        this.client = RestClient.builder()
                .baseUrl(config.baseUrl().isBlank()
                        ? "http://127.0.0.1"
                        : resolveEndpoint(config.baseUrl()).toString())
                .requestFactory(factory)
                .build();
    }

    @Override
    public ContactMemoryModels.LlmOutput generate(ContactMemoryModels.Context context) {
        if (context == null || context.contactId() == null || context.ownerUserId() == null) {
            throw new ContactMemoryLlmGateway.GatewayException("OWNER_MISMATCH", false);
        }
        if (config.baseUrl().isBlank()) {
            throw new ContactMemoryLlmGateway.GatewayException("LLM_UNAVAILABLE", false,
                    "BASE_URL_MISSING", null);
        }

        String rawResponse = null;
        try {
            Map<String, Object> request = buildRequest(config.model(), context, mapper,
                    config.maxInputBytes());
            RestClient.RequestBodySpec call = client.post().body(request);
            if (!config.apiKey().isBlank()) {
                call.header("Authorization", "Bearer " + config.apiKey());
            }
            HttpResult response = call.exchange((requestMessage, responseMessage) ->
                    new HttpResult(
                            responseMessage.getStatusCode().value(),
                            readResponseBody(responseMessage.getBody(), config.auditMaxResponseBytes())));
            rawResponse = response.body();
            if (response.status() >= 400) {
                throw providerStatusFailure(response.status());
            }
            JsonNode root = mapper.readTree(rawResponse);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new ContactMemoryLlmGateway.GatewayException("INVALID_OUTPUT", false,
                        "EMPTY_CONTENT", null);
            }
            ContactMemoryModels.LlmOutput output = parseOutput(content, context);
            return new ContactMemoryModels.LlmOutput(
                    output.observations(),
                    output.profile(),
                    output.labelChanges(),
                    output.noises(),
                    config.model(),
                    boundedDiagnostic("PARSED"));
        } catch (ContactMemoryLlmGateway.GatewayException exception) {
            throw exception;
        } catch (org.springframework.web.client.HttpStatusCodeException exception) {
            throw providerStatusFailure(exception.getStatusCode().value());
        } catch (Exception exception) {
            throw transportFailure(exception);
        }
    }

    public static ContactMemoryModels.LlmOutput parseOutput(
            String rawJson, ContactMemoryModels.Context context) {
        if (rawJson == null || context == null) {
            throw new ContactMemoryLlmGateway.GatewayException("INVALID_OUTPUT", false);
        }
        try {
            JsonNode root = new ObjectMapper().readTree(stripCodeFence(rawJson));
            if (root == null || !root.isObject()
                    || !root.has("observations")
                    || !root.has("profile")
                    || !root.has("labelChanges")
                    || !root.has("noises")) {
                throw invalidOutput();
            }
            List<ContactMemoryModels.ObservationCandidate> observations =
                    parseObservations(root.get("observations"), context);
            ContactMemoryModels.ProfileCandidate profile = parseProfile(root.get("profile"));
            List<ContactMemoryModels.LabelChange> labelChanges =
                    parseLabelChanges(root.get("labelChanges"), context);
            List<ContactMemoryModels.Noise> noises = parseNoises(root.get("noises"), context);
            return new ContactMemoryModels.LlmOutput(
                    observations, profile, labelChanges, noises, null, boundedDiagnostic("PARSED"));
        } catch (ContactMemoryLlmGateway.GatewayException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new ContactMemoryLlmGateway.GatewayException(
                    "INVALID_OUTPUT", false, "INVALID_JSON", exception);
        } catch (RuntimeException exception) {
            throw new ContactMemoryLlmGateway.GatewayException(
                    "INVALID_OUTPUT", false, "STRUCTURE_INVALID", exception);
        }
    }

    public static ContactMemoryLlmGateway.GatewayException transportFailure(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> visited = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException) {
                return new ContactMemoryLlmGateway.GatewayException(
                        "LLM_TIMEOUT", true, "TIMEOUT", failure);
            }
            if (current instanceof UnknownHostException) {
                return new ContactMemoryLlmGateway.GatewayException(
                        "LLM_UNAVAILABLE", true, "DNS_ERROR", failure);
            }
            if (current instanceof ConnectException) {
                return new ContactMemoryLlmGateway.GatewayException(
                        "LLM_UNAVAILABLE", true, "CONNECT_ERROR", failure);
            }
            if (current instanceof SSLException) {
                return new ContactMemoryLlmGateway.GatewayException(
                        "LLM_UNAVAILABLE", true, "TLS_ERROR", failure);
            }
            current = current.getCause();
        }
        return new ContactMemoryLlmGateway.GatewayException(
                "LLM_UNAVAILABLE", true, "CLIENT_ERROR", failure);
    }

    static URI resolveEndpoint(String configuredBaseUrl) {
        String value = configuredBaseUrl.trim();
        if (value.endsWith("/v1/chat/completions")) {
            return URI.create(value);
        }
        if (value.endsWith("/v1") || value.endsWith("/v1/")) {
            return URI.create(value.replaceAll("/+$", "") + "/chat/completions");
        }
        return URI.create(value.replaceAll("/+$", "") + "/v1/chat/completions");
    }

    static Map<String, Object> buildRequest(String model,
                                            ContactMemoryModels.Context context,
                                            ObjectMapper mapper,
                                            long maxInputBytes) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model);
            request.put("temperature", 0.1);
            request.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt()),
                    Map.of("role", "user", "content", mapper.writeValueAsString(
                            toPayload(context)))));
            byte[] serialized = mapper.writeValueAsBytes(request);
            if (serialized.length > maxInputBytes) {
                throw new ContactMemoryLlmGateway.GatewayException(
                        "INPUT_LIMIT", false, "INPUT_BYTES_EXCEEDED", null);
            }
            return request;
        } catch (ContactMemoryLlmGateway.GatewayException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new ContactMemoryLlmGateway.GatewayException(
                    "INVALID_OUTPUT", false, "REQUEST_SERIALIZATION_FAILED", exception);
        }
    }

    private static Map<String, Object> toPayload(ContactMemoryModels.Context context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("owner", Map.of(
                "contactId", context.contactId(),
                "ownerUserId", context.ownerUserId()));
        payload.put("manualTags", context.manualTags().stream()
                .map(OpenAiCompatibleContactMemoryGateway::manualTagPayload).toList());
        payload.put("inboundMessages", context.inboundMessages().stream()
                .map(OpenAiCompatibleContactMemoryGateway::messagePayload).toList());
        payload.put("currentProfile", profilePayload(context.currentProfile()));
        payload.put("observations", context.observations().stream()
                .map(OpenAiCompatibleContactMemoryGateway::observationPayload).toList());
        payload.put("activeFacts", context.activeFacts().stream()
                .map(OpenAiCompatibleContactMemoryGateway::factPayload).toList());
        payload.put("activeLabels", context.activeLabels().stream()
                .map(OpenAiCompatibleContactMemoryGateway::labelPayload).toList());
        payload.put("topics", context.topics().stream()
                .map(OpenAiCompatibleContactMemoryGateway::topicPayload).toList());
        payload.put("callTranscripts", context.callTranscripts().stream()
                .map(OpenAiCompatibleContactMemoryGateway::transcriptPayload).toList());
        payload.put("inputCursor", context.inputCursor());
        return payload;
    }

    private static Map<String, Object> manualTagPayload(ContactMemoryModels.ManualTag tag) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", tag.id());
        payload.put("name", bounded(tag.name(), 100));
        payload.put("color", bounded(tag.color(), 30));
        return payload;
    }

    private static Map<String, Object> messagePayload(MessageEntity message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", message.getId());
        payload.put("occurredAt", instantPayload(message.getOccurredAt()));
        payload.put("direction", bounded(message.getDirection(), 32));
        payload.put("subject", bounded(message.getSubject(), 500));
        payload.put("text", bounded(message.getBodyText(), 4_000));
        return payload;
    }

    private static Map<String, Object> profilePayload(ContactProfileVersionEntity profile) {
        if (profile == null) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", profile.getId());
        payload.put("content", bounded(profile.getContent(), 200));
        payload.put("version", profile.getVersion());
        return payload;
    }

    private static Map<String, Object> observationPayload(ContactMemoryObservationEntity observation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", observation.getId());
        payload.put("category", observation.getCategory());
        payload.put("normalizedKey", observation.getNormalizedKey());
        payload.put("observedValue", bounded(observation.getObservedValue(), 500));
        payload.put("polarity", observation.getPolarity());
        payload.put("confidence", observation.getConfidence());
        payload.put("status", observation.getStatus());
        return payload;
    }

    private static Map<String, Object> factPayload(ContactMemoryFactEntity fact) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", fact.getId());
        payload.put("category", fact.getCategory());
        payload.put("normalizedKey", fact.getNormalizedKey());
        payload.put("normalizedValue", fact.getNormalizedValue());
        payload.put("displayValue", bounded(fact.getDisplayValue(), 500));
        payload.put("polarity", fact.getPolarity());
        payload.put("status", fact.getStatus());
        payload.put("confidence", fact.getConfidence());
        return payload;
    }

    private static Map<String, Object> labelPayload(ContactAiLabelEntity label) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", label.getId());
        payload.put("category", label.getCategory());
        payload.put("normalizedName", label.getNormalizedName());
        payload.put("displayName", bounded(label.getDisplayName(), 100));
        payload.put("status", label.getStatus());
        payload.put("confidence", label.getConfidence());
        return payload;
    }

    private static Map<String, Object> topicPayload(AiTopicEntity topic) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", topic.getId());
        payload.put("title", bounded(topic.getTitle(), 1_000));
        payload.put("summary", bounded(topic.getConfirmedSummary() == null
                ? topic.getAiSummary() : topic.getConfirmedSummary(), 1_000));
        payload.put("firstOccurredAt", instantPayload(topic.getFirstOccurredAt()));
        payload.put("lastOccurredAt", instantPayload(topic.getLastOccurredAt()));
        return payload;
    }

    private static Map<String, Object> transcriptPayload(CallTranscriptRevisionEntity transcript) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", transcript.getId());
        payload.put("editedAt", instantPayload(transcript.getEditedAt()));
        payload.put("text", bounded(transcript.getText(), 4_000));
        return payload;
    }

    private static List<ContactMemoryModels.ObservationCandidate> parseObservations(
            JsonNode node, ContactMemoryModels.Context context) {
        if (node == null || !node.isArray()) {
            throw invalidOutput();
        }
        List<ContactMemoryModels.ObservationCandidate> result = new ArrayList<>();
        for (JsonNode item : node) {
            rejectForbiddenFields(item, "status", "factStatus", "colorToken");
            ContactMemoryModels.Category category = category(item, "category");
            ContactMemoryModels.Polarity polarity = polarity(item, "polarity");
            BigDecimalValue confidence = confidence(item.get("confidence"));
            String key = requiredText(item, "normalizedKey", 100);
            String value = requiredText(item, "observedValue", 500);
            List<ContactMemoryModels.EvidenceRef> evidence = evidence(item.get("evidence"));
            validateObservationEvidence(evidence, context);
            result.add(new ContactMemoryModels.ObservationCandidate(
                    category, key, value, polarity, confidence.value(), evidence,
                    optionalText(item, "reason", MAX_REASON_CHARS)));
        }
        return List.copyOf(result);
    }

    private static ContactMemoryModels.ProfileCandidate parseProfile(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw invalidOutput();
        }
        rejectForbiddenFields(node, "status", "colorToken");
        String content = requiredText(node, "content", 200);
        return new ContactMemoryModels.ProfileCandidate(content);
    }

    private static List<ContactMemoryModels.LabelChange> parseLabelChanges(
            JsonNode node, ContactMemoryModels.Context context) {
        if (node == null || !node.isArray()) {
            throw invalidOutput();
        }
        if (node.size() > MAX_LABEL_CHANGES) {
            throw new ContactMemoryLlmGateway.GatewayException("OUTPUT_LIMIT", false);
        }
        List<ContactMemoryModels.LabelChange> result = new ArrayList<>();
        for (JsonNode item : node) {
            rejectForbiddenFields(item, "status", "colorToken", "factStatus");
            ContactMemoryModels.LabelOperation operation = operation(item, "operation");
            ContactMemoryModels.Category category = category(item, "category");
            BigDecimalValue confidence = confidence(item.get("confidence"));
            String name = requiredText(item, "name", 100);
            if (!ContactMemoryModels.isValidAiLabelName(name)) {
                throw invalidOutput();
            }
            List<ContactMemoryModels.EvidenceRef> evidence = evidence(item.get("evidence"));
            validateLabelEvidence(evidence, context);
            result.add(new ContactMemoryModels.LabelChange(
                    operation, category, name, confidence.value(), evidence,
                    optionalText(item, "reason", MAX_REASON_CHARS)));
        }
        return List.copyOf(result);
    }

    private static List<ContactMemoryModels.Noise> parseNoises(
            JsonNode node, ContactMemoryModels.Context context) {
        if (node == null || !node.isArray()) {
            throw invalidOutput();
        }
        List<ContactMemoryModels.Noise> result = new ArrayList<>();
        for (JsonNode item : node) {
            List<ContactMemoryModels.EvidenceRef> evidence = evidence(item.get("evidence"));
            validateGeneralEvidence(evidence, context);
            result.add(new ContactMemoryModels.Noise(
                    evidence, optionalText(item, "reason", MAX_REASON_CHARS)));
        }
        return List.copyOf(result);
    }

    private static List<ContactMemoryModels.EvidenceRef> evidence(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new ContactMemoryLlmGateway.GatewayException("INVALID_EVIDENCE", false);
        }
        List<ContactMemoryModels.EvidenceRef> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                throw new ContactMemoryLlmGateway.GatewayException("INVALID_EVIDENCE", false);
            }
            String type = requiredText(item, "type", 40);
            UUID id;
            try {
                id = UUID.fromString(requiredText(item, "id", 80));
            } catch (IllegalArgumentException exception) {
                throw new ContactMemoryLlmGateway.GatewayException(
                        "INVALID_EVIDENCE", false, "INVALID_UUID", exception);
            }
            ContactMemoryModels.EvidenceType evidenceType;
            try {
                evidenceType = ContactMemoryModels.EvidenceType.valueOf(type);
            } catch (IllegalArgumentException exception) {
                throw new ContactMemoryLlmGateway.GatewayException(
                        "INVALID_EVIDENCE", false, "UNKNOWN_EVIDENCE_TYPE", exception);
            }
            if (!seen.add(evidenceType + ":" + id)) {
                throw new ContactMemoryLlmGateway.GatewayException(
                        "INVALID_EVIDENCE", false, "DUPLICATE_EVIDENCE", null);
            }
            result.add(new ContactMemoryModels.EvidenceRef(evidenceType, id));
        }
        return List.copyOf(result);
    }

    private static void validateObservationEvidence(
            List<ContactMemoryModels.EvidenceRef> evidence,
            ContactMemoryModels.Context context) {
        for (ContactMemoryModels.EvidenceRef reference : evidence) {
            if (reference.type() == ContactMemoryModels.EvidenceType.LONG_TERM_FACT
                    || reference.type() == ContactMemoryModels.EvidenceType.PROFILE_VERSION) {
                throw new ContactMemoryLlmGateway.GatewayException(
                        "INVALID_EVIDENCE", false, "OBSERVATION_EVIDENCE_TYPE", null);
            }
            validateGeneralEvidence(List.of(reference), context);
        }
    }

    private static void validateLabelEvidence(
            List<ContactMemoryModels.EvidenceRef> evidence,
            ContactMemoryModels.Context context) {
        for (ContactMemoryModels.EvidenceRef reference : evidence) {
            validateGeneralEvidence(List.of(reference), context);
        }
    }

    private static void validateGeneralEvidence(
            List<ContactMemoryModels.EvidenceRef> evidence,
            ContactMemoryModels.Context context) {
        Set<UUID> messageIds = context.inboundMessages().stream()
                .map(MessageEntity::getId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> topicIds = context.topics().stream()
                .filter(topic -> belongsToContext(topic, context))
                .map(AiTopicEntity::getId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> transcriptIds = context.callTranscripts().stream()
                .map(CallTranscriptRevisionEntity::getId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> factIds = context.activeFacts().stream()
                .filter(fact -> belongsToContext(fact, context))
                .map(ContactMemoryFactEntity::getId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        UUID profileId = context.currentProfile() != null
                && belongsToContext(context.currentProfile(), context)
                ? context.currentProfile().getId() : null;

        for (ContactMemoryModels.EvidenceRef reference : evidence) {
            boolean known = switch (reference.type()) {
                case MESSAGE -> messageIds.contains(reference.id());
                case TOPIC -> topicIds.contains(reference.id());
                case CALL_TRANSCRIPT -> transcriptIds.contains(reference.id());
                case LONG_TERM_FACT -> factIds.contains(reference.id());
                case PROFILE_VERSION -> profileId != null && profileId.equals(reference.id());
            };
            if (!known) {
                boolean presentInWrongOwner = switch (reference.type()) {
                    case LONG_TERM_FACT -> context.activeFacts().stream()
                            .anyMatch(fact -> reference.id().equals(fact.getId()));
                    case PROFILE_VERSION -> context.currentProfile() != null
                            && reference.id().equals(context.currentProfile().getId());
                    case TOPIC -> context.topics().stream()
                            .anyMatch(topic -> reference.id().equals(topic.getId()));
                    default -> false;
                };
                throw new ContactMemoryLlmGateway.GatewayException(
                        presentInWrongOwner ? "OWNER_MISMATCH" : "INVALID_EVIDENCE",
                        false);
            }
        }
    }

    private static boolean belongsToContext(ContactMemoryFactEntity fact,
                                            ContactMemoryModels.Context context) {
        return context.contactId().equals(fact.getContactId())
                && context.ownerUserId().equals(fact.getOwnerUserId());
    }

    private static boolean belongsToContext(ContactProfileVersionEntity profile,
                                            ContactMemoryModels.Context context) {
        return context.contactId().equals(profile.getContactId())
                && context.ownerUserId().equals(profile.getOwnerUserId());
    }

    private static boolean belongsToContext(AiTopicEntity topic,
                                            ContactMemoryModels.Context context) {
        if (topic.getContactId() != null && !context.contactId().equals(topic.getContactId())) {
            return false;
        }
        return !"CONTACT".equals(topic.getOwnerType())
                || context.contactId().equals(topic.getOwnerId());
    }

    private static ContactMemoryModels.Category category(JsonNode node, String field) {
        String value = requiredText(node, field, 50);
        try {
            return ContactMemoryModels.Category.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalidOutput();
        }
    }

    private static ContactMemoryModels.Polarity polarity(JsonNode node, String field) {
        String value = requiredText(node, field, 50);
        try {
            return ContactMemoryModels.Polarity.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalidOutput();
        }
    }

    private static ContactMemoryModels.LabelOperation operation(JsonNode node, String field) {
        String value = requiredText(node, field, 50);
        try {
            return ContactMemoryModels.LabelOperation.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalidOutput();
        }
    }

    private static BigDecimalValue confidence(JsonNode node) {
        if (node == null || !node.isNumber()) {
            throw invalidOutput();
        }
        double value = node.asDouble(Double.NaN);
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw invalidOutput();
        }
        return new BigDecimalValue(node.decimalValue());
    }

    private static String requiredText(JsonNode node, String field, int maxChars) {
        if (node == null || !node.has(field) || !node.get(field).isTextual()) {
            throw invalidOutput();
        }
        String value = node.get(field).asText().trim();
        if (value.isBlank() || value.codePointCount(0, value.length()) > maxChars) {
            throw invalidOutput();
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field, int maxChars) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        if (!node.get(field).isTextual()) {
            throw invalidOutput();
        }
        String value = node.get(field).asText().trim();
        if (value.codePointCount(0, value.length()) > maxChars) {
            throw invalidOutput();
        }
        return value;
    }

    private static void rejectForbiddenFields(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            throw invalidOutput();
        }
        for (String field : fields) {
            if (node.has(field)) {
                throw invalidOutput();
            }
        }
    }

    private static ContactMemoryLlmGateway.GatewayException invalidOutput() {
        return new ContactMemoryLlmGateway.GatewayException("INVALID_OUTPUT", false);
    }

    static String readResponseBody(InputStream input, long maxBytes) throws IOException {
        if (input == null || maxBytes <= 0) {
            throw new ContactMemoryLlmGateway.GatewayException("OUTPUT_LIMIT", false);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) {
                throw new ContactMemoryLlmGateway.GatewayException(
                        "OUTPUT_LIMIT", false, "RESPONSE_BYTES_EXCEEDED", null);
            }
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static ContactMemoryLlmGateway.GatewayException providerStatusFailure(int status) {
        if (status == 408 || status == 429 || status >= 500) {
            return new ContactMemoryLlmGateway.GatewayException(
                    status == 429 ? "LLM_RATE_LIMITED" : "LLM_UNAVAILABLE",
                    true, "HTTP_" + status, null);
        }
        return new ContactMemoryLlmGateway.GatewayException(
                "LLM_UNAVAILABLE", false, "HTTP_" + status, null);
    }

    private static String stripCodeFence(String value) {
        String result = value.trim();
        if (result.startsWith("```")) {
            int newline = result.indexOf('\n');
            if (newline >= 0) {
                result = result.substring(newline + 1).trim();
            }
            if (result.endsWith("```")) {
                result = result.substring(0, result.length() - 3).trim();
            }
        }
        return result;
    }

    private static String systemPrompt() {
        return """
                You are a contact-memory extraction engine.
                Return ONE JSON object and nothing else: no prose, no explanation, no markdown code fences.
                A strict parser validates every field name and type below; any deviation fails the whole run.

                {
                  "observations": [
                    {
                      "category": "NEED",
                      "normalizedKey": "short-stable-key",
                      "observedValue": "what was actually observed",
                      "polarity": "NEUTRAL",
                      "confidence": 0.8,
                      "evidence": [{"type": "MESSAGE", "id": "<uuid from the input context>"}],
                      "reason": "optional explanation"
                    }
                  ],
                  "profile": {"content": "contact summary, at most 200 characters"},
                  "labelChanges": [
                    {
                      "operation": "ADD",
                      "category": "NEED",
                      "name": "short label",
                      "confidence": 0.9,
                      "evidence": [{"type": "MESSAGE", "id": "<uuid from the input context>"}],
                      "reason": "optional explanation"
                    }
                  ],
                  "noises": [
                    {
                      "evidence": [{"type": "MESSAGE", "id": "<uuid from the input context>"}],
                      "reason": "why this message carries no signal"
                    }
                  ]
                }

                All four top-level fields must always be present, and observations, labelChanges and noises
                must always be arrays (empty is allowed). Use null when there is no profile to record.

                Field rules enforced by the parser:
                - "profile" is an object with a "content" field, or null. It is never a bare string.
                - "evidence" is always a NON-EMPTY ARRAY of {"type", "id"} objects, never a bare id string.
                  Never emit "evidenceId" or "evidenceIds".
                - "id" is a UUID copied verbatim from the input context. Never invent identifiers.
                - "type" is one of MESSAGE, TOPIC, CALL_TRANSCRIPT, LONG_TERM_FACT, PROFILE_VERSION in
                  upper case.
                - Observations must not cite LONG_TERM_FACT or PROFILE_VERSION evidence.
                - "confidence" is required on observations and label changes, and must be a number
                  between 0 and 1.
                - "normalizedKey" is required on observations and must be at most 100 characters.
                - "profile" content has a HARD LIMIT of 200 characters. Count them before answering.
                  Aim for 120 characters or fewer; if the summary runs longer, drop the least
                  important details instead of exceeding the limit. An over-long summary fails the run.
                - Never emit these fields: status, factStatus, colorToken.

                Allowed category values: IDENTITY, PRODUCT_INTEREST, NEED, PERSONALITY_COMMUNICATION,
                DECISION_FACTOR, RISK, RELATIONSHIP_STAGE, OTHER_STABLE_TRAIT.
                Allowed polarity values: POSITIVE, NEGATIVE, NEUTRAL.
                Allowed label operations: ADD, UPDATE, STALE, INACTIVATE, RESTORE.
                Return at most 20 label changes.

                Label name rules:
                - At most 32 Unicode code points, no line breaks, no repeated whitespace.
                - No sentence-ending punctuation at the end: 。！？!?；;：:,.，、
                - Never a sentence. Do not start with 客户, 他, 她, 对方, 用户, 本人 or 我们 followed by
                  希望, 需要, 想要, 计划, 正在, 已经, 会, 将, 喜欢, 认为, 确认, 表示 or 要求.
                - Manual tags are read-only context. Never modify, delete, inactivate, restore, rename,
                  recolor, or return changes for manual tags.
                - Never return database permission decisions or IDs not supplied in the context.
                """;
    }

    private static String bounded(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.codePointCount(0, value.length()) <= maxChars
                ? value : value.substring(0, value.offsetByCodePoints(0, maxChars));
    }

    private static String instantPayload(Instant value) {
        return value == null ? null : value.toString();
    }

    private static String boundedDiagnostic(String value) {
        return bounded(value, MAX_RAW_DIAGNOSTICS);
    }

    private record BigDecimalValue(java.math.BigDecimal value) {
    }

    private record HttpResult(int status, String body) {
    }
}
