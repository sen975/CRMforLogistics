package com.crmforlogistics.messagecenter.service.message;

import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves provider templates into the user-visible message text. */
@Service
public class TemplateMessageTextResolver {
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}|\\$\\(\\s*([A-Za-z0-9_.-]+)\\s*\\)|\\(\\s*([A-Za-z][A-Za-z0-9_.-]*)\\s*\\)");

    private final TemplateMapper templateMapper;
    private final ObjectMapper objectMapper;

    public TemplateMessageTextResolver(TemplateMapper templateMapper, ObjectMapper objectMapper) {
        this.templateMapper = Objects.requireNonNull(templateMapper);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public String renderForSend(UUID channelAccountId, String templateCode, String languageCode,
                                Map<String, ?> parameters) {
        String code = value(templateCode);
        if (code.isBlank()) {
            throw new IllegalArgumentException("CHATAPP_TEMPLATE_CODE_REQUIRED");
        }
        String language = firstNonBlank(languageCode, "en_US");
        Optional<TemplateEntity> template = templateMapper.findForSend(channelAccountId, code, language);
        if (template.isEmpty() || !isSendableBy(channelAccountId, template.get())
                || value(template.get().getBody()).isBlank()) {
            throw new IllegalArgumentException("CHATAPP_TEMPLATE_NOT_SYNCED");
        }
        if (!expectedVariables(template.get()).equals(new LinkedHashSet<>(
                parameters == null ? Map.<String, Object>of().keySet() : parameters.keySet()))) {
            throw new IllegalArgumentException("CHATAPP_TEMPLATE_PARAMETERS_INVALID");
        }
        return render(template.get().getBody(), parameters);
    }

    private LinkedHashSet<String> expectedVariables(TemplateEntity template) {
        Map<String, Object> examples = readMap(template.getExamplesJsonb());
        return examples.isEmpty()
                ? new LinkedHashSet<>(placeholders(template.getBody()))
                : new LinkedHashSet<>(examples.keySet());
    }

    public List<String> placeholders(String body) {
        List<String> result = new ArrayList<>();
        if (body == null || body.isBlank()) return result;
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            String key = firstNonBlank(matcher.group(1), matcher.group(2), matcher.group(3));
            if (!key.isBlank() && !result.contains(key)) result.add(key);
        }
        return result;
    }

    /** Returns a historical snapshot unless it is the old bracket placeholder. */
    public String resolve(MessageEntity message) {
        String stored = message.getBodyText();
        if (!"template".equalsIgnoreCase(message.getMessageKind())
                || (stored != null && !stored.isBlank() && !stored.startsWith("["))) {
            return stored;
        }
        try {
            Map<String, Object> metadata = readMap(message.getMetadataJsonb());
            String code = value(metadata.get("templateCode"));
            if (code.isBlank() || message.getChannelAccountId() == null) {
                return stored;
            }
            String language = firstNonBlank(value(metadata.get("languageCode")), "en_US");
            Optional<TemplateEntity> template = templateMapper.findForDisplay(
                    message.getChannelAccountId(), code, language);
            if (template.isEmpty() || value(template.get().getBody()).isBlank()) {
                return stored;
            }
            return render(template.get().getBody(), parameters(metadata.get("templateParams")));
        } catch (RuntimeException e) {
            return stored;
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> parameters(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, new TypeReference<>() {});
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private static String render(String body, Map<String, ?> parameters) {
        Map<String, ?> safe = parameters == null ? Map.of() : parameters;
        Matcher matcher = PLACEHOLDER.matcher(body);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String key = firstNonBlank(matcher.group(1), matcher.group(2), matcher.group(3));
            Object raw = safe.get(key);
            String replacement = raw == null ? matcher.group() : String.valueOf(raw);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static boolean isSendableBy(UUID channelAccountId, TemplateEntity template) {
        return channelAccountId != null && channelAccountId.equals(template.getChannelAccountId())
                && "APPROVED".equalsIgnoreCase(value(template.getStatus()))
                && Boolean.TRUE.equals(template.getAllowSend())
                && template.getDeletedAt() == null;
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }
}
