package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;

import java.util.List;

public interface WhatsAppOnboardingGateway {
    StartupProfile startupProfile(String onboardingMode);

    void verifyEmbeddedCode(String code);

    BoundScope bindWaba(String wabaId);

    List<ProviderPhone> syncPhoneNumbers(WhatsAppProviderScopeEntity scope);

    /**
     * Reads the provider's already configured phone numbers using the global
     * administrator CAMS credentials. This path must not start onboarding.
     */
    default List<ProviderPhone> syncConfiguredPhoneNumbers() {
        throw new UnsupportedOperationException("Configured phone sync is not supported");
    }

    ProviderResult add(AddCommand command, WhatsAppProviderScopeEntity scope);

    ProviderResult sendCode(CodeCommand command, WhatsAppProviderScopeEntity scope);

    ProviderResult verify(VerifyCommand command, WhatsAppProviderScopeEntity scope);

    default String encryptedProviderConfig() {
        return null;
    }

    default String encryptedProviderConfig(String chatappFrom) {
        return encryptedProviderConfig();
    }

    record AddCommand(String countryCode, String phoneNumber, String verifiedName) { }
    record CodeCommand(String phoneNumber, String locale, String method) { }
    record VerifyCommand(String phoneNumber, String verificationCode) { }
    record ProviderResult(String custSpaceId, String wabaId, String phoneNumber,
                          String providerPhoneStatus, String phoneVerificationStatus,
                          String verifiedName) { }

    record StartupProfile(String appId, String configId, String onboardingMode) { }
    record BoundScope(String custSpaceId, String wabaId) { }
    record ProviderPhone(String normalizedPhoneNumber, String verifiedName,
                         String providerStatus, String verificationStatus) { }
}
