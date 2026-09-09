package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppPhoneOnboardingOperationEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppPhoneOnboardingOperationMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import com.crmforlogistics.messagecenter.service.channel.ChatAppCapabilityGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.List;

@Service
public class WhatsAppPhoneNumberService {
    private static final String PROVIDER = "ALIYUN_CAMS";
    private final WhatsAppProviderScopeMapper scopes;
    private final WhatsAppPhoneOnboardingOperationMapper operations;
    private final ChannelAccountMapper accounts;
    private final WhatsAppOnboardingGateway gateway;
    private final ChatAppCapabilityGate capabilityGate;

    public WhatsAppPhoneNumberService(WhatsAppProviderScopeMapper scopes,
                                      WhatsAppPhoneOnboardingOperationMapper operations,
                                      ChannelAccountMapper accounts,
                                      WhatsAppOnboardingGateway gateway) {
        this(scopes, operations, accounts, gateway, null);
    }

    @Autowired
    public WhatsAppPhoneNumberService(WhatsAppProviderScopeMapper scopes,
                                      WhatsAppPhoneOnboardingOperationMapper operations,
                                      ChannelAccountMapper accounts,
                                      WhatsAppOnboardingGateway gateway,
                                      ChatAppCapabilityGate capabilityGate) {
        this.scopes = scopes;
        this.operations = operations;
        this.accounts = accounts;
        this.gateway = gateway;
        this.capabilityGate = capabilityGate;
    }

    @Transactional
    public Status start(UUID userId, AddCommand command) {
        if (capabilityGate != null) capabilityGate.requireReady();
        WhatsAppProviderScopeEntity scope = requireScope();
        String phone = normalizePhone(command.phoneNumber());
        if (accounts.findActiveByNormalizedIdentifier(phone) != null) {
            throw failure("WHATSAPP_PHONE_ALREADY_BOUND", HttpStatus.CONFLICT);
        }
        WhatsAppPhoneOnboardingOperationEntity existing = operations.find(userId, scope.getId(), phone);
        if (existing != null && !"FAILED".equals(existing.getStatus())) return projection(existing);
        WhatsAppOnboardingGateway.ProviderResult result = gateway.add(
                new WhatsAppOnboardingGateway.AddCommand(command.countryCode(), phone, command.verifiedName()), scope);
        if (result == null) throw failure("WHATSAPP_PROVIDER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        WhatsAppPhoneOnboardingOperationEntity operation = existing == null
                ? new WhatsAppPhoneOnboardingOperationEntity() : existing;
        if (existing == null) {
            operation.setId(UUID.randomUUID());
            operation.setUserId(userId);
            operation.setProviderScopeId(scope.getId());
            operation.setPhoneNumber(phone);
            operation.setCountryCode(command.countryCode());
            operation.setVerifiedName(command.verifiedName());
        }
        operation.setStatus("PENDING");
        if (existing == null && operations.insert(operation) != 1) {
            throw failure("WHATSAPP_PHONE_ONBOARDING_CONFLICT", HttpStatus.CONFLICT);
        }
        return projection(operation);
    }

    @Transactional
    public Status sendCode(UUID userId, CodeCommand command) {
        requireCapability();
        WhatsAppProviderScopeEntity scope = requireScope();
        String phone = normalizePhone(command.phoneNumber());
        WhatsAppPhoneOnboardingOperationEntity operation = requireOperation(userId, scope, phone);
        WhatsAppOnboardingGateway.ProviderResult result = gateway.sendCode(
                new WhatsAppOnboardingGateway.CodeCommand(phone, command.locale(), command.method()), scope);
        if (result == null) throw failure("WHATSAPP_PROVIDER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        operations.updateStatus(operation.getId(), userId, "CODE_SENT");
        return new Status(operation.getId(), last4(phone), "CODE_SENT");
    }

    @Transactional
    public Status verify(UUID userId, VerifyCommand command) {
        requireCapability();
        WhatsAppProviderScopeEntity scope = requireScope();
        String phone = normalizePhone(command.phoneNumber());
        WhatsAppPhoneOnboardingOperationEntity operation = requireOperation(userId, scope, phone);
        WhatsAppOnboardingGateway.ProviderResult result = gateway.verify(
                new WhatsAppOnboardingGateway.VerifyCommand(phone, command.verificationCode()), scope);
        if (result == null || !"ACTIVE".equalsIgnoreCase(result.providerPhoneStatus())
                || !"VERIFIED".equalsIgnoreCase(result.phoneVerificationStatus())) {
            operations.updateStatus(operation.getId(), userId, "FAILED");
            throw failure("WHATSAPP_PHONE_NOT_READY", HttpStatus.CONFLICT);
        }
        if (accounts.findActiveByNormalizedIdentifier(phone) != null) {
            throw failure("WHATSAPP_PHONE_ALREADY_BOUND", HttpStatus.CONFLICT);
        }
        if (accounts.countActiveByOwnerAndChannel(userId, "chatapp") > 0) {
            throw failure("WHATSAPP_OWNER_ACCOUNT_ALREADY_EXISTS", HttpStatus.CONFLICT);
        }
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setOwnerUserId(userId);
        account.setChannelType("chatapp");
        account.setName(result.verifiedName() == null || result.verifiedName().isBlank()
                ? operation.getVerifiedName() : result.verifiedName());
        account.setAccountIdentifier(phone);
        account.setAccountIdentifierNormalized(phone);
        account.setAuthStatus("active");
        account.setSyncStatus("idle");
        account.setProviderScopeId(scope.getId());
        account.setOnboardingMode("API_ONLY");
        account.setPhoneVerificationStatus("VERIFIED");
        account.setProviderPhoneStatus("ACTIVE");
        account.setEncryptedConfig(scope.getEncryptedConfig() == null
                || scope.getEncryptedConfig().isBlank() ? "{}" : scope.getEncryptedConfig());
        if (accounts.insertOwned(account, userId) != 1) {
            throw failure("WHATSAPP_PHONE_ALREADY_BOUND", HttpStatus.CONFLICT);
        }
        operations.updateStatus(operation.getId(), userId, "REGISTERED");
        return new Status(account.getId(), last4(phone), "REGISTERED");
    }

    public List<PhoneNumberStatus> list(UUID userId) {
        WhatsAppProviderScopeEntity scope = requireScope();
        return accounts.findActiveByScope(scope.getId()).stream()
                .filter(account -> userId == null || userId.equals(account.getOwnerUserId()))
                .map(account -> new PhoneNumberStatus(account.getId(), last4(account.getAccountIdentifier()),
                        account.getOwnerUserId(), account.getOnboardingMode(),
                        account.getPhoneVerificationStatus(), account.getProviderPhoneStatus()))
                .toList();
    }

    private WhatsAppProviderScopeEntity requireScope() {
        WhatsAppProviderScopeEntity scope = scopes.findEnterpriseScope(PROVIDER);
        if (scope == null || scope.getId() == null || scope.getWabaId() == null
                || !"IDENTITY_VERIFIED".equals(scope.getIdentityStatus())) {
            throw failure("WHATSAPP_PROVIDER_SCOPE_NOT_READY", HttpStatus.CONFLICT);
        }
        return scope;
    }

    private WhatsAppPhoneOnboardingOperationEntity requireOperation(UUID userId,
                                                                     WhatsAppProviderScopeEntity scope,
                                                                     String phone) {
        WhatsAppPhoneOnboardingOperationEntity operation = operations.find(userId, scope.getId(), phone);
        if (operation == null || "REGISTERED".equals(operation.getStatus())) {
            throw failure("WHATSAPP_PHONE_ONBOARDING_REQUIRED", HttpStatus.CONFLICT);
        }
        return operation;
    }

    private static String normalizePhone(String value) {
        String phone = value == null ? "" : value.replaceAll("\\D", "");
        if (phone.length() < 6 || phone.length() > 20) throw failure("WHATSAPP_PHONE_INVALID", HttpStatus.BAD_REQUEST);
        return phone;
    }
    private static String last4(String phone) { return phone.substring(Math.max(0, phone.length() - 4)); }
    private static Status projection(WhatsAppPhoneOnboardingOperationEntity operation) {
        return new Status(operation.getId(), last4(operation.getPhoneNumber()), operation.getStatus());
    }
    private static WhatsAppAuthorizationException failure(String code, HttpStatus status) {
        return new WhatsAppAuthorizationException(code, status);
    }

    private void requireCapability() {
        if (capabilityGate != null) capabilityGate.requireReady();
    }

    public record AddCommand(String countryCode, String phoneNumber, String verifiedName) { }
    public record CodeCommand(String phoneNumber, String locale, String method) { }
    public record VerifyCommand(String phoneNumber, String verificationCode) { }
    public record Status(UUID operationId, String phoneNumberLast4, String status) { }
    public record PhoneNumberStatus(UUID accountId, String phoneNumberLast4, UUID ownerUserId,
                                    String onboardingMode, String phoneVerificationStatus,
                                    String providerPhoneStatus) { }
}
