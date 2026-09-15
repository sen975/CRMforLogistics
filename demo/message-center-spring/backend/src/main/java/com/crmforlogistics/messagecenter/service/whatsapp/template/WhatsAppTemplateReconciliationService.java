package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import com.crmforlogistics.messagecenter.entity.TemplateOperationEntity;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateChangeRequestMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateOperationMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ProviderTemplatePage;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ProviderTemplateSummary;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ReviewStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WhatsAppTemplateReconciliationService {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;
    private static final int CLAIM_LIMIT = 20;
    private static final int MAX_RECONCILIATIONS = 10;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final Duration RECONCILIATION_WINDOW = Duration.ofHours(24);
    private static final Duration MAX_BACKOFF = Duration.ofHours(3);

    private final WhatsAppTemplateGateway gateway;
    private final ChannelAccountMapper accountMapper;
    private final TemplateMapper templateMapper;
    private final TemplateOperationMapper operationMapper;
    private final TemplateChangeRequestMapper changeRequestMapper;
    private final TemplateMediaAssetMapper mediaMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ConcurrentHashMap<String, CompletableFuture<SyncResult>> syncs = new ConcurrentHashMap<>();

    public WhatsAppTemplateReconciliationService(WhatsAppTemplateGateway gateway,
                                                 ChannelAccountMapper accountMapper,
                                                 TemplateMapper templateMapper,
                                                 TemplateOperationMapper operationMapper,
                                                 TemplateChangeRequestMapper changeRequestMapper,
                                                 TemplateMediaAssetMapper mediaMapper,
                                                 ObjectMapper objectMapper,
                                                 Clock clock) {
        this.gateway = Objects.requireNonNull(gateway);
        this.accountMapper = Objects.requireNonNull(accountMapper);
        this.templateMapper = Objects.requireNonNull(templateMapper);
        this.operationMapper = Objects.requireNonNull(operationMapper);
        this.changeRequestMapper = Objects.requireNonNull(changeRequestMapper);
        this.mediaMapper = Objects.requireNonNull(mediaMapper);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    public SyncResult syncScope(UUID providerScopeId, UUID credentialAccountId) {
        UUID scopeId = Objects.requireNonNull(providerScopeId);
        UUID accountId = Objects.requireNonNull(credentialAccountId);
        return sync("scope:" + scopeId, scopeId, accountId, false);
    }

    public SyncResult syncPrivateAccount(UUID accountId) {
        UUID id = Objects.requireNonNull(accountId);
        ChannelAccountEntity account = accountMapper.selectById(id);
        if (account == null || !"BUSINESS_APP_COEXISTENCE".equalsIgnoreCase(account.getOnboardingMode())
                || account.getProviderScopeId() == null) {
            throw new WhatsAppTemplateException("WHATSAPP_TEMPLATE_DOMAIN_MISMATCH",
                    org.springframework.http.HttpStatus.CONFLICT,
                    "当前账号不是可同步的 Business App 私有模板账号", Map.of(), null, false);
        }
        return sync("account:" + id, account.getProviderScopeId(), id, true);
    }

    private SyncResult sync(String key, UUID providerScopeId, UUID accountId, boolean privateDomain) {
        CompletableFuture<SyncResult> current = new CompletableFuture<>();
        CompletableFuture<SyncResult> existing = syncs.putIfAbsent(key, current);
        if (existing != null) {
            return await(existing);
        }
        try {
            SyncResult result = doSync(providerScopeId, accountId, privateDomain);
            current.complete(result);
            return result;
        } catch (RuntimeException | Error error) {
            current.completeExceptionally(error);
            throw error;
        } finally {
            syncs.remove(key, current);
        }
    }

    private SyncResult doSync(UUID providerScopeId, UUID accountId, boolean privateDomain) {
        Instant now = now();
        ProviderSnapshot provider;
        try {
            provider = loadProviderSnapshot(accountId);
        } catch (RuntimeException error) {
            return new SyncResult(0, 0, 0, false);
        }

        Map<TemplateKey, TemplateEntity> existing = existingTemplates(providerScopeId, accountId, privateDomain);
        int changed = 0;
        for (ProviderTemplateSummary summary : provider.items()) {
            TemplateKey key = key(summary.templateCode(), summary.language());
            TemplateEntity current = existing.get(key);
            try {
                Optional<TemplateSnapshot> detail = verifiedDetail(accountId, summary.templateCode(),
                        summary.language(), gateway.detail(accountId, summary.templateCode(), summary.language()));
                if (detail.isPresent()) {
                    persistSnapshot(detail.orElseThrow(), current, providerScopeId, accountId, now, privateDomain);
                } else {
                    persistSummary(summary, current, providerScopeId, accountId, now, privateDomain);
                }
                changed++;
            } catch (RuntimeException error) {
                // The list state is authoritative for review status, but not for components.
                persistSummary(summary, current, providerScopeId, accountId, now, privateDomain);
                changed++;
            }
            existing.remove(key);
        }
        if (provider.complete()) {
            for (TemplateEntity missing : existing.values()) {
                missing.setDeletedAt(now);
                missing.setAllowSend(false);
                missing.setUpdatedAt(now);
                templateMapper.updateById(missing);
                changed++;
            }
        }
        return new SyncResult(provider.pages(), provider.items().size(), changed, provider.complete());
    }

    private static SyncResult await(CompletableFuture<SyncResult> inFlight) {
        try {
            return inFlight.join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (error.getCause() instanceof Error cause) {
                throw cause;
            }
            throw error;
        }
    }

    public int reconcileUnknown(String workerId) {
        Instant now = now();
        List<TemplateOperationEntity> claimed = operationMapper.claimUnknown(
                Objects.requireNonNull(workerId), now, now.plus(LEASE_DURATION), CLAIM_LIMIT);
        int resolved = 0;
        for (TemplateOperationEntity operation : claimed) {
            if ("RETIRED".equals(operation.getOperationType())) {
                continue;
            }
            if (expired(operation, now)) {
                fail(operation, "RECONCILIATION_WINDOW_EXPIRED", "Reconciliation exceeded the 24 hour window", now);
                continue;
            }
            try {
                ProviderSnapshot provider = loadProviderSnapshot(operation.getChannelAccountId());
                if (!provider.complete()) {
                    retry(operation, "RECONCILIATION_NOT_CONFIRMED", "Provider pagination is incomplete", now);
                    continue;
                }
                if (reconcile(operation, provider.items(), now)) {
                    resolved++;
                }
            } catch (RuntimeException error) {
                retry(operation, "RECONCILIATION_NOT_CONFIRMED", "Provider state is not yet conclusive", now);
            }
        }
        return resolved;
    }

    private boolean reconcile(TemplateOperationEntity operation, List<ProviderTemplateSummary> summaries, Instant now) {
        return switch (operation.getOperationType()) {
            case "CREATE" -> reconcileCreate(operation, summaries, now);
            case "MODIFY", "SET_SEND_PERMISSION" -> reconcileExisting(operation, summaries, now);
            case "DELETE" -> reconcileDelete(operation, summaries, now);
            case "RETIRED" -> false;
            default -> {
                retry(operation, "RECONCILIATION_NOT_CONFIRMED", "Unsupported operation type", now);
                yield false;
            }
        };
    }

    private boolean reconcileCreate(TemplateOperationEntity operation,
                                    List<ProviderTemplateSummary> summaries,
                                    Instant now) {
        TemplateCommand command;
        try {
            command = objectMapper.readValue(operation.getRequestedSnapshotJsonb(), TemplateCommand.class);
        } catch (JsonProcessingException error) {
            fail(operation, "RECONCILIATION_REQUEST_INVALID", "Stored requested snapshot cannot be read", now);
            return false;
        }
        List<ProviderTemplateSummary> matches = summaries.stream()
                .filter(summary -> same(summary.templateName(), command.name())
                        && same(summary.language(), command.language()))
                .toList();
        if (matches.size() != 1) {
            retry(operation, matches.size() > 1 ? "RECONCILIATION_AMBIGUOUS" : "RECONCILIATION_NOT_CONFIRMED",
                    matches.size() > 1 ? "Provider returned multiple matching templates" : "Provider template is absent",
                    now);
            return false;
        }
        return reconcileDetail(operation, matches.get(0), command, now, true);
    }

    private boolean reconcileExisting(TemplateOperationEntity operation,
                                      List<ProviderTemplateSummary> summaries,
                                      Instant now) {
        List<ProviderTemplateSummary> matches = matchingCodeAndLanguage(operation, summaries);
        if (matches.size() != 1) {
            retry(operation, matches.size() > 1 ? "RECONCILIATION_AMBIGUOUS" : "RECONCILIATION_NOT_CONFIRMED",
                    "Provider state is not yet conclusive", now);
            return false;
        }
        return reconcileDetail(operation, matches.get(0), null, now, false);
    }

    private boolean reconcileDelete(TemplateOperationEntity operation,
                                    List<ProviderTemplateSummary> summaries,
                                    Instant now) {
        if (matchingCodeAndLanguage(operation, summaries).isEmpty()) {
            templateForOperation(operation).ifPresent(template -> {
                        template.setDeletedAt(now);
                        template.setAllowSend(false);
                        template.setUpdatedAt(now);
                        templateMapper.updateById(template);
                    });
            operationMapper.markSucceeded(operation.getId(), operation.getProviderTemplateId(), null, now);
            markRequestSucceeded(operation, null, now);
            return true;
        }
        retry(operation, "RECONCILIATION_NOT_CONFIRMED", "Provider still returns the template", now);
        return false;
    }

    private boolean reconcileDetail(TemplateOperationEntity operation,
                                    ProviderTemplateSummary summary,
                                    TemplateCommand command,
                                    Instant now,
                                    boolean create) {
        Optional<TemplateSnapshot> detail = verifiedDetail(operation.getChannelAccountId(), summary.templateCode(),
                summary.language(), gateway.detail(operation.getChannelAccountId(),
                        summary.templateCode(), summary.language()));
        if (detail.isEmpty()) {
            retry(operation, "RECONCILIATION_NOT_CONFIRMED", "Provider detail is not yet available", now);
            return false;
        }
        TemplateSnapshot snapshot = detail.orElseThrow();
        TemplateEntity current = templateForOperation(operation).orElse(null);
        UUID providerScopeId = current == null ? scopeForOperation(operation) : current.getProviderScopeId();
        boolean privateDomain = isPrivateAccount(operation.getChannelAccountId());
        persistSnapshot(snapshot, current, providerScopeId,
                operation.getChannelAccountId(), now, privateDomain);
        if (create && snapshot.reviewStatus() == ReviewStatus.REJECTED) {
            referencedMedia(operation, command, snapshot).ifPresent(asset -> mediaMapper.markOrphaned(asset.getId()));
            fail(operation, "TEMPLATE_PROVIDER_REJECTED", snapshot.rejectionReason(), now);
            return true;
        }
        if (create) {
            referencedMedia(operation, command, snapshot).ifPresent(asset -> {
                asset.setAssetStatus("ATTACHED");
                asset.setAttachedAt(now);
                mediaMapper.updateById(asset);
            });
        }
        operationMapper.markSucceeded(operation.getId(), snapshot.templateCode(), null, now);
        markRequestSucceeded(operation, null, now);
        return true;
    }

    private Optional<TemplateMediaAssetEntity> referencedMedia(TemplateOperationEntity operation,
                                                                TemplateCommand command,
                                                                TemplateSnapshot snapshot) {
        if (command == null) {
            return Optional.empty();
        }
        return command.components().stream()
                .map(TemplateComponent::mediaAssetId)
                .filter(Objects::nonNull)
                .findFirst()
                .flatMap(WhatsAppTemplateReconciliationService::parseUuid)
                .flatMap(assetId -> mediaMapper.findByIdAndChannelAccountId(assetId, operation.getChannelAccountId()))
                .filter(asset -> snapshot.components().stream()
                        .map(TemplateComponent::mediaAssetId)
                        .anyMatch(url -> same(url, asset.getProviderUrl())));
    }

    private ProviderSnapshot loadProviderSnapshot(UUID accountId) {
        Map<TemplateKey, ProviderTemplateSummary> items = new LinkedHashMap<>();
        boolean unambiguous = true;
        for (int page = 1; page <= MAX_PAGES; page++) {
            ProviderTemplatePage result = gateway.list(accountId, page, PAGE_SIZE);
            if (result == null || result.items() == null) {
                throw new IllegalStateException("Provider returned an invalid template page");
            }
            for (ProviderTemplateSummary summary : result.items()) {
                if (items.putIfAbsent(key(summary.templateCode(), summary.language()), summary) != null) {
                    unambiguous = false;
                }
            }
            if (!result.hasNext()) {
                return new ProviderSnapshot(new ArrayList<>(items.values()), page, unambiguous);
            }
            if (result.items().isEmpty()) {
                return new ProviderSnapshot(new ArrayList<>(items.values()), page, false);
            }
        }
        return new ProviderSnapshot(new ArrayList<>(items.values()), MAX_PAGES, false);
    }

    private static Optional<TemplateSnapshot> verifiedDetail(UUID accountId, String templateCode, String language,
                                                              Optional<TemplateSnapshot> detail) {
        if (detail.isEmpty()) {
            return detail;
        }
        TemplateSnapshot snapshot = detail.orElseThrow();
        if (!Objects.equals(accountId, snapshot.accountId())
                || !same(templateCode, snapshot.templateCode())
                || !same(language, snapshot.language())) {
            throw new IllegalStateException("Provider template detail does not match the requested account and key");
        }
        return detail;
    }

    private Map<TemplateKey, TemplateEntity> existingTemplates(UUID providerScopeId, UUID accountId,
                                                                boolean privateDomain) {
        Map<TemplateKey, TemplateEntity> existing = new HashMap<>();
        List<TemplateEntity> templates = privateDomain
                ? templateMapper.findPrivateForAccount(Objects.requireNonNull(accountId))
                : templateMapper.findScopeTemplates(Objects.requireNonNull(providerScopeId));
        for (TemplateEntity entity : templates) {
            existing.put(key(entity.getProviderTemplateId(), entity.getLanguageCode()), entity);
        }
        return existing;
    }

    private void persistSnapshot(TemplateSnapshot snapshot, TemplateEntity entity, UUID providerScopeId,
                                 UUID credentialAccountId, Instant now, boolean privateDomain) {
        UUID scopeId = Objects.requireNonNull(providerScopeId);
        TemplateEntity target = entity == null ? new TemplateEntity() : entity;
        if (entity == null) {
            target.setId(UUID.randomUUID());
            target.setCreatedAt(now);
        }
        target.setChannelAccountId(privateDomain ? credentialAccountId : null);
        target.setProviderScopeId(scopeId);
        target.setTemplateDomain(privateDomain ? "EMPLOYEE_BUSINESS_APP" : "ENTERPRISE_API");
        target.setProviderTemplateId(snapshot.templateCode());
        target.setLanguageCode(snapshot.language());
        target.setName(snapshot.templateName());
        target.setBody(body(snapshot.components()));
        target.setStatus(snapshot.reviewStatus().name());
        target.setCategory(snapshot.category());
        target.setTemplateType("WHATSAPP");
        target.setComponentsJsonb(json(snapshot.components()));
        target.setExamplesJsonb(json(snapshot.examples()));
        target.setMessageSendTtlSeconds(snapshot.messageSendTtlSeconds());
        target.setAllowSend(snapshot.allowSend());
        target.setProviderAuditStatus(snapshot.rawAuditStatus());
        target.setRejectionReason(snapshot.rejectionReason());
        target.setProviderUpdatedAt(snapshot.providerUpdatedAt());
        target.setLastSyncedAt(now);
        target.setUpdatedAt(now);
        target.setDeletedAt(snapshot.deletedAt());
        if (privateDomain) {
            templateMapper.upsertPrivate(target);
        } else {
            templateMapper.upsertShared(target);
        }
    }

    private void persistSummary(ProviderTemplateSummary summary, TemplateEntity entity, UUID providerScopeId,
                                UUID accountId, Instant now, boolean privateDomain) {
        if (entity == null) {
            TemplateSnapshot snapshot = new TemplateSnapshot(accountId, summary.templateCode(), summary.templateName(),
                    summary.language(), summary.category(), reviewStatus(summary.rawAuditStatus()),
                    summary.rawAuditStatus(), summary.reason(), false, List.of(), Map.of(), null,
                    summary.providerUpdatedAt(), null);
            persistSnapshot(snapshot, null, providerScopeId, accountId, now, privateDomain);
            return;
        }
        entity.setName(summary.templateName());
        entity.setCategory(summary.category());
        entity.setStatus(reviewStatus(summary.rawAuditStatus()).name());
        entity.setProviderAuditStatus(summary.rawAuditStatus());
        entity.setRejectionReason(summary.reason());
        entity.setProviderUpdatedAt(summary.providerUpdatedAt());
        entity.setLastSyncedAt(now);
        entity.setUpdatedAt(now);
        templateMapper.updateById(entity);
    }

    private void retry(TemplateOperationEntity operation, String code, String message, Instant now) {
        if (attemptLimitReached(operation)) {
            fail(operation, "RECONCILIATION_ATTEMPT_LIMIT", "Reconciliation exceeded 10 attempts", now);
            return;
        }
        operationMapper.markUnknown(operation.getId(), code, message, now.plus(backoff(operation)));
    }

    private void fail(TemplateOperationEntity operation, String code, String message, Instant now) {
        operationMapper.markFailed(operation.getId(), null, code, message == null ? "Provider rejected template" : message, now);
        if (operation.getChangeRequestId() != null) {
            changeRequestMapper.markExecutionFailed(operation.getChangeRequestId(), code,
                    message == null ? "Provider rejected template" : message, now);
        }
    }

    private Optional<TemplateEntity> templateForOperation(TemplateOperationEntity operation) {
        ChannelAccountEntity account = accountMapper.selectById(operation.getChannelAccountId());
        boolean privateDomain = isPrivateAccount(operation.getChannelAccountId());
        if (operation.getTemplateId() != null) {
            return privateDomain
                    ? templateMapper.findPrivateForUpdate(operation.getTemplateId(), operation.getChannelAccountId())
                    : templateMapper.findSharedForUpdate(operation.getTemplateId());
        }
        return privateDomain
                ? templateMapper.findByPrivateIdentity(account.getId(), operation.getProviderTemplateId(),
                operation.getLanguageCode())
                : templateMapper.findSharedForDisplay(scopeForOperation(operation), operation.getProviderTemplateId(),
                operation.getLanguageCode());
    }

    private boolean isPrivateAccount(UUID accountId) {
        ChannelAccountEntity account = accountMapper.selectById(accountId);
        return account != null && "BUSINESS_APP_COEXISTENCE".equalsIgnoreCase(account.getOnboardingMode());
    }

    private UUID scopeForOperation(TemplateOperationEntity operation) {
        ChannelAccountEntity account = accountMapper.selectById(operation.getChannelAccountId());
        if (account == null || account.getProviderScopeId() == null) {
            throw new IllegalStateException("WHATSAPP_PROVIDER_SCOPE_REQUIRED");
        }
        return account.getProviderScopeId();
    }

    private void markRequestSucceeded(TemplateOperationEntity operation, String providerRequestId, Instant now) {
        if (operation.getChangeRequestId() != null) {
            changeRequestMapper.markSucceeded(operation.getChangeRequestId(), providerRequestId, now);
        }
    }

    private static List<ProviderTemplateSummary> matchingCodeAndLanguage(TemplateOperationEntity operation,
                                                                           List<ProviderTemplateSummary> summaries) {
        return summaries.stream().filter(summary -> same(summary.templateCode(), operation.getProviderTemplateId())
                && same(summary.language(), operation.getLanguageCode())).toList();
    }

    private static boolean expired(TemplateOperationEntity operation, Instant now) {
        return operation.getStartedAt() != null
                && !operation.getStartedAt().plus(RECONCILIATION_WINDOW).isAfter(now);
    }

    private static boolean attemptLimitReached(TemplateOperationEntity operation) {
        return operation.getReconcileAttemptCount() != null
                && operation.getReconcileAttemptCount() >= MAX_RECONCILIATIONS;
    }

    private static Duration backoff(TemplateOperationEntity operation) {
        int attempt = operation.getReconcileAttemptCount() == null ? 1 : operation.getReconcileAttemptCount();
        long seconds = 60L << Math.min(Math.max(attempt - 1, 0), 12);
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.toSeconds()));
    }

    private static ReviewStatus reviewStatus(String raw) {
        if (raw == null) return ReviewStatus.UNKNOWN;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "pass" -> ReviewStatus.APPROVED;
            case "fail" -> ReviewStatus.REJECTED;
            case "auditing" -> ReviewStatus.PENDING;
            case "unaudit" -> ReviewStatus.SUSPENDED;
            default -> ReviewStatus.UNKNOWN;
        };
    }

    private static String body(List<TemplateComponent> components) {
        return components.stream().filter(component -> component.type() == ComponentType.BODY)
                .map(TemplateComponent::text).filter(Objects::nonNull).findFirst().orElse("");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize provider template snapshot", error);
        }
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private static TemplateKey key(String templateCode, String language) {
        return new TemplateKey(templateCode, language);
    }

    private static boolean same(String left, String right) {
        return Objects.equals(left, right);
    }

    private Instant now() {
        return clock.instant();
    }

    public record SyncResult(int pages, int fetched, int changed, boolean complete) {
    }

    private record TemplateKey(String templateCode, String language) {
    }

    private record ProviderSnapshot(List<ProviderTemplateSummary> items, int pages, boolean complete) {
    }
}
