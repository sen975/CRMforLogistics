package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.Page;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.Query;

public interface ChatAppPublicTemplateGateway {
    Page list(TemplateCredentialSource source, Query query);
}
