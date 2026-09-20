package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AdminWhatsAppAccountSyncService {
    private static final String PROVIDER = "ALIYUN_CAMS";
    private static final Logger log = LoggerFactory.getLogger(AdminWhatsAppAccountSyncService.class);

    /** Mirrors ck_channel_accounts_provider_phone_status. */
    private static final Set<String> STORABLE_PROVIDER_STATUSES =
            Set.of("PENDING", "ACTIVE", "DISABLED", "FAILED");
    /** Mirrors ck_channel_accounts_phone_verification_status. */
    private static final Set<String> STORABLE_VERIFICATION_STATUSES =
            Set.of("PENDING", "CODE_SENT", "VERIFIED", "FAILED");

    /**
     * Used by the constructors that take plain collaborators and no transaction manager: with no
     * transaction there is nothing to roll the projection back into, so the body simply runs as-is.
     */
    private static final TransactionOperations NO_TRANSACTION = TransactionOperations.withoutTransaction();

    private final AppConfig config;
    private final WhatsAppOnboardingGateway gateway;
    private final WhatsAppProviderScopeMapper scopes;
    private final ChannelAccountMapper accounts;
    private final RoleMapper roles;
    private final WhatsAppCamsConfigService camsConfig;
    private final TransactionOperations transactions;

    public AdminWhatsAppAccountSyncService(AppConfig config,
                                           WhatsAppOnboardingGateway gateway,
                                           WhatsAppProviderScopeMapper scopes,
                                           ChannelAccountMapper accounts) {
        this(config, gateway, scopes, accounts, null);
    }

    public AdminWhatsAppAccountSyncService(AppConfig config,
                                           WhatsAppOnboardingGateway gateway,
                                           WhatsAppProviderScopeMapper scopes,
                                           ChannelAccountMapper accounts,
                                           RoleMapper roles) {
        this(config, gateway, scopes, accounts, roles, null);
    }

    public AdminWhatsAppAccountSyncService(AppConfig config,
                                           WhatsAppOnboardingGateway gateway,
                                           WhatsAppProviderScopeMapper scopes,
                                           ChannelAccountMapper accounts,
                                           RoleMapper roles,
                                           WhatsAppCamsConfigService camsConfig) {
        this(config, gateway, scopes, accounts, roles, camsConfig, NO_TRANSACTION);
    }

    @Autowired
    public AdminWhatsAppAccountSyncService(AppConfig config,
                                           WhatsAppOnboardingGateway gateway,
                                           WhatsAppProviderScopeMapper scopes,
                                           ChannelAccountMapper accounts,
                                           RoleMapper roles,
                                           WhatsAppCamsConfigService camsConfig,
                                           PlatformTransactionManager transactionManager) {
        this(config, gateway, scopes, accounts, roles, camsConfig, new TransactionTemplate(transactionManager));
    }

    private AdminWhatsAppAccountSyncService(AppConfig config,
                                            WhatsAppOnboardingGateway gateway,
                                            WhatsAppProviderScopeMapper scopes,
                                            ChannelAccountMapper accounts,
                                            RoleMapper roles,
                                            WhatsAppCamsConfigService camsConfig,
                                            TransactionOperations transactions) {
        this.config = config;
        this.gateway = gateway;
        this.scopes = scopes;
        this.accounts = accounts;
        this.roles = roles;
        this.camsConfig = camsConfig;
        this.transactions = transactions;
    }

    /**
     * Resolves the legacy single-scope configuration, then hands over to {@link #sync(UUID, UUID)},
     * which owns the transaction and must stay outside of it so its FAILED projection is written
     * after the rollback rather than undone by it.
     */
    public SyncResult sync(UUID actorId) {
        WhatsAppProviderScopeEntity legacyScope = transactions.execute(status -> {
            if (camsConfig == null) {
                String custSpaceId = config.custSpaceId();
                scopes.upsertAdminConfigured(PROVIDER, custSpaceId, gateway.encryptedProviderConfig());
                return scopes.findByProviderAndExternalScopeId(PROVIDER, custSpaceId);
            }
            return camsConfig.resolveScopeForSync();
        });
        return sync(actorId, legacyScope == null ? null : legacyScope.getId());
    }

    /**
     * Runs the sync in one transaction and, when the provider call fails, records the FAILED
     * projection <em>after</em> that transaction has completed (with a rollback) and released its
     * connection.
     *
     * <p>Writing it from inside the transaction would never persist: the exception is rethrown and
     * the enclosing rollback undoes the {@code UPDATE} together with everything else. Deferring it
     * to a {@code TransactionSynchronization} would leave the write on the transaction's own
     * connection, uncommitted whenever the pool hands out connections with auto-commit disabled.
     * A {@code REQUIRES_NEW} helper called from here would self-deadlock instead, because
     * {@link WhatsAppCamsConfigService#resolveScopeForSync()} can upsert this very scope row inside
     * the outer transaction. Here, by contrast, the single {@code @Update} runs with no active
     * transaction, so it commits on its own no matter how the pool is configured.
     */
    public SyncResult sync(UUID actorId, UUID scopeId) {
        AtomicReference<UUID> failedScopeId = new AtomicReference<>();
        try {
            return transactions.execute(status -> performSync(actorId, scopeId, failedScopeId));
        } catch (RuntimeException error) {
            // The guard is unchanged: only a scope-based failure with a configured CAMS service is
            // projected, and only the gateway marks one below.
            UUID scopeToMark = failedScopeId.get();
            if (scopeToMark != null && camsConfig != null) {
                scopes.touchSyncResult(scopeToMark, "FAILED",
                        error instanceof WhatsAppAuthorizationException auth
                                ? auth.code() : "WHATSAPP_CAMS_SYNC_FAILED");
            }
            throw error;
        }
    }

    /** The transactional body. It must not write the FAILED projection itself; see {@link #sync(UUID, UUID)}. */
    private SyncResult performSync(UUID actorId, UUID scopeId, AtomicReference<UUID> failedScopeId) {
        if (roles != null && (actorId == null || !roles.userHasRole(actorId, "admin"))) {
            throw failure("WHATSAPP_ADMIN_REQUIRED", HttpStatus.FORBIDDEN);
        }
        WhatsAppProviderScopeEntity scope = scopeId == null
                ? (camsConfig == null
                    ? scopes.findByProviderAndExternalScopeId(PROVIDER, config.custSpaceId())
                    : camsConfig.resolveScopeForSync())
                : (camsConfig == null
                    ? scopes.findByProviderAndExternalScopeId(PROVIDER, config.custSpaceId())
                    : camsConfig.requireReadyScope(scopeId));
        String custSpaceId = scope == null ? config.custSpaceId() : scope.getExternalScopeId();
        if (scope == null || custSpaceId == null || custSpaceId.isBlank()) throw failure("WHATSAPP_CAMS_SYNC_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        // Readiness is the only gate: an administrator administers a Business App space from the
        // same page, so its numbers must be importable too.
        if (scope == null || scope.getId() == null || !"READY".equalsIgnoreCase(scope.getStatus())) {
            throw failure("WHATSAPP_CAMS_SYNC_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        }

        List<ChannelAccountEntity> localAccounts = (scopeId == null || camsConfig == null)
                ? accounts.findAllWhatsAppForSync() : accounts.findAllWhatsAppByScope(scope.getId());
        if (localAccounts == null) localAccounts = List.of();
        Map<String, ChannelAccountEntity> localByPhone = new HashMap<>();
        for (ChannelAccountEntity account : localAccounts) {
            String normalized = normalize(account.getAccountIdentifierNormalized(),
                    account.getAccountIdentifier());
            if (!normalized.isBlank()) localByPhone.putIfAbsent(normalized, account);
        }

        Instant syncedAt = Instant.now();
        List<WhatsAppOnboardingGateway.ProviderPhone> providerPhones;
        try {
            providerPhones = camsConfig == null
                    ? gateway.syncConfiguredPhoneNumbers() : gateway.syncConfiguredPhoneNumbers(scope);
        } catch (RuntimeException error) {
            failedScopeId.set(scope.getId());
            throw error;
        }
        if (providerPhones == null) providerPhones = List.of();

        accounts.markWhatsAppProviderUnavailable(scope.getId(), syncedAt);

        int imported = 0;
        int refreshed = 0;
        Set<String> usablePhones = new HashSet<>();
        List<ProviderPhoneReport> reported = new ArrayList<>();
        for (WhatsAppOnboardingGateway.ProviderPhone providerPhone : providerPhones) {
            if (providerPhone == null) continue;
            String phone = normalize(providerPhone.normalizedPhoneNumber(), null);
            boolean usable = isUsable(providerPhone);
            log.info("whatsapp.cams.sync scope={} phone={} providerStatus={} verificationStatus={} usable={}",
                    scope.getExternalScopeId(), maskPhone(phone), providerPhone.providerStatus(),
                    providerPhone.verificationStatus(), usable);
            if (!phone.isBlank()) {
                reported.add(new ProviderPhoneReport(maskPhone(phone), providerPhone.providerStatus(),
                        providerPhone.verificationStatus(), usable));
            }
            ChannelAccountEntity existing = phone.isBlank() ? null : localByPhone.get(phone);
            if (!usable) {
                // The provider did answer for this number, so the row keeps that answer rather than
                // the blanket UNKNOWN written above, which now only means "the provider said nothing".
                if (existing != null) {
                    accounts.recordProviderPhoneStatus(scope.getId(), phone,
                            storableProviderStatus(providerPhone.providerStatus()),
                            storableVerificationStatus(providerPhone.verificationStatus()), syncedAt);
                }
                continue;
            }
            if (phone.isBlank()) continue;
            usablePhones.add(phone);
            String encryptedConfig = gateway.encryptedProviderConfig(phone);
            String name = providerPhone.verifiedName() == null || providerPhone.verifiedName().isBlank()
                    ? "WhatsApp 账号" : providerPhone.verifiedName().trim();
            if (existing == null) {
                ChannelAccountEntity entity = new ChannelAccountEntity();
                entity.setId(UUID.randomUUID());
                entity.setName(name);
                entity.setAccountIdentifier(phone);
                entity.setAccountIdentifierNormalized(phone);
                entity.setPhoneVerificationStatus(storableVerificationStatus(providerPhone.verificationStatus()));
                entity.setProviderPhoneStatus(storableProviderStatus(providerPhone.providerStatus()));
                entity.setEncryptedConfig(encryptedConfig);
                accounts.upsertAdminSynced(entity, scope.getId(), syncedAt);
                imported++;
            } else {
                accounts.refreshAdminSynced(existing.getId(), scope.getId(), name, phone, phone,
                        storableVerificationStatus(providerPhone.verificationStatus()),
                        storableProviderStatus(providerPhone.providerStatus()), encryptedConfig, syncedAt);
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

        log.info("whatsapp.cams.sync scope={} returned={} usable={} imported={} refreshed={} unavailable={}",
                scope.getExternalScopeId(), providerPhones.size(), usablePhones.size(), imported,
                refreshed, unavailable);

        List<ChannelAccountEntity> projected = accounts.findAllWhatsAppByScope(scope.getId());
        if (projected == null) projected = List.of();
        if (scope.getId() != null) scopes.touchSyncResult(scope.getId(), "SUCCESS", null);
        return new SyncResult(imported, refreshed, unavailable,
                projected.stream().map(AdminWhatsAppAccountSyncService::project).toList(), reported);
    }

    /**
     * Sendability is the provider's call: a number CAMS reports ACTIVE is connected and can send,
     * whatever its code verification says. Code verification is a step of the onboarding flow, and
     * an admin-synced number was configured in CAMS by the administrator in the first place, so an
     * expired code (CAMS reports {@code EXPIRED}, which the column cannot even hold) must not cost
     * the CRM a working number.
     */
    private static boolean isUsable(WhatsAppOnboardingGateway.ProviderPhone phone) {
        return phone != null && "ACTIVE".equalsIgnoreCase(phone.providerStatus());
    }

    /**
     * The two status columns accept only the CRM's own vocabulary, and CAMS answers with values
     * outside it (an expired code, for one, is {@code EXPIRED}). Whatever does not fit is stored as
     * {@code UNKNOWN} / null — the raw answer survives in {@link ProviderPhoneReport}, which is
     * what the page shows.
     */
    private static String storableProviderStatus(String providerStatus) {
        String value = upper(providerStatus);
        return STORABLE_PROVIDER_STATUSES.contains(value) ? value : "UNKNOWN";
    }

    private static String storableVerificationStatus(String verificationStatus) {
        String value = upper(verificationStatus);
        return STORABLE_VERIFICATION_STATUSES.contains(value) ? value : null;
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
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
                             List<AccountProjection> accounts,
                             List<ProviderPhoneReport> providerPhones) { }

    public record AccountProjection(UUID accountId, UUID ownerUserId, String maskedPhone,
                                    String name, String providerStatus,
                                    String verificationStatus, Instant lastSyncedAt) { }

    /**
     * What CAMS answered for one number, including the numbers the sync refused to import. Without
     * it a space whose only number is not ACTIVE looks simply empty, with no way to tell an
     * unusable provider answer from a number CAMS never reported at all.
     */
    public record ProviderPhoneReport(String maskedPhone, String providerStatus,
                                      String verificationStatus, boolean accepted) { }
}
