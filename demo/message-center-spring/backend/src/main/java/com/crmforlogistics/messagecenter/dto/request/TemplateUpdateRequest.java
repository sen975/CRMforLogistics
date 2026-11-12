package com.crmforlogistics.messagecenter.dto.request;

import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;

import java.util.List;
import java.util.Map;

public record TemplateUpdateRequest(
        String name,
        String category,
        List<TemplateComponent> components,
        Map<String, List<String>> examples,
        Integer messageSendTtlSeconds,
        String clientRequestId) {

    public TemplateCommand toCommand(String language) {
        return new TemplateCommand(name, language, category, components, examples,
                messageSendTtlSeconds, clientRequestId);
    }
}
