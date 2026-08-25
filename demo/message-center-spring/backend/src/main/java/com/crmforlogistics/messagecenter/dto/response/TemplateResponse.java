package com.crmforlogistics.messagecenter.dto.response;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record TemplateResponse(
        String templateCode,
        String templateName,
        String displayName,
        String languageCode,
        String body,
        List<String> placeholders,
        String category,
        JsonNode components,
        Map<String, List<String>> variableDefinitions
) {}
