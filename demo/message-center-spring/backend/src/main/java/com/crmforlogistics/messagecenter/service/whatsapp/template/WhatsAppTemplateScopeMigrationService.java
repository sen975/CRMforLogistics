package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppTemplateMigrationMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class WhatsAppTemplateScopeMigrationService {
    private final ChannelAccountMapper accountMapper;
    private final WhatsAppProviderScopeMapper scopeMapper;
    private final TemplateMapper templateMapper;
    private final WhatsAppTemplateMigrationMapper migrationMapper;
    private final WhatsAppProviderScopeService providerScopeService;
    private final WhatsAppTemplateReconciliationService reconciliation;
    private final WhatsAppTemplateScopeGate gate;
    private final Clock clock;

    public WhatsAppTemplateScopeMigrationService(ChannelAccountMapper accountMapper,
                                                 WhatsAppProviderScopeMapper scopeMapper,
                                                 TemplateMapper templateMapper,
                                                 WhatsAppTemplateMigrationMapper migrationMapper,
                                                 WhatsAppProviderScopeService providerScopeService,
                                                 WhatsAppTemplateReconciliationService reconciliation,
                                                 WhatsAppTemplateScopeGate gate,
                                                 Clock clock) {
        this.accountMapper = Objects.requireNonNull(accountMapper);
        this.scopeMapper = Objects.requireNonNull(scopeMapper);
        this.templateMapper = Objects.requireNonNull(templateMapper);
        this.migrationMapper = migrationMapper;
        this.providerScopeService = providerScopeService;
        this.reconciliation = Objects.requireNonNull(reconciliation);
        this.gate = Objects.requireNonNull(gate);
        this.clock = Objects.requireNonNull(clock);
    }

    public MigrationReport migrate() {
        if (migrationMapper != null) {
            migrationMapper.markRunning();
            migrationMapper.clearExceptions();
        }
        List<ChannelAccountEntity> allAccounts = accountMapper.selectActiveChatAppAccountsForSync();
        if (providerScopeService != null) {
            for (ChannelAccountEntity account : allAccounts) {
                if (account.getProviderScopeId() == null) {
                    providerScopeService.bind(account);
                }
            }
        }
        List<WhatsAppProviderScopeEntity> scopes = scopeMapper.findAllByProvider("ALIYUN_CAMS");
        if (scopes.isEmpty()) {
            gate.fail("WHATSAPP_ACCOUNT_REQUIRED", "尚未绑定有效的 WhatsApp 模板空间");
            return new MigrationReport(MigrationStatus.BLOCKED, 0, 0, 1);
        }
        if (scopes.size() > 1) {
            // Several CAMS spaces coexist. Binding the shared catalog to one of them is enough to keep
            // the template APIs usable, and skipping merge/reconcile means no other space's rows are
            // re-scoped or retired — which is what used to make a second space a hard outage.
            gate.open(sharedCatalogScope(scopes, allAccounts));
            return new MigrationReport(MigrationStatus.READY, scopes.size(), 0, 0);
        }

        UUID scopeId = scopes.get(0).getId();
        List<ChannelAccountEntity> accounts = activeAccounts(allAccounts, scopeId);
        if (accounts.isEmpty()) {
            gate.fail("WHATSAPP_ACCOUNT_REQUIRED", "没有可用于模板对账的 WhatsApp 账号");
            return new MigrationReport(MigrationStatus.BLOCKED, 1, 0, 1);
        }
        int canonical = mergeDuplicates(scopeId, clock.instant());
        int exceptions = reconcileReferences(scopeId);
        if (exceptions > 0) {
            gate.fail("WHATSAPP_TEMPLATE_MIGRATION_EXCEPTION", "共享模板历史数据存在无法自动归并的引用");
            return new MigrationReport(MigrationStatus.BLOCKED, 1, canonical, exceptions);
        }

        WhatsAppTemplateReconciliationService.SyncResult sync = null;
        for (ChannelAccountEntity account : accounts) {
            sync = reconciliation.syncScope(scopeId, account.getId());
            if (sync.complete()) {
                break;
            }
        }
        if (sync == null || !sync.complete()) {
            gate.fail("WHATSAPP_TEMPLATE_RECONCILIATION_INCOMPLETE", "WhatsApp 官方模板目录对账未完成");
            return new MigrationReport(MigrationStatus.BLOCKED, 1, canonical, 1);
        }
        gate.open(scopeId);
        return new MigrationReport(MigrationStatus.READY, 1, canonical, 0);
    }

    /**
     * Picks the space the shared template catalog belongs to when more than one CAMS space is bound:
     * the one that already owns templates, then the one with the most active chatapp accounts, then the
     * lowest id. Every term is derived from existing rows, so the same database always resolves to the
     * same space and no other space's data is touched.
     */
    private UUID sharedCatalogScope(List<WhatsAppProviderScopeEntity> scopes,
                                    List<ChannelAccountEntity> allAccounts) {
        Comparator<WhatsAppProviderScopeEntity> order = Comparator
                .comparingLong((WhatsAppProviderScopeEntity scope) -> templateMapper.countLiveSharedForScope(scope.getId()))
                .reversed()
                .thenComparing(Comparator.comparingLong(
                        (WhatsAppProviderScopeEntity scope) -> countAccounts(allAccounts, scope.getId())).reversed())
                .thenComparing(WhatsAppProviderScopeEntity::getId);
        return scopes.stream().min(order).orElseThrow().getId();
    }

    private static long countAccounts(List<ChannelAccountEntity> allAccounts, UUID scopeId) {
        return allAccounts.stream().filter(account -> scopeId.equals(account.getProviderScopeId())).count();
    }

    private static List<ChannelAccountEntity> activeAccounts(List<ChannelAccountEntity> allAccounts, UUID scopeId) {
        List<ChannelAccountEntity> matching = new ArrayList<>();
        for (ChannelAccountEntity account : allAccounts) {
            if (scopeId.equals(account.getProviderScopeId())) {
                matching.add(account);
            }
        }
        return matching;
    }

    private int mergeDuplicates(UUID scopeId, Instant now) {
        Map<TemplateKey, List<TemplateEntity>> groups = new LinkedHashMap<>();
        for (TemplateEntity template : templateMapper.findTemplatesForScopeMigration(scopeId)) {
            groups.computeIfAbsent(new TemplateKey(template.getProviderTemplateId(), template.getLanguageCode()),
                    ignored -> new ArrayList<>()).add(template);
        }
        Comparator<TemplateEntity> stable = Comparator
                .comparing((TemplateEntity template) -> !scopeId.equals(template.getProviderScopeId()))
                .thenComparing(TemplateEntity::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(TemplateEntity::getId);
        for (List<TemplateEntity> group : groups.values()) {
            group.sort(stable);
            UUID survivor = group.get(0).getId();
            templateMapper.assignProviderScope(survivor, scopeId, now);
            for (int index = 1; index < group.size(); index++) {
                UUID duplicate = group.get(index).getId();
                if (migrationMapper != null) {
                    migrationMapper.rewireOperations(duplicate, survivor);
                    migrationMapper.copyMediaBindings(duplicate, survivor);
                    migrationMapper.deleteDuplicateMediaBindings(duplicate);
                }
                templateMapper.retireDuplicate(duplicate, now);
            }
        }
        return groups.size();
    }

    private int reconcileReferences(UUID scopeId) {
        if (migrationMapper == null) {
            return 0;
        }
        migrationMapper.linkOperationsByIdentity(scopeId);
        int operations = migrationMapper.countUnlinkedOperations(scopeId);
        int media = migrationMapper.countUnlinkedAttachedMedia(scopeId);
        if (operations > 0) {
            migrationMapper.insertCountException("TEMPLATE_OPERATION", "TEMPLATE_OPERATION_UNLINKED", operations);
        }
        if (media > 0) {
            migrationMapper.insertCountException("TEMPLATE_MEDIA_ASSET", "ATTACHED_MEDIA_UNLINKED", media);
        }
        return operations + media;
    }

    public enum MigrationStatus { READY, BLOCKED }

    public record MigrationReport(MigrationStatus status, int scopeCount, int canonicalTemplates, int exceptions) { }

    private record TemplateKey(String code, String language) { }
}
