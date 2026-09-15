package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AdminWhatsAppAccountSyncService {
    private static final String PROVIDER = "ALIYUN_CAMS";

    private final AppConfig config;
    private final WhatsAppOnboardingGateway gateway;
    private final WhatsAppProviderScopeMapper scopes;
    private final ChannelAccountMapper accounts;
    private final RoleMapper roles;

    public AdminWhatsAppAccountSyncService(AppConfig config,
                                           WhatsAppOnboardingGateway gateway,
                                           WhatsAppProviderScopeMapper scopes,
                                           ChannelAccountMapper accounts) {
        this(config, gateway, scopes, accounts, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdminWhatsAppAccountSyncService(AppConfig config,
                                           WhatsAppOnboardingGateway gateway,
                                           WhatsAppProviderScopeMapper scopes,
                                           ChannelAccountMapper accounts,
                                           RoleMapper roles) {
        this.config = config;
        this.gateway = gateway;
        this.scopes = scopes;
        this.accounts = accounts;
        this.roles = roles;
    }

    @Transactional
    public SyncResult sync(UUID actorId) {
        if (roles != null && (actorId == null || !roles.userHasRole(actorId, "admin"))) {
            throw failure("WHATSAPP_ADMIN_REQUIRED", HttpStatus.FORBIDDEN);
        }
        String custSpaceId = config.custSpaceId();
        if (custSpaceId == null || custSpaceId.isBlank()) {
            throw failure("WHATSAPP_CAMS_SYNC_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        }

        String scopeConfig = gateway.encryptedProviderConfig();
        scopes.upsertAdminConfigured(PROVIDER, custSpaceId.trim(), scopeConfig);
        WhatsAppProviderScopeEntity scope =
                scopes.findByProviderAndExternalScopeId(PROVIDER, custSpaceId.trim());
        if (scope == null || scope.getId() == null || !"READY".equalsIgnoreCase(scope.getStatus())
                || !"ENTERPRISE_API".equalsIgnoreCase(scope.getScopeType())) {
            throw failure("WHATSAPP_CAMS_SYNC_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        }

        List<ChannelAccountEntity> localAccounts = accounts.findAllWhatsAppForSync();
        if (localAccounts == null) localAccounts = List.of();
        Map<String, ChannelAccountEntity> localByPhone = new HashMap<>();
        for (ChannelAccountEntity account : localAccounts) {
            String normalized = normalize(account.getAccountIdentifierNormalized(),
                    account.getAccountIdentifier());
            if (!normalized.isBlank()) localByPhone.putIfAbsent(normalized, account);
        }

        Instant syncedAt = Instant.now();
        List<WhatsAppOnboardingGateway.ProviderPhone> providerPhones =
                gateway.syncConfiguredPhoneNumbers();
        if (providerPhones == null) providerPhones = List.of();

        accounts.markWhatsAppProviderUnavailable(scope.getId(), syncedAt);

        int imported = 0;
        int refreshed = 0;
        Set<String> usablePhones = new HashSet<>();
        for (WhatsAppOnboardingGateway.ProviderPhone providerPhone : providerPhones) {
            if (!isUsable(providerPhone)) continue;
            String phone = normalize(providerPhone.normalizedPhoneNumber(), null);
            if (phone.isBlank()) continue;
            usablePhones.add(phone);
            String encryptedConfig = gateway.encryptedProviderConfig(phone);
            ChannelAccountEntity existing = localByPhone.get(phone);
            String name = providerPhone.verifiedName() == null || providerPhone.verifiedName().isBlank()
                    ? "WhatsApp 账号" : providerPhone.verifiedName().trim();
            if (existing == null) {
                ChannelAccountEntity entity = new ChannelAccountEntity();
                entity.setId(UUID.randomUUID());
                entity.setName(name);
                entity.setAccountIdentifier(phone);
                entity.setAccountIdentifierNormalized(phone);
                entity.setPhoneVerificationStatus("VERIFIED");
                entity.setProviderPhoneStatus("ACTIVE");
                entity.setEncryptedConfig(encryptedConfig);
                accounts.upsertAdminSynced(entity, scope.getId(), syncedAt);
                imported++;
            } else {
                accounts.refreshAdminSynced(existing.getId(), scope.getId(), name, phone, phone,
                        "VERIFIED", "ACTIVE", encryptedConfig, syncedAt);
                refreshed++;
            }
        }

        int unavailable = 0;
        for (ChannelAccountEntity account : localAccounts) {
            String phone = normalize(account.getAccountIdentifierNormalized(), account.getAccountIdentifier());
            if (!phone.isBlank() && !usablePhones.contains(phone)
                    && (account.getProviderScopeId() == null
                    || scope.getId().equals(account.getProviderScopeId()))) {
                unavailable++;
            }
        }

        List<ChannelAccountEntity> projected = accounts.findAllWhatsAppByScope(scope.getId());
        if (projected == null) projected = List.of();
        return new SyncResult(imported, refreshed, unavailable,
                projected.stream().map(AdminWhatsAppAccountSyncService::project).toList());
    }

    private static boolean isUsable(WhatsAppOnboardingGateway.ProviderPhone phone) {
        return phone != null
                && "ACTIVE".equalsIgnoreCase(phone.providerStatus())
                && "VERIFIED".equalsIgnoreCase(phone.verificationStatus());
    }

    private static String normalize(String normalized, String fallback) {
        String value = normalized == null || normalized.isBlank() ? fallback : normalized;
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static AccountProjection project(ChannelAccountEntity account) {
        return new AccountProjection(account.getId(), account.getOwnerUserId(),
                maskPhone(account.getAccountIdentifier()), account.getName(),
                account.getProviderPhoneStatus(), account.getPhoneVerificationStatus(),
                account.getLastSyncedAt());
    }

    private static String maskPhone(String phone) {
        String value = phone == null ? "" : phone.trim();
        if (value.length() <= 4) return value;
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }

    private static WhatsAppAuthorizationException failure(String code, HttpStatus status) {
        return new WhatsAppAuthorizationException(code, status);
    }

    public record SyncResult(int importedCount, int refreshedCount, int unavailableCount,
                             List<AccountProjection> accounts) { }

    public record AccountProjection(UUID accountId, UUID ownerUserId, String maskedPhone,
                                    String name, String providerStatus,
                                    String verificationStatus, Instant lastSyncedAt) { }
}
