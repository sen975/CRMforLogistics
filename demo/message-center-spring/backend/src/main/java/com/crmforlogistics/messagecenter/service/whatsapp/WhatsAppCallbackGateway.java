package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentials;

public interface WhatsAppCallbackGateway {
    ProviderApplyResult updatePhone(PhoneUpdate command, ChatAppAccountCredentials credentials);

    ProviderApplyResult updateAccount(AccountUpdate command, ChatAppAccountCredentials credentials);

    record PhoneUpdate(String custSpaceId, String phoneNumber, String upCallbackUrl,
                       String statusCallbackUrl, String httpFlag, String queueFlag) { }

    record AccountUpdate(String custSpaceId, String statusCallbackUrl,
                         String httpFlag, String queueFlag) { }

    record ProviderApplyResult(String requestId) { }
}
