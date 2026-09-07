package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.dto.response.TemplateResponse;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.service.message.TemplateMessageTextResolver;
import com.crmforlogistics.messagecenter.service.whatsapp.template.TemplateDisplayName;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChatAppTemplateService {

    private final TemplateMapper templateMapper;
    private final TemplateMessageTextResolver templateTextResolver;
    private final ObjectMapper objectMapper;

    public ChatAppTemplateService(TemplateMapper templateMapper,
                                  TemplateMessageTextResolver templateTextResolver,
                                  ObjectMapper objectMapper) {
        this.templateMapper = Objects.requireNonNull(templateMapper);
        this.templateTextResolver = Objects.requireNonNull(templateTextResolver);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public List<TemplateResponse> listAll() {
        return templateMapper.findGloballySendable().stream()
                .filter(ChatAppTemplateService::isSendable)
                .map(this::toResponse)
                .toList();
    }

    public List<TemplateResponse> listForAccount(UUID accountId) {
        return templateMapper.findSendableForChannelAccount(accountId).stream()
                .filter(template -> isSendableBy(accountId, template))
                .map(this::toResponse)
                .toList();
    }

    private TemplateResponse toResponse(TemplateEntity entity) {
        Map<String, List<String>> examples = readExamples(entity.getExamplesJsonb());
        List<String> placeholders = requiredPlaceholders(entity);
        Map<String, List<String>> variableDefinitions = new LinkedHashMap<>();
        placeholders.forEach(variable -> variableDefinitions.put(variable, examples.getOrDefault(variable, List.of())));
        return new TemplateResponse(
                entity.getProviderTemplateId(),
                entity.getName(),
                TemplateDisplayName.format(entity.getName(), entity.getRemark()),
                entity.getLanguageCode(),
                entity.getBody(),
                placeholders,
                entity.getCategory(),
                readComponents(entity.getComponentsJsonb()),
                Map.copyOf(variableDefinitions));
    }

    public List<String> requiredPlaceholders(TemplateEntity entity) {
        Objects.requireNonNull(entity, "template is required");
        Map<String, List<String>> examples = readExamples(entity.getExamplesJsonb());
        return examples.isEmpty()
                ? templateTextResolver.placeholders(entity.getBody())
                : List.copyOf(examples.keySet());
    }

    private JsonNode readComponents(String componentsJsonb) {
        try {
            return objectMapper.readTree(componentsJsonb == null || componentsJsonb.isBlank() ? "[]" : componentsJsonb);
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }

    private Map<String, List<String>> readExamples(String examplesJsonb) {
        try {
            Map<String, List<String>> examples = objectMapper.readValue(
                    examplesJsonb == null || examplesJsonb.isBlank() ? "{}" : examplesJsonb,
                    new TypeReference<>() {});
            return examples == null ? Map.of() : examples;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static boolean isSendableBy(UUID accountId, TemplateEntity template) {
        return accountId != null && accountId.equals(template.getChannelAccountId())
                && "APPROVED".equalsIgnoreCase(template.getStatus())
                && Boolean.TRUE.equals(template.getAllowSend())
                && template.getDeletedAt() == null;
    }

    private static boolean isSendable(TemplateEntity template) {
        return template != null
                && "APPROVED".equalsIgnoreCase(template.getStatus())
                && Boolean.TRUE.equals(template.getAllowSend())
                && template.getDeletedAt() == null;
    }
}
