package com.crmforlogistics.messagecenter.dto.response;

import java.util.List;

public record TemplateResponse(
        String templateCode,
        String templateName,
        String languageCode,
        String body,
        List<String> placeholders
) {}
