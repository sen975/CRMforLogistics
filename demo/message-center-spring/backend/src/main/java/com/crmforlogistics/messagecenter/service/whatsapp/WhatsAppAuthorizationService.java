package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppAuthorizationAttemptEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppAuthorizationPhoneCandidateEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppAuthorizationAttemptMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppAuthorizationPhoneCandidateMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.service.channel.ChatAppCapabilityGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class WhatsAppAuthorizationService {
    private static final String PROVIDER = "ALIYUN_CAMS";
    private static final int ATTEMPT_TTL_SECONDS = 300;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WhatsAppAuthorizationAttemptMapper attempts;
    private final WhatsAppAuthorizationPhoneCandidateMapper phoneCandidates;
    private final ChannelAccountMapper accounts;
    private final WhatsAppProviderScopeMapper scopes;
    private final WhatsAppOnboardingGateway gateway;
    private final ChatAppCapabilityGate capabilityGate;
    private final RoleMapper roles;
    private final Clock clock;

    public WhatsAppAuthorizationService(WhatsAppAuthorizationAttemptMapper attempts,
                                        ChannelAccountMapper accounts,
                                        WhatsAppProviderScopeMapper scopes,
                                        WhatsAppOnboardingGateway gateway) {
        this(attempts, accounts, scopes, null, gateway, null, null, Clock.systemUTC());
    }

    public WhatsAppAuthorizationService(WhatsAppAuthorizationAttemptMapper attempts,
                                        ChannelAccountMapper accounts,
                                        WhatsAppProviderScopeMapper scopes,
                                        WhatsAppOnboardingGateway gateway,
                                        Clock clock) {
        this(attempts, accounts, scopes, null, gateway, null, null, clock);
    }

    public WhatsAppAuthorizationService(WhatsAppAuthorizationAttemptMapper attempts,
                                        ChannelAccountMapper accounts,
                                        WhatsAppProviderScopeMapper scopes,
                                        WhatsAppOnboardingGateway gateway,
                                        ChatAppCapabilityGate capabilityGate,
                                        RoleMapper roles,
                                        Clock clock) {
        this(attempts, accounts, scopes, null, gateway, capabilityGate, roles, clock);
    }

    @Autowired
    public WhatsAppAuthorizationService(WhatsAppAuthorizationAttemptMapper attempts,
                                        ChannelAccountMapper accounts,
                                        WhatsAppProviderScopeMapper scopes,
                                        WhatsAppAuthorizationPhoneCandidateMapper phoneCandidates,
                                        WhatsAppOnboardingGateway gateway,
                                        ChatAppCapabilityGate capabilityGate,
                                        RoleMapper roles,
                                        Clock clock) {
        this.attempts = attempts;
        this.phoneCandidates = phoneCandidates;
        this.accounts = accounts;
        this.scopes = scopes;
        this.gateway = gateway;
        this.capabilityGate = capabilityGate;
        this.roles = roles;
        this.clock = clock;
    }

    @Transactional
    public AttemptProjection createAttempt(UUID userId) {
        return createAttempt(userId, "BUSINESS_APP_COEXISTENCE");
    }

    @Transactional
    public AttemptProjection createAttempt(UUID userId, String onboardingMode) {
        return createAttempt(userId, onboardingMode, null, null);
    }

    @Transactional
    public AttemptProjection createAttempt(UUID userId, String onboardingMode,
                                           String accountName, String accountRemark) {
        requireCapability();
        if (userId == null) throw failure("WHATSAPP_AUTH_USER_REQUIRED", HttpStatus.UNAUTHORIZED);
        String mode = normalizeOnboardingMode(onboardingMode);
        if ("ADMIN_API_WABA".equals(mode) && !isAdmin(userId)) {
            throw failure("WHATSAPP_ENTERPRISE_WABA_ADMIN_REQUIRED", HttpStatus.FORBIDDEN);
        }
        if ("EMPLOYEE_BUSINESS_APP".equals(mode)
                && accounts.countActiveByOwnerAndChannel(userId, "chatapp") > 0) {
            throw failure("WHATSAPP_OWNER_ACCOUNT_ALREADY_EXISTS", HttpStatus.CONFLICT);
        }
        String state = randomState();
        WhatsAppAuthorizationAttemptEntity entity = new WhatsAppAuthorizationAttemptEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setStateHash(hashState(state));
        entity.setStatus("PENDING");
        entity.setOnboardingMode(mode);
        entity.setAccountName(normalizeOptional(accountName));
        entity.setAccountRemark(normalizeOptional(accountRemark));
        entity.setExpiresAt(clock.instant().plusSeconds(ATTEMPT_TTL_SECONDS));
        if (attempts.insert(entity) != 1) {
            throw failure("WHATSAPP_AUTH_ATTEMPT_CREATE_FAILED", HttpStatus.SERVICE_UNAVAILABLE);
        }
        WhatsAppOnboardingGateway.StartupProfile startupProfile = gateway.startupProfile(mode);
        if (startupProfile == null || isBlank(startupProfile.appId()) || isBlank(startupProfile.configId())) {
            throw failure("WHATSAPP_ONBOARDING_MODE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return new AttemptProjection(entity.getId(), state, entity.getExpiresAt(), startupProfile);
    }

    /**
     * Completes the self-service attempt according to the mode persisted at startup. The caller
     * cannot select the mode at completion time.
     */
    @Transactional
    public CompletionProjection completeAuthorization(UUID userId, SelfServiceCompletionCommand command) {
        return completeSelfServiceAuthorization(userId, command, null);
    }

    private CompletionProjection completeSelfServiceAuthorization(UUID userId,
                                                                    SelfServiceCompletionCommand command,
                                                                    String requiredMode) {
        requireCapability();
        if (userId == null || command == null || command.attemptId() == null) {
            throw failure("WHATSAPP_AUTH_STATE_INVALID", HttpStatus.BAD_REQUEST);
        }

        WhatsAppAuthorizationAttemptEntity attempt = attempts.findByIdForUpdate(command.attemptId());
        verifyAttemptIdentity(userId, command.state(), attempt);
        if ("COMPLETED".equals(attempt.getStatus())) {
            String completedMode = normalizeOnboardingMode(attempt.getOnboardingMode());
            if (requiredMode != null && !requiredMode.equals(completedMode)) {
                throw failure("WHATSAPP_ONBOARDING_MODE_INVALID", HttpStatus.CONFLICT);
            }
            return new CompletionProjection(attempt.getCompletedAccountId(),
                    isBlank(attempt.getCompletedPhoneNumber()) ? null : last4(normalizePhone(attempt.getCompletedPhoneNumber())),
                    completedMode);
        }
        verifyPendingAttempt(userId, command.state(), command.event(), attempt);
        String onboardingMode = normalizeOnboardingMode(attempt.getOnboardingMode());
        if (requiredMode != null && !requiredMode.equals(onboardingMode)) {
            throw failure("WHATSAPP_ONBOARDING_MODE_INVALID", HttpStatus.CONFLICT);
        }
        if ("ADMIN_API_WABA".equals(onboardingMode)) {
            return completeEnterpriseApiWaba(userId, command, attempt);
        }
        return completeOwnedBusinessApp(userId, command, attempt);
    }

    private CompletionProjection completeOwnedBusinessApp(UUID userId,
                                                           SelfServiceCompletionCommand command,
                                                           WhatsAppAuthorizationAttemptEntity attempt) {
        String wabaId = required(command.wabaId(), "WHATSAPP_WABA_ID_REQUIRED");
        String phoneNumberId = required(command.phoneNumberId(), "WHATSAPP_PHONE_ID_REQUIRED");
        String code = required(command.code(), "WHATSAPP_EMBEDDED_SIGNUP_CODE_REQUIRED");
        gateway.verifyEmbeddedCode(code);

        WhatsAppOnboardingGateway.BoundScope bound = gateway.bindWaba(wabaId);
        WhatsAppProviderScopeEntity scope = ensureOwnedBusinessAppScope(userId, wabaId, bound);
        Instant now = clock.instant();
        if (attempts.advanceMetaCompleted(attempt.getId(), wabaId, phoneNumberId, now) != 1) {
            throw failure("WHATSAPP_AUTH_STATE_CONFLICT", HttpStatus.CONFLICT);
        }

        List<WhatsAppOnboardingGateway.ProviderPhone> usablePhones = gateway.syncPhoneNumbers(scope).stream()
                .filter(phone -> isUsable(phone.providerStatus(), phone.verificationStatus()))
                .toList();
        if (usablePhones.isEmpty()) {
            attempts.fail(attempt.getId(), "PROVIDER_SYNC", "WHATSAPP_PHONE_NOT_READY");
            throw failure("WHATSAPP_PHONE_NOT_READY", HttpStatus.CONFLICT);
        }
        if (usablePhones.size() > 1) {
            return new CompletionProjection(null, null, "BUSINESS_APP_COEXISTENCE", true,
                    issuePhoneCandidates(attempt, usablePhones));
        }

        WhatsAppOnboardingGateway.ProviderPhone providerPhone = usablePhones.get(0);
        String phoneNumber = normalizePhone(providerPhone.normalizedPhoneNumber());
        if (accounts.findActiveByNormalizedIdentifier(phoneNumber) != null) {
            attempts.fail(attempt.getId(), "ACCOUNT_BIND", "WHATSAPP_PHONE_ALREADY_BOUND");
            throw failure("WHATSAPP_PHONE_ALREADY_BOUND", HttpStatus.CONFLICT);
        }
        if (accounts.countActiveByOwnerAndChannel(userId, "chatapp") > 0) {
            attempts.fail(attempt.getId(), "ACCOUNT_BIND", "WHATSAPP_OWNER_ACCOUNT_ALREADY_EXISTS");
            throw failure("WHATSAPP_OWNER_ACCOUNT_ALREADY_EXISTS", HttpStatus.CONFLICT);
        }

        if (attempts.markProviderSynced(attempt.getId(), phoneNumber) != 1) {
            throw failure("WHATSAPP_AUTH_STATE_CONFLICT", HttpStatus.CONFLICT);
        }
        ChannelAccountEntity account = newChannelAccount(userId, scope, phoneNumber, providerPhone,
                "BUSINESS_APP_COEXISTENCE", attempt.getAccountName(), attempt.getAccountRemark());
        if (accounts.insertOwned(account, userId) != 1) {
            throw failure("WHATSAPP_PHONE_ALREADY_BOUND", HttpStatus.CONFLICT);
        }
        if (attempts.complete(attempt.getId(), account.getId(), now) != 1) {
            throw failure("WHATSAPP_AUTH_STATE_CONFLICT", HttpStatus.CONFLICT);
        }
        return new CompletionProjection(account.getId(), last4(phoneNumber), account.getOnboardingMode());
    }

    private CompletionProjection completeEnterpriseApiWaba(UUID userId,
                                                            SelfServiceCompletionCommand command,
                                                            WhatsAppAuthorizationAttemptEntity attempt) {
        if (!isAdmin(userId)) {
            throw failure("WHATSAPP_ENTERPRISE_WABA_ADMIN_REQUIRED", HttpStatus.FORBIDDEN);
        }
        String wabaId = required(command.wabaId(), "WHATSAPP_WABA_ID_REQUIRED");
        String phoneNumberId = required(command.phoneNumberId(), "WHATSAPP_PHONE_ID_REQUIRED");
        gateway.verifyEmbeddedCode(required(command.code(), "WHATSAPP_EMBEDDED_SIGNUP_CODE_REQUIRED"));
        WhatsAppProviderScopeEntity scope = ensureEnterpriseApiScope(wabaId, gateway.bindWaba(wabaId));
        Instant now = clock.instant();
        if (attempts.advanceMetaCompleted(attempt.getId(), wabaId, phoneNumberId, now) != 1) {
            throw failure("WHATSAPP_AUTH_STATE_CONFLICT", HttpStatus.CONFLICT);
        }
        gateway.syncPhoneNumbers(scope);
        if (attempts.markScopeProviderSynced(attempt.getId()) != 1) {
            throw failure("WHATSAPP_AUTH_STATE_CONFLICT", HttpStatus.CONFLICT);
        }
        if (attempts.completeScope(attempt.getId(), now) != 1) {
            throw failure("WHATSAPP_AUTH_STATE_CONFLICT", HttpStatus.CONFLICT);
        }
        return new CompletionProjection(null, null, "ADMIN_API_WABA");
    }

    private List<PhoneCandidateProjection> issuePhoneCandidates(
            WhatsAppAuthorizationAttemptEntity attempt,
            List<WhatsAppOnboardingGateway.ProviderPhone> usablePhones) {
        if (phoneCandidates == null) {
            throw failure("WHATSAPP_PHONE_SELECTION_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        }
        phoneCandidates.deleteByAttempt(attempt.getId());
        List<PhoneCandidateProjection> projections = new ArrayList<>(usablePhones.size());
        for (WhatsAppOnboardingGateway.ProviderPhone phone : usablePhones) {
            String normalizedPhone = normalizePhone(phone.normalizedPhoneNumber());
            String candidateId = randomState();
            WhatsAppAuthorizationPhoneCandidateEntity candidate =
                    new WhatsAppAuthorizationPhoneCandidateEntity();
            candidate.setId(UUID.randomUUID());
            candidate.setAttemptId(attempt.getId());
            candidate.setTokenHash(hashState(candidateId));
            candidate.setPhoneNumber(normalizedPhone);
            candidate.setMaskedPhone(maskedPhone(normalizedPhone));
            candidate.setExpiresAt(attempt.getExpiresAt());
            if (phoneCandidates.insertCandidate(candidate) != 1) {
                throw failure("WHATSAPP_PHONE_SELECTION_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
            }
            projections.add(new PhoneCandidateProjection(candidateId, candidate.getMaskedPhone()));
        }
        return List.copyOf(projections);
    }

    @Transactional
    public CompletionProjection selectBusinessAppPhone(UUID userId, PhoneSelectionCommand command) {
        requireCapability();
        if (userId == null || command == null || command.attemptId() == null) {
            throw failure("WHATSAPP_AUTH_STATE_INVALID", HttpStatus.BAD_REQUEST);
        }
        WhatsAppAuthorizationAttemptEntity attempt = attempts.findByIdForUpdate(command.attemptId());
        verifySelectableAttempt(userId, command.state(), attempt);
        Instant now = clock.instant();
        if (phoneCandidates == null) {
            throw failure("WHATSAPP_PHONE_SELECTION_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        }
        WhatsAppAuthorizationPhoneCandidateEntity candidate = phoneCandidates.findValid(
                attempt.getId(), hashState(required(command.candidateId(), "WHATSAPP_PHONE_CANDIDATE_REQUIRED")), now);
        if (candidate == null) {
            throw failure("WHATSAPP_PHONE_CANDIDATE_INVALID", HttpStatus.NOT_FOUND);
        }

        WhatsAppProviderScopeEntity scope = scopes.findOwnedBusinessAppScope(
                PROVIDER, attempt.getCompletedWabaId(), userId);
        if (scope == null || scope.getId() == null || !"READY".equals(scope.getStatus())) {
            throw failure("WHATSAPP_PROVIDER_SCOPE_NOT_READY", HttpStatus.CONFLICT);
        }
        WhatsAppOnboardingGateway.ProviderPhone selected = gateway.syncPhoneNumbers(scope).stream()
                .filter(phone -> isUsable(phone.providerStatus(), phone.verificationStatus()))
                .filter(phone -> candidate.getPhoneNumber().equals(normalizePhone(phone.normalizedPhoneNumber())))
                .findFirst()
                .orElseThrow(() -> failure("WHATSAPP_PHONE_CANDIDATE_STALE", HttpStatus.CONFLICT));
        String phoneNumber = candidate.getPhoneNumber();
        if (accounts.findActiveByNormalizedIdentifier(phoneNumber) != null) {
            throw failure("WHATSAPP_PHONE_ALREADY_BOUND", HttpStatus.CONFLICT);
        }
        if (accounts.countActiveByOwnerAndChannel(userId, "chatapp") > 0) {
            throw failure("WHATSAPP_OWNER_ACCOUNT_ALREADY_EXISTS", HttpStatus.CONFLICT);
        }
        if (attempts.markProviderSynced(attempt.getId(), phoneNumber) != 1) {
            throw failure("WHATSAPP_AUTH_STATE_CONFLICT", HttpStatus.CONFLICT);
        }
        ChannelAccountEntity account = newChannelAccount(userId, scope, phoneNumber, selected,
                "BUSINESS_APP_COEXISTENCE", attempt.getAccountName(), attempt.getAccountRemark());
        if (accounts.insertOwned(account, userId) != 1) {
            throw failure("WHATSAPP_PHONE_ALREADY_BOUND", HttpStatus.CONFLICT);
        }
        if (attempts.complete(attempt.getId(), account.getId(), now) != 1) {
            throw failure("WHATSAPP_AUTH_STATE_CONFLICT", HttpStatus.CONFLICT);
        }
        phoneCandidates.deleteByAttempt(attempt.getId());
        return new CompletionProjection(account.getId(), last4(phoneNumber), account.getOnboardingMode());
    }

    private void verifySelectableAttempt(UUID userId, String state,
                                         WhatsAppAuthorizationAttemptEntity attempt) {
        if (attempt == null || !constantTimeEquals(hashState(state), attempt.getStateHash())) {
            throw failure("WHATSAPP_AUTH_STATE_INVALID", HttpStatus.NOT_FOUND);
        }
        if (!userId.equals(attempt.getUserId())) {
            throw failure("WHATSAPP_AUTH_USER_MISMATCH", HttpStatus.FORBIDDEN);
        }
        if (!"META_COMPLETED".equals(attempt.getStatus())
                || !clock.instant().isBefore(attempt.getExpiresAt())) {
            throw failure("WHATSAPP_AUTH_STATE_EXPIRED", HttpStatus.CONFLICT);
        }
        if (!"EMPLOYEE_BUSINESS_APP".equals(attempt.getOnboardingMode())
                || isBlank(attempt.getCompletedWabaId())) {
            throw failure("WHATSAPP_ONBOARDING_MODE_INVALID", HttpStatus.CONFLICT);
        }
    }

    private void verifyAttemptIdentity(UUID userId, String state,
                                       WhatsAppAuthorizationAttemptEntity attempt) {
        if (attempt == null || !constantTimeEquals(hashState(state), attempt.getStateHash())) {
            throw failure("WHATSAPP_AUTH_STATE_INVALID", HttpStatus.NOT_FOUND);
        }
        if (!userId.equals(attempt.getUserId())) {
            throw failure("WHATSAPP_AUTH_USER_MISMATCH", HttpStatus.FORBIDDEN);
        }
    }

    private void verifyPendingAttempt(UUID userId, String state, String event,
                                      WhatsAppAuthorizationAttemptEntity attempt) {
        verifyAttemptIdentity(userId, state, attempt);
        if (!"PENDING".equals(attempt.getStatus()) || !clock.instant().isBefore(attempt.getExpiresAt())) {
            throw failure("WHATSAPP_AUTH_STATE_EXPIRED", HttpStatus.CONFLICT);
        }
        if (!"FINISH".equalsIgnoreCase(required(event, "WHATSAPP_EMBEDDED_SIGNUP_EVENT_REQUIRED"))) {
            throw failure("WHATSAPP_EMBEDDED_SIGNUP_NOT_FINISHED", HttpStatus.CONFLICT);
        }
    }

    private WhatsAppProviderScopeEntity ensureOwnedBusinessAppScope(UUID userId, String wabaId,
                                                                      WhatsAppOnboardingGateway.BoundScope bound) {
        if (bound == null || bound.custSpaceId() == null || bound.custSpaceId().isBlank()
                || !wabaId.equals(bound.wabaId())) {
            throw failure("WHATSAPP_PROVIDER_SCOPE_NOT_READY", HttpStatus.CONFLICT);
        }
        WhatsAppProviderScopeEntity existing = scopes.findOwnedBusinessAppScope(PROVIDER, wabaId, userId);
        if (existing != null) {
            if (existing.getId() == null || !"READY".equals(existing.getStatus())) {
                throw failure("WHATSAPP_PROVIDER_SCOPE_NOT_READY", HttpStatus.CONFLICT);
            }
            return existing;
        }

        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setId(UUID.randomUUID());
        scope.setProvider(PROVIDER);
        scope.setExternalScopeId(bound.custSpaceId());
        scope.setWabaId(wabaId);
        scope.setScopeType("EMPLOYEE_BUSINESS_APP");
        scope.setOwnerUserId(userId);
        scope.setIdentityStatus("IDENTITY_VERIFIED");
        scope.setStatus("READY");
        scope.setEncryptedConfig("{}");
        if (scopes.insert(scope) != 1) {
            WhatsAppProviderScopeEntity concurrent = scopes.findOwnedBusinessAppScope(PROVIDER, wabaId, userId);
            if (concurrent == null || concurrent.getId() == null || !"READY".equals(concurrent.getStatus())) {
                throw failure("WHATSAPP_PROVIDER_SCOPE_NOT_READY", HttpStatus.CONFLICT);
            }
            return concurrent;
        }
        return scope;
    }

    private WhatsAppProviderScopeEntity ensureEnterpriseApiScope(String wabaId,
                                                                   WhatsAppOnboardingGateway.BoundScope bound) {
        if (bound == null || isBlank(bound.custSpaceId()) || !wabaId.equals(bound.wabaId())) {
            throw failure("WHATSAPP_PROVIDER_SCOPE_NOT_READY", HttpStatus.CONFLICT);
        }
        WhatsAppProviderScopeEntity matchingScope = scopes.findByProviderAndWabaId(PROVIDER, wabaId);
        if (matchingScope != null) {
            if (matchingScope.getId() == null || !"READY".equals(matchingScope.getStatus())
                    || !"ENTERPRISE_API".equals(matchingScope.getScopeType())
                    || matchingScope.getOwnerUserId() != null) {
                throw failure("WHATSAPP_PROVIDER_SCOPE_CONFLICT", HttpStatus.CONFLICT);
            }
            return matchingScope;
        }

        WhatsAppProviderScopeEntity previousEnterpriseScope = scopes.findEnterpriseApiScope(PROVIDER);
        if (previousEnterpriseScope != null && previousEnterpriseScope.getId() != null
                && !wabaId.equals(previousEnterpriseScope.getWabaId())) {
            List<ChannelAccountEntity> activeAccounts = accounts.findActiveByScope(previousEnterpriseScope.getId());
            if (activeAccounts != null && !activeAccounts.isEmpty()) {
                throw failure("WHATSAPP_ENTERPRISE_WABA_REPLACEMENT_CONFLICT", HttpStatus.CONFLICT);
            }
            if (scopes.blockEnterpriseApiScope(previousEnterpriseScope.getId()) != 1) {
                throw failure("WHATSAPP_PROVIDER_SCOPE_CONFLICT", HttpStatus.CONFLICT);
            }
        }

        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setId(UUID.randomUUID());
        scope.setProvider(PROVIDER);
        scope.setExternalScopeId(bound.custSpaceId());
        scope.setWabaId(wabaId);
        scope.setScopeType("ENTERPRISE_API");
        scope.setIdentityStatus("IDENTITY_VERIFIED");
        scope.setStatus("READY");
        scope.setEncryptedConfig("{}");
        if (scopes.insert(scope) == 1) {
            return scope;
        }
        WhatsAppProviderScopeEntity concurrent = scopes.findByProviderAndWabaId(PROVIDER, wabaId);
        if (concurrent == null || concurrent.getId() == null || !"READY".equals(concurrent.getStatus())
                || !"ENTERPRISE_API".equals(concurrent.getScopeType())
                || concurrent.getOwnerUserId() != null) {
            throw failure("WHATSAPP_PROVIDER_SCOPE_CONFLICT", HttpStatus.CONFLICT);
        }
        return concurrent;
    }

    private static ChannelAccountEntity newChannelAccount(UUID userId, WhatsAppProviderScopeEntity scope,
                                                           String phoneNumber,
                                                           WhatsAppOnboardingGateway.ProviderPhone providerPhone,
                                                           String onboardingMode,
                                                           String accountName,
                                                           String accountRemark) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setOwnerUserId(userId);
        account.setChannelType("chatapp");
        account.setName(isBlank(accountName)
                ? (isBlank(providerPhone.verifiedName()) ? phoneNumber : providerPhone.verifiedName())
                : accountName.trim());
        account.setRemark(normalizeOptional(accountRemark));
        account.setAccountIdentifier(phoneNumber);
        account.setAccountIdentifierNormalized(phoneNumber);
        account.setAuthStatus("active");
        account.setSyncStatus("idle");
        account.setProviderScopeId(scope.getId());
        account.setOnboardingMode(onboardingMode);
        account.setPhoneVerificationStatus(providerPhone.verificationStatus());
        account.setProviderPhoneStatus(providerPhone.providerStatus());
        account.setEncryptedConfig(scope.getEncryptedConfig() == null ? "{}" : scope.getEncryptedConfig());
        return account;
    }

    static String hashState(String state) {
        if (state == null) return "";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(state.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                (right == null ? "" : right).getBytes(StandardCharsets.UTF_8));
    }

    private static String randomState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String required(String value, String code) {
        if (value == null || value.isBlank()) throw failure(code, HttpStatus.BAD_REQUEST);
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizePhone(String value) {
        String digits = required(value, "WHATSAPP_PHONE_REQUIRED").replaceAll("\\D", "");
        if (digits.length() < 6 || digits.length() > 20) {
            throw failure("WHATSAPP_PHONE_INVALID", HttpStatus.BAD_REQUEST);
        }
        return digits;
    }

    private WhatsAppProviderScopeEntity requireScope() {
        WhatsAppProviderScopeEntity scope = scopes.findEnterpriseScope(PROVIDER);
        if (scope == null || scope.getId() == null || scope.getExternalScopeId() == null
                || scope.getExternalScopeId().isBlank() || !"READY".equals(scope.getStatus())) {
            throw failure("WHATSAPP_PROVIDER_SCOPE_NOT_READY", HttpStatus.CONFLICT);
        }
        return scope;
    }

    private WhatsAppProviderScopeEntity ensureScope(UUID userId, String wabaId, String onboardingMode) {
        WhatsAppOnboardingGateway.BoundScope bound = gateway.bindWaba(wabaId);
        if (bound == null || bound.custSpaceId() == null || bound.custSpaceId().isBlank()
                || bound.wabaId() == null || !wabaId.equals(bound.wabaId())) {
            throw failure("WHATSAPP_PROVIDER_SCOPE_NOT_READY", HttpStatus.CONFLICT);
        }
        String scopeType = "BUSINESS_APP_COEXISTENCE".equals(onboardingMode)
                ? "EMPLOYEE_BUSINESS_APP" : "ENTERPRISE_API";
        UUID owner = "EMPLOYEE_BUSINESS_APP".equals(scopeType) ? userId : null;
        scopes.insertBound(PROVIDER, bound.custSpaceId(), bound.wabaId(), scopeType, owner);
        WhatsAppProviderScopeEntity scope = "EMPLOYEE_BUSINESS_APP".equals(scopeType)
                ? scopes.findOwnedBusinessAppScope(PROVIDER, bound.wabaId(), userId)
                : scopes.findByProviderAndWabaId(PROVIDER, bound.wabaId());
        if (scope == null || scope.getId() == null || !"READY".equals(scope.getStatus())
                || !scopeType.equalsIgnoreCase(scope.getScopeType())
                || (owner != null && !owner.equals(scope.getOwnerUserId()))) {
            throw failure("WHATSAPP_PROVIDER_SCOPE_NOT_READY", HttpStatus.CONFLICT);
        }
        return scope;
    }

    private static boolean isUsable(String providerStatus, String verificationStatus) {
        return "ACTIVE".equalsIgnoreCase(providerStatus)
                && "VERIFIED".equalsIgnoreCase(verificationStatus);
    }

    private static boolean isEmptyConfig(String config) {
        return config == null || config.isBlank() || "{}".equals(config.trim());
    }

    private static String normalizeOnboardingMode(String value) {
        String mode = required(value, "WHATSAPP_ONBOARDING_MODE_REQUIRED").toUpperCase();
        if ("BUSINESS_APP_COEXISTENCE".equals(mode)) return "EMPLOYEE_BUSINESS_APP";
        if ("API_ONLY".equals(mode)) return "ADMIN_API_WABA";
        if (!"EMPLOYEE_BUSINESS_APP".equals(mode) && !"ADMIN_API_WABA".equals(mode)) {
            throw failure("WHATSAPP_ONBOARDING_MODE_INVALID", HttpStatus.BAD_REQUEST);
        }
        return mode;
    }

    private boolean isAdmin(UUID userId) {
        return roles != null && roles.userHasRole(userId, "admin");
    }

    private void requireCapability() {
        if (capabilityGate != null) capabilityGate.requireReady();
    }

    private static String last4(String phoneNumber) {
        return phoneNumber.substring(Math.max(0, phoneNumber.length() - 4));
    }

    private static String maskedPhone(String phoneNumber) {
        return "***" + last4(phoneNumber);
    }

    private static WhatsAppAuthorizationException failure(String code, HttpStatus status) {
        return new WhatsAppAuthorizationException(code, status);
    }

    public record AttemptProjection(UUID attemptId, String state, Instant expiresAt,
                                    WhatsAppOnboardingGateway.StartupProfile startupProfile) {
        public AttemptProjection(UUID attemptId, String state, Instant expiresAt) {
            this(attemptId, state, expiresAt, null);
        }
    }
    public record SelfServiceCompletionCommand(UUID attemptId, String state, String event, String code,
                                               String wabaId, String phoneNumberId) { }
    public record PhoneSelectionCommand(UUID attemptId, String state, String candidateId) { }
    public record PhoneCandidateProjection(String candidateId, String maskedPhone) { }
    public record CompletionProjection(UUID accountId, String phoneNumberLast4, String onboardingMode,
                                       boolean phoneSelectionRequired,
                                       List<PhoneCandidateProjection> phoneCandidates) {
        public CompletionProjection(UUID accountId, String phoneNumberLast4, String onboardingMode) {
            this(accountId, phoneNumberLast4, onboardingMode, false, List.of());
        }
    }
}
