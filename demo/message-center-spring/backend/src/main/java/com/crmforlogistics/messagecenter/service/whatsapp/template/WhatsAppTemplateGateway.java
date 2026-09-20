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

public interface WhatsAppTemplateGateway {

    CreateResult create(TemplateCredentialSource source, TemplateCommand command);

    ModifyResult modify(TemplateCredentialSource source, String templateCode, String language, TemplateCommand command);

    PropertyResult setSendPermission(TemplateCredentialSource source, String templateCode, String language, boolean allowSend);

    DeleteResult delete(TemplateCredentialSource source, String templateCode, String language);

    ProviderTemplatePage list(TemplateCredentialSource source, int page, int size);

    Optional<TemplateSnapshot> detail(TemplateCredentialSource source, String templateCode, String language);

    UploadedMedia upload(TemplateCredentialSource source, HeaderFormat format, byte[] bytes, String fileName, String contentType);
}
