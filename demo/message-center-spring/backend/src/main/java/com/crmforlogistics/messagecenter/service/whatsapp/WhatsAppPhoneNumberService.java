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
        if (existing != null) {
            WhatsAppOnboardingGateway.ProviderPhone recovered = gateway.syncPhoneNumbers(scope).stream()
                    .filter(phoneFact -> phone.equals(normalizePhone(phoneFact.normalizedPhoneNumber())))
                    .filter(phoneFact -> "ACTIVE".equalsIgnoreCase(phoneFact.providerStatus())
                            && "VERIFIED".equalsIgnoreCase(phoneFact.verificationStatus()))
                    .findFirst().orElse(null);
            if (recovered != null) {
                return registerFromProvider(userId, scope, existing, recovered,
                        recovered.verifiedName() == null ? existing.getVerifiedName() : recovered.verifiedName());
            }
        }
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
            operation.setAccountName(command.accountName());
            operation.setAccountRemark(command.accountRemark());
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
        WhatsAppPhoneOnboardingOperationEntity operation = requireOperation(userId, scope, command.operationId());
        if (!command.confirmed()) {
            throw failure("WHATSAPP_VERIFICATION_CODE_CONFIRMATION_REQUIRED", HttpStatus.CONFLICT);
        }
        if ("CODE_SENT".equals(operation.getStatus())) {
            return new Status(operation.getId(), null, last4(operation.getPhoneNumber()), "CODE_SENT");
        }
        String phone = operation.getPhoneNumber();
        WhatsAppOnboardingGateway.ProviderResult result = gateway.sendCode(
                new WhatsAppOnboardingGateway.CodeCommand(phone, command.locale(), command.method()), scope);
        if (result == null) throw failure("WHATSAPP_PROVIDER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        operations.updateStatus(operation.getId(), userId, "CODE_SENT");
        return new Status(operation.getId(), null, last4(phone), "CODE_SENT");
    }

    @Transactional
    public Status verify(UUID userId, VerifyCommand command) {
        requireCapability();
        WhatsAppProviderScopeEntity scope = requireScope();
        WhatsAppPhoneOnboardingOperationEntity operation = requireOperation(userId, scope, command.operationId());
        if ("REGISTERED".equals(operation.getStatus())) {
            return new Status(operation.getId(), operation.getCompletedAccountId(), last4(operation.getPhoneNumber()), "REGISTERED");
        }
        if (!"CODE_SENT".equals(operation.getStatus())) {
            throw failure("WHATSAPP_PHONE_ONBOARDING_REQUIRED", HttpStatus.CONFLICT);
        }
        String phone = operation.getPhoneNumber();
        WhatsAppOnboardingGateway.ProviderResult result = gateway.verify(
                new WhatsAppOnboardingGateway.VerifyCommand(phone, command.verificationCode()), scope);
        if (result == null || !"ACTIVE".equalsIgnoreCase(result.providerPhoneStatus())
                || !"VERIFIED".equalsIgnoreCase(result.phoneVerificationStatus())) {
            operations.updateStatus(operation.getId(), userId, "FAILED");
            throw failure("WHATSAPP_PHONE_NOT_READY", HttpStatus.CONFLICT);
        }
        WhatsAppOnboardingGateway.ProviderPhone synced = gateway.syncPhoneNumbers(scope).stream()
                .filter(phoneFact -> phone.equals(normalizePhone(phoneFact.normalizedPhoneNumber())))
                .filter(phoneFact -> "ACTIVE".equalsIgnoreCase(phoneFact.providerStatus())
                        && "VERIFIED".equalsIgnoreCase(phoneFact.verificationStatus()))
                .findFirst().orElse(null);
        if (synced == null) {
            operations.updateStatus(operation.getId(), userId, "FAILED");
            throw failure("WHATSAPP_PHONE_NOT_READY", HttpStatus.CONFLICT);
        }
        if (accounts.findActiveByNormalizedIdentifier(phone) != null) {
            operations.updateStatus(operation.getId(), userId, "FAILED");
            throw failure("WHATSAPP_PHONE_ALREADY_BOUND", HttpStatus.CONFLICT);
        }
        if (accounts.countActiveByOwnerAndChannel(userId, "chatapp") > 0) {
            operations.updateStatus(operation.getId(), userId, "FAILED");
            throw failure("WHATSAPP_OWNER_ACCOUNT_ALREADY_EXISTS", HttpStatus.CONFLICT);
        }
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setOwnerUserId(userId);
        account.setChannelType("chatapp");
        account.setName(operation.getAccountName() == null || operation.getAccountName().isBlank()
                ? (result.verifiedName() == null || result.verifiedName().isBlank()
                ? operation.getVerifiedName() : result.verifiedName()) : operation.getAccountName());
        account.setRemark(operation.getAccountRemark());
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
            operations.updateStatus(operation.getId(), userId, "FAILED");
            throw failure("WHATSAPP_PHONE_ALREADY_BOUND", HttpStatus.CONFLICT);
        }
        if (operations.complete(operation.getId(), userId, account.getId()) != 1) {
            throw failure("WHATSAPP_PHONE_ONBOARDING_CONFLICT", HttpStatus.CONFLICT);
        }
        return new Status(operation.getId(), account.getId(), last4(phone), "REGISTERED");
    }

    /** Administrative account view retained for the existing admin assignment surface. */
    public List<PhoneNumberStatus> list(UUID userId) {
        WhatsAppProviderScopeEntity scope = requireScope();
        return accounts.findActiveByScope(scope.getId()).stream()
                .filter(account -> userId == null || userId.equals(account.getOwnerUserId()))
                .map(account -> new PhoneNumberStatus(account.getId(), last4(account.getAccountIdentifier()),
                        account.getOwnerUserId(), account.getOnboardingMode(),
                        account.getPhoneVerificationStatus(), account.getProviderPhoneStatus()))
                .toList();
    }

    public List<OperationProjection> listOperations(UUID userId) {
        WhatsAppProviderScopeEntity scope = requireScope();
        return operations.findAllByUserAndScope(userId, scope.getId()).stream()
                .map(operation -> new OperationProjection(operation.getId(), operation.getCompletedAccountId(),
                        last4(operation.getPhoneNumber()), operation.getAccountName(), operation.getAccountRemark(),
                        operation.getStatus()))
                .toList();
    }

    private Status registerFromProvider(UUID userId,
                                        WhatsAppProviderScopeEntity scope,
                                        WhatsAppPhoneOnboardingOperationEntity operation,
                                        WhatsAppOnboardingGateway.ProviderPhone providerPhone,
                                        String verifiedName) {
        String phone = normalizePhone(providerPhone.normalizedPhoneNumber());
        if (accounts.findActiveByNormalizedIdentifier(phone) != null) {
            operations.updateStatus(operation.getId(), userId, "FAILED");
            throw failure("WHATSAPP_PHONE_ALREADY_BOUND", HttpStatus.CONFLICT);
        }
        if (accounts.countActiveByOwnerAndChannel(userId, "chatapp") > 0) {
            operations.updateStatus(operation.getId(), userId, "FAILED");
            throw failure("WHATSAPP_OWNER_ACCOUNT_ALREADY_EXISTS", HttpStatus.CONFLICT);
        }
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setOwnerUserId(userId);
        account.setChannelType("chatapp");
        account.setName(operation.getAccountName() == null || operation.getAccountName().isBlank()
                ? verifiedName : operation.getAccountName());
        account.setRemark(operation.getAccountRemark());
        account.setAccountIdentifier(phone);
        account.setAccountIdentifierNormalized(phone);
        account.setAuthStatus("active");
        account.setSyncStatus("idle");
        account.setProviderScopeId(scope.getId());
        account.setOnboardingMode("API_ONLY");
        account.setPhoneVerificationStatus("VERIFIED");
        account.setProviderPhoneStatus("ACTIVE");
        account.setEncryptedConfig(scope.getEncryptedConfig() == null || scope.getEncryptedConfig().isBlank()
                ? "{}" : scope.getEncryptedConfig());
        if (accounts.insertOwned(account, userId) != 1) {
            operations.updateStatus(operation.getId(), userId, "FAILED");
            throw failure("WHATSAPP_PHONE_ALREADY_BOUND", HttpStatus.CONFLICT);
        }
        if (operations.complete(operation.getId(), userId, account.getId()) != 1) {
            throw failure("WHATSAPP_PHONE_ONBOARDING_CONFLICT", HttpStatus.CONFLICT);
        }
        return new Status(operation.getId(), account.getId(), last4(phone), "REGISTERED");
    }

    private WhatsAppProviderScopeEntity requireScope() {
        WhatsAppProviderScopeEntity scope = scopes.findEnterpriseApiScope(PROVIDER);
        if (scope == null || scope.getId() == null || scope.getWabaId() == null
                || !"ENTERPRISE_API".equals(scope.getScopeType()) || !"READY".equals(scope.getStatus())
                || !"IDENTITY_VERIFIED".equals(scope.getIdentityStatus())) {
            throw failure("WHATSAPP_PROVIDER_SCOPE_NOT_READY", HttpStatus.CONFLICT);
        }
        return scope;
    }

    private WhatsAppPhoneOnboardingOperationEntity requireOperation(UUID userId,
                                                                     WhatsAppProviderScopeEntity scope,
                                                                     UUID operationId) {
        WhatsAppPhoneOnboardingOperationEntity operation = operations.findByIdForUpdate(operationId, userId);
        if (operation == null || !scope.getId().equals(operation.getProviderScopeId())) {
            throw failure("WHATSAPP_PHONE_OPERATION_NOT_FOUND", HttpStatus.NOT_FOUND);
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
        return new Status(operation.getId(), operation.getCompletedAccountId(), last4(operation.getPhoneNumber()), operation.getStatus());
    }
    private static WhatsAppAuthorizationException failure(String code, HttpStatus status) {
        return new WhatsAppAuthorizationException(code, status);
    }

    private void requireCapability() {
        if (capabilityGate != null) capabilityGate.requireReady();
    }

    public record AddCommand(String countryCode, String phoneNumber, String verifiedName,
                             String accountName, String accountRemark) {
        public AddCommand(String countryCode, String phoneNumber, String verifiedName) {
            this(countryCode, phoneNumber, verifiedName, null, null);
        }
    }
    public record CodeCommand(UUID operationId, String locale, String method, boolean confirmed) { }
    public record VerifyCommand(UUID operationId, String verificationCode) { }
    public record Status(UUID operationId, UUID accountId, String phoneNumberLast4, String status) {
        public Status(UUID operationId, String phoneNumberLast4, String status) {
            this(operationId, null, phoneNumberLast4, status);
        }
    }
    public record PhoneNumberStatus(UUID accountId, String phoneNumberLast4, UUID ownerUserId,
                                    String onboardingMode, String phoneVerificationStatus,
                                    String providerPhoneStatus) { }

    public record OperationProjection(UUID operationId, UUID accountId, String phoneNumberLast4,
                                      String accountName, String accountRemark, String status) { }
}
