package com.crmforlogistics.messagecenter.channel.chatapp.template;

import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.Page;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.Query;

import java.util.UUID;

public interface ChatAppPublicTemplateGateway {
    Page list(UUID accountId, Query query);
}
