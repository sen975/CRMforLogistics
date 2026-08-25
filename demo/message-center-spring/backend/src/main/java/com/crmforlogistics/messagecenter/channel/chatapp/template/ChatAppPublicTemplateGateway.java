package com.crmforlogistics.messagecenter.channel.chatapp.template;

import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.Page;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.Query;

public interface ChatAppPublicTemplateGateway {
    Page list(Query query);
}
