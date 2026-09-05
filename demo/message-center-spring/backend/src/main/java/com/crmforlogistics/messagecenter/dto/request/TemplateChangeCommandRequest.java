package com.crmforlogistics.messagecenter.dto.request;

import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ChangeCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ChangeType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateDraft;

public record TemplateChangeCommandRequest(
        ChangeType changeType,
        long expectedVersion,
        String clientRequestId,
        TemplateDraft template,
        Boolean allowSend,
        String remark) {
    public ChangeCommand toCommand() {
        return new ChangeCommand(changeType, expectedVersion, clientRequestId, template, allowSend, remark);
    }
}
