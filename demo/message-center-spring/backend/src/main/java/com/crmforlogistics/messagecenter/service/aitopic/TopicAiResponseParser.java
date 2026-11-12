package com.crmforlogistics.messagecenter.service.aitopic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GenerationOutput;
import static com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicAssignment;

public class TopicAiResponseParser {
    private final ObjectMapper mapper;

    public TopicAiResponseParser() { this(new ObjectMapper()); }
    public TopicAiResponseParser(ObjectMapper mapper) { this.mapper = mapper; }

    public GenerationOutput parse(String json, Set<UUID> allowedSourceIds) {
        try {
            JsonNode root = mapper.readTree(stripCodeFence(json));
            JsonNode topics = root == null ? null : root.get("topics");
            if (topics == null || !topics.isArray() || topics.isEmpty() || topics.size() > 20) throw invalid();
            List<TopicAssignment> result = new ArrayList<>();
            Set<UUID> seen = new HashSet<>();
            Set<String> topicKeys = new HashSet<>();
            for (JsonNode topic : topics) {
                String key = text(topic, "topicKey", 100);
                String title = text(topic, "title", 200);
                String summary = text(topic, "summary", 4000);
                double relevance = relevance(topic.get("relevance"));
                JsonNode sourceIds = topic.get("sourceIds");
                if (key.isBlank() || title.isBlank() || summary.isBlank() || relevance < 0 || relevance > 1
                        || sourceIds == null || !sourceIds.isArray()) throw invalid();
                if (sourceIds.isEmpty()) throw invalid();
                if (!topicKeys.add(key)) throw invalid();
                List<UUID> ids = new ArrayList<>();
                for (JsonNode source : sourceIds) {
                    UUID id = UUID.fromString(source.asText());
                    if (!allowedSourceIds.contains(id) || !seen.add(id)) throw invalid();
                    ids.add(id);
                }
                result.add(new TopicAssignment(key, title, summary, relevance, ids));
            }
            if (seen.isEmpty() || !seen.equals(allowedSourceIds)) throw invalid();
            return new GenerationOutput(List.copyOf(result));
        } catch (AiTopicException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AiTopicException("AI_RESPONSE_PARSE_FAILED", false, "INVALID_JSON", e);
        } catch (Exception e) {
            throw new AiTopicException("AI_RESPONSE_INVALID", false, "STRUCTURE_OR_SOURCE_VALIDATION_FAILED", e);
        }
    }

    private static String stripCodeFence(String json) {
        String value = json.trim();
        if (value.startsWith("```")) {
            int newline = value.indexOf('\n');
            if (newline != -1) value = value.substring(newline + 1).trim();
            if (value.endsWith("```")) value = value.substring(0, value.length() - 3).trim();
        }
        return value;
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().codePointCount(0, value.asText().length()) > max) return "";
        return value.asText().trim();
    }

    private static double relevance(JsonNode value) {
        if (value == null || value.isNull()) return -1;
        if (value.isNumber()) return value.asDouble(-1);
        if (!value.isTextual()) return -1;
        String normalized = value.asText().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "high", "高" -> 0.9d;
            case "medium", "中" -> 0.5d;
            case "low", "低" -> 0.1d;
            default -> {
                try { yield Double.parseDouble(normalized); }
                catch (NumberFormatException ignored) { yield -1; }
            }
        };
    }

    private static AiTopicException invalid() { return new AiTopicException("AI_RESPONSE_INVALID", false); }
}
