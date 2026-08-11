package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.CreateResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.DeleteResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ModifyResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.PropertyResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ProviderTemplatePage;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateSnapshot;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.UploadedMedia;

import java.util.Optional;
import java.util.UUID;

public interface WhatsAppTemplateGateway {

    CreateResult create(UUID accountId, TemplateCommand command);

    ModifyResult modify(UUID accountId, String templateCode, String language, TemplateCommand command);

    PropertyResult setSendPermission(UUID accountId, String templateCode, String language, boolean allowSend);

    DeleteResult delete(UUID accountId, String templateCode, String language);

    ProviderTemplatePage list(UUID accountId, int page, int size);

    Optional<TemplateSnapshot> detail(UUID accountId, String templateCode, String language);

    UploadedMedia upload(UUID accountId, HeaderFormat format, byte[] bytes, String fileName, String contentType);
}
