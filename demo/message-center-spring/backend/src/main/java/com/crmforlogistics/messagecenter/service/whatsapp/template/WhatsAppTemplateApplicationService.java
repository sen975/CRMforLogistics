package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.AuditLogEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAccountMode;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import com.crmforlogistics.messagecenter.entity.TemplateOperationEntity;
import com.crmforlogistics.messagecenter.mapper.AuditLogMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateOperationMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.CreateResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.DeleteResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ModifyResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.OperationStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.OperationType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.PropertyResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ReviewStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class WhatsAppTemplateApplicationService {
    private final ChannelAccountMapper accountMapper;
    private final TemplateOperationMapper operationMapper;
    private final TemplateMediaAssetMapper mediaMapper;
    private final TemplateMapper templateMapper;
    private final AuditLogMapper auditLogMapper;
    private final WhatsAppTemplateGateway gateway;
    private final WhatsAppTemplateValidator validator;
    private final WhatsAppProviderScopeService providerScopes;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WhatsAppTemplateApplicationService(
            ChannelAccountMapper accountMapper,
            TemplateOperationMapper operationMapper,
            TemplateMediaAssetMapper mediaMapper,
            TemplateMapper templateMapper,
            AuditLogMapper auditLogMapper,
            WhatsAppTemplateGateway gateway,
            WhatsAppTemplateValidator validator,
            WhatsAppProviderScopeService providerScopes,
            ObjectMapper objectMapper,
            Clock clock) {
        this.accountMapper = Objects.requireNonNull(accountMapper);
        this.operationMapper = Objects.requireNonNull(operationMapper);
        this.mediaMapper = Objects.requireNonNull(mediaMapper);
        this.templateMapper = Objects.requireNonNull(templateMapper);
        this.auditLogMapper = Objects.requireNonNull(auditLogMapper);
        this.gateway = Objects.requireNonNull(gateway);
        this.validator = Objects.requireNonNull(validator);
        this.providerScopes = Objects.requireNonNull(providerScopes);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView create(UUID providerScopeId, UUID accountId, TemplateCommand command,
                                UUID actorUserId, String traceId) {
        ChannelAccountEntity account = requireAccount(accountId);
        requireEnterpriseApiAccount(account);
        if (!Objects.equals(providerScopeId, account.getProviderScopeId())) {
            throw business("WHATSAPP_PROVIDER_SCOPE_MISMATCH", HttpStatus.CONFLICT,
                    "WhatsApp account does not belong to the shared template scope");
        }
        return createInternal(providerScopeId, accountId, command, actorUserId, traceId, false);
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView createPrivate(UUID accountId, TemplateCommand command,
                                       UUID actorUserId, String traceId) {
        ChannelAccountEntity account = requireOwnedBusinessAppAccount(accountId, actorUserId);
        if (account.getProviderScopeId() == null) {
            throw business("WHATSAPP_PROVIDER_SCOPE_REQUIRED", HttpStatus.CONFLICT,
                    "Business App 账号尚未完成模板空间绑定");
        }
        return createInternal(account.getProviderScopeId(), accountId, command, actorUserId, traceId, true);
    }

    /**
     * 申请模板：按<b>账号自己的绑定方式</b>决定走到哪个域 —— 这是助手那条路的唯一入口。
     *
     * <h2>为什么不能只调 createPrivate（2026-09-29 修的）</h2>
     * 两种绑定对应两个平台 API，它们的模板根本不在一个地方：
     * Business App 的模板是<b>账号私有</b>的（{@link #createPrivate}），
     * 企业 API 的模板是<b>整个 CAMS 空间共享</b>的（{@link #create}）。
     * 而 {@code createPrivate} 的第一行是 {@code requireOwnedBusinessAppAccount}，
     * 它对一个企业 API 账号只会抛 {@code WHATSAPP_TEMPLATE_DOMAIN_MISMATCH} ——
     * 也就是说，只调它的话，<b>任何企业 API 账号都申请不了模板</b>，
     * 而错误信息里没有一个字告诉用户「换个入口就好了」。
     *
     * <h2>归属为什么必须在这里查</h2>
     * {@link #create} 自己<b>不</b>查归属：共享域的归属由 scope 门承担（调用方已经先
     * {@code requireScopeAccount(actorUserId, scopeId)} 过）。域分派把两条路合到一处，
     * 那道门就不再必然存在，所以这里先自己查一次 —— 判据是 SQL 里的
     * {@code owner_user_id}（{@link #requireOwnedAccount}），两个域共用同一条。
     *
     * <h2>未绑空间不等于「这个账号在 CAMS 里不存在」</h2>
     * 账号属于哪个空间由它<b>凭证里的</b> {@code custSpaceId} 决定
     * （{@link WhatsAppProviderScopeService#bind}），{@code provider_scope_id} 只是我们
     * 这边的缓存。缓存为空时这里补绑一次，而不是把账号判死 —— 否则一个在 CAMS 里
     * 明明有号码的用户会因为一次没跑过的绑定而「不能申请模板」。
     *
     * <p>本条只覆盖<b>新建</b>。修改与删除模板不开放给助手：它们影响的是所有正在用该
     * 模板的会话，且同样提交给外部平台、撤不回，风险面远大于「多一个待审核的模板」。
     */
    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView createForActor(UUID accountId, TemplateCommand command,
                                        UUID actorUserId, String traceId) {
        ChannelAccountEntity account = requireOwnedAccount(accountId, actorUserId);
        UUID providerScopeId = account.getProviderScopeId();
        if (providerScopeId == null) {
            providerScopeId = providerScopes.bind(account).getId();
        }
        boolean privateDomain = WhatsAppAccountMode.isBusinessApp(account.getOnboardingMode());
        return createInternal(providerScopeId, accountId, command, actorUserId, traceId, privateDomain);
    }

    private OperationView createInternal(UUID providerScopeId, UUID accountId, TemplateCommand command,
                                         UUID actorUserId, String traceId, boolean privateDomain) {
        requireAccount(accountId);
        TemplateCommand validated = validator.validate(command);
        PreparedCommand prepared = prepareMedia(accountId, validated);
        BeginOperation begun = beginOperation(accountId, validated.clientRequestId(), OperationType.CREATE,
                null, validated.language(), validated, actorUserId, traceId);
        if (begun.existing()) {
            return operationView(begun.operation());
        }

        TemplateOperationEntity operation = begun.operation();
        TemplateCredentialSource source = privateDomain
                ? TemplateCredentialSource.account(accountId)
                : TemplateCredentialSource.space(providerScopeId);
        try {
            CreateResult result = gateway.create(source, prepared.providerCommand());
            TemplateSnapshot snapshot = detailAfterAcknowledgedWrite(source, result.templateCode(), validated.language())
                    .orElseGet(() -> unknownSnapshot(result.templateCode(), validated));
            persistSnapshot(snapshot, providerScopeId, accountId, actorUserId, privateDomain);
            attach(prepared.asset());
            succeed(operation, result.templateCode(), result.providerRequestId());
            auditOperation(operation, actorUserId, traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException e) {
            failOperation(operation, prepared.asset(), e);
            auditOperation(operation, actorUserId, traceId);
            throw e;
        }
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView modifyShared(UUID providerScopeId, UUID credentialAccountId, UUID templateId,
                                      TemplateCommand command, UUID actorUserId, UUID changeRequestId,
                                      String traceId) {
        TemplateEntity current = lockSharedTemplate(providerScopeId, credentialAccountId, templateId);
        requireEnterpriseApiAccount(requireAccount(credentialAccountId));
        TemplateCommand requested = validator.validate(command);
        final TemplateCommand validated = new TemplateCommand(requested.name(), current.getLanguageCode(), requested.category(),
                requested.components(), requested.examples(), requested.messageSendTtlSeconds(),
                requested.clientRequestId());
        if (!Objects.equals(current.getName(), validated.name())) {
            throw immutableName();
        }
        PreparedCommand prepared = prepareMedia(credentialAccountId, validated);
        BeginOperation begun = beginOperation(credentialAccountId, validated.clientRequestId(), OperationType.MODIFY,
                current.getProviderTemplateId(), current.getLanguageCode(), validated, actorUserId, traceId,
                current.getId(), changeRequestId);
        if (begun.existing()) {
            return operationView(begun.operation());
        }

        TemplateOperationEntity operation = begun.operation();
        TemplateCredentialSource source = TemplateCredentialSource.space(providerScopeId);
        try {
            ModifyResult result = gateway.modify(source, current.getProviderTemplateId(),
                    current.getLanguageCode(), prepared.providerCommand());
            TemplateSnapshot snapshot = detailAfterAcknowledgedWrite(source,
                    current.getProviderTemplateId(), current.getLanguageCode()).orElseGet(() -> unknownSnapshot(
                    current.getProviderTemplateId(), validated));
            persistSnapshot(snapshot, providerScopeId, credentialAccountId, actorUserId);
            attach(prepared.asset());
            succeed(operation, result.templateCode(), result.providerRequestId());
            audit("WHATSAPP_TEMPLATE_MODIFY", "MESSAGE_TEMPLATE", current.getId(), actorUserId,
                    json(templateSummary(current)), json(snapshot), auditResult(OperationStatus.SUCCEEDED), traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException e) {
            failOperation(operation, prepared.asset(), e);
            auditOperation(operation, actorUserId, traceId);
            throw e;
        }
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView modifyPrivate(UUID accountId, UUID actorUserId, UUID templateId,
                                       TemplateCommand command, String traceId) {
        ChannelAccountEntity account = requireOwnedBusinessAppAccount(accountId, actorUserId);
        TemplateEntity current = lockPrivateTemplate(accountId, templateId);
        TemplateCommand requested = validator.validate(command);
        final TemplateCommand validated = new TemplateCommand(requested.name(), current.getLanguageCode(), requested.category(),
                requested.components(), requested.examples(), requested.messageSendTtlSeconds(),
                requested.clientRequestId());
        if (!Objects.equals(current.getName(), validated.name())) {
            throw immutableName();
        }
        PreparedCommand prepared = prepareMedia(accountId, validated);
        BeginOperation begun = beginOperation(accountId, validated.clientRequestId(), OperationType.MODIFY,
                current.getProviderTemplateId(), current.getLanguageCode(), validated, actorUserId, traceId,
                current.getId(), null);
        if (begun.existing()) return operationView(begun.operation());
        TemplateOperationEntity operation = begun.operation();
        TemplateCredentialSource source = TemplateCredentialSource.account(accountId);
        try {
            ModifyResult result = gateway.modify(source, current.getProviderTemplateId(),
                    current.getLanguageCode(), prepared.providerCommand());
            TemplateSnapshot snapshot = detailAfterAcknowledgedWrite(source, current.getProviderTemplateId(),
                    current.getLanguageCode()).orElseGet(() -> unknownSnapshot(
                    current.getProviderTemplateId(), validated));
            persistSnapshot(snapshot, account.getProviderScopeId(), accountId, actorUserId, true);
            attach(prepared.asset());
            succeed(operation, result.templateCode(), result.providerRequestId());
            audit("WHATSAPP_PRIVATE_TEMPLATE_MODIFY", "MESSAGE_TEMPLATE", current.getId(), actorUserId,
                    json(templateSummary(current)), json(snapshot), auditResult(OperationStatus.SUCCEEDED), traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException error) {
            failOperation(operation, prepared.asset(), error);
            auditOperation(operation, actorUserId, traceId);
            throw error;
        }
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView setSendPermissionShared(UUID providerScopeId, UUID credentialAccountId, UUID templateId,
                                                 boolean allowSend, String clientRequestId, UUID actorUserId,
                                                 UUID changeRequestId, String traceId) {
        TemplateEntity current = lockSharedTemplate(providerScopeId, credentialAccountId, templateId);
        requireEnterpriseApiAccount(requireAccount(credentialAccountId));
        if (allowSend && !"APPROVED".equalsIgnoreCase(current.getStatus())) {
            // 提示要中文，而且要<b>点名当前状态</b>：2026-09-29 报障时这里只有一句英文，
            // 而模板状态又因 CAMS 的 sendFail 未被映射而显示成灰色的「未知」，
            // 用户因此既看不出「审核没通过」，也看不出该去改模板还是改配置。
            throw business("TEMPLATE_NOT_APPROVED", HttpStatus.CONFLICT,
                    "只有审核通过的模板才能开启发送（该模板当前状态：" + current.getStatus() + "）");
        }
        if (!allowSend && !pausable(current.getCategory())) {
            throw pauseUnsupported(current.getCategory());
        }
        boolean previousAllowSend = Boolean.TRUE.equals(current.getAllowSend());
        BeginOperation begun = beginOperation(credentialAccountId, clientRequestId, OperationType.SET_SEND_PERMISSION,
                current.getProviderTemplateId(), current.getLanguageCode(), Map.of("allowSend", allowSend),
                actorUserId, traceId, current.getId(), changeRequestId);
        if (begun.existing()) {
            return operationView(begun.operation());
        }

        TemplateOperationEntity operation = begun.operation();
        try {
            PropertyResult result = gateway.setSendPermission(TemplateCredentialSource.space(providerScopeId),
                    current.getProviderTemplateId(), current.getLanguageCode(), allowSend);
            current.setAllowSend(result.allowSend());
            if (result.allowSend() != allowSend) {
                throw business("TEMPLATE_PERMISSION_NOT_CONFIRMED", HttpStatus.BAD_GATEWAY,
                        "Provider did not confirm the requested permission");
            }
            current.setUpdatedAt(now());
            templateMapper.updateById(current);
            succeed(operation, current.getProviderTemplateId(), result.providerRequestId());
            audit("WHATSAPP_TEMPLATE_SEND_PERMISSION", "MESSAGE_TEMPLATE", current.getId(), actorUserId,
                    json(Map.of("allowSend", previousAllowSend)), json(Map.of("allowSend", result.allowSend())),
                    auditResult(OperationStatus.SUCCEEDED), traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException e) {
            failOperation(operation, null, e);
            auditOperation(operation, actorUserId, traceId);
            throw e;
        }
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView setSendPermissionPrivate(UUID accountId, UUID actorUserId, UUID templateId,
                                                  boolean allowSend, String clientRequestId, String traceId) {
        requireOwnedBusinessAppAccount(accountId, actorUserId);
        TemplateEntity current = lockPrivateTemplate(accountId, templateId);
        if (!allowSend && !pausable(current.getCategory())) {
            throw pauseUnsupported(current.getCategory());
        }
        boolean previousAllowSend = Boolean.TRUE.equals(current.getAllowSend());
        BeginOperation begun = beginOperation(accountId, clientRequestId, OperationType.SET_SEND_PERMISSION,
                current.getProviderTemplateId(), current.getLanguageCode(), Map.of("allowSend", allowSend),
                actorUserId, traceId, current.getId(), null);
        if (begun.existing()) return operationView(begun.operation());
        TemplateOperationEntity operation = begun.operation();
        try {
            PropertyResult result = gateway.setSendPermission(TemplateCredentialSource.account(accountId),
                    current.getProviderTemplateId(), current.getLanguageCode(), allowSend);
            if (result.allowSend() != allowSend) {
                throw business("TEMPLATE_PERMISSION_NOT_CONFIRMED", HttpStatus.BAD_GATEWAY,
                        "Provider did not confirm the requested permission");
            }
            current.setAllowSend(result.allowSend());
            current.setUpdatedAt(now());
            templateMapper.updateById(current);
            succeed(operation, current.getProviderTemplateId(), result.providerRequestId());
            audit("WHATSAPP_PRIVATE_TEMPLATE_SEND_PERMISSION", "MESSAGE_TEMPLATE", current.getId(), actorUserId,
                    json(Map.of("allowSend", previousAllowSend)), json(Map.of("allowSend", result.allowSend())),
                    auditResult(OperationStatus.SUCCEEDED), traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException error) {
            failOperation(operation, null, error);
            auditOperation(operation, actorUserId, traceId);
            throw error;
        }
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView deleteShared(UUID providerScopeId, UUID credentialAccountId, UUID templateId,
                                      String clientRequestId, UUID actorUserId, UUID changeRequestId,
                                      String traceId) {
        TemplateEntity current = lockSharedTemplate(providerScopeId, credentialAccountId, templateId);
        requireEnterpriseApiAccount(requireAccount(credentialAccountId));
        BeginOperation begun = beginOperation(credentialAccountId, clientRequestId, OperationType.DELETE,
                current.getProviderTemplateId(), current.getLanguageCode(),
                Map.of("templateCode", current.getProviderTemplateId(), "language", current.getLanguageCode()),
                actorUserId, traceId, current.getId(), changeRequestId);
        if (begun.existing()) {
            return operationView(begun.operation());
        }

        TemplateOperationEntity operation = begun.operation();
        try {
            DeleteResult result = gateway.delete(TemplateCredentialSource.space(providerScopeId),
                    current.getProviderTemplateId(), current.getLanguageCode());
            if (!result.success()) {
                throw providerRejected("TEMPLATE_DELETE_NOT_CONFIRMED", result.providerRequestId());
            }
            current.setDeletedAt(now());
            current.setAllowSend(false);
            current.setUpdatedAt(now());
            templateMapper.updateById(current);
            succeed(operation, current.getProviderTemplateId(), result.providerRequestId());
            audit("WHATSAPP_TEMPLATE_DELETE", "MESSAGE_TEMPLATE", current.getId(), actorUserId,
                    "{}", json(Map.of("deleted", true)), auditResult(OperationStatus.SUCCEEDED), traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException e) {
            failOperation(operation, null, e);
            auditOperation(operation, actorUserId, traceId);
            throw e;
        }
    }

    @Transactional(noRollbackFor = WhatsAppTemplateException.class)
    public OperationView deletePrivate(UUID accountId, UUID actorUserId, UUID templateId,
                                       String clientRequestId, String traceId) {
        requireOwnedBusinessAppAccount(accountId, actorUserId);
        TemplateEntity current = lockPrivateTemplate(accountId, templateId);
        BeginOperation begun = beginOperation(accountId, clientRequestId, OperationType.DELETE,
                current.getProviderTemplateId(), current.getLanguageCode(),
                Map.of("templateCode", current.getProviderTemplateId(), "language", current.getLanguageCode()),
                actorUserId, traceId, current.getId(), null);
        if (begun.existing()) return operationView(begun.operation());
        TemplateOperationEntity operation = begun.operation();
        try {
            DeleteResult result = gateway.delete(TemplateCredentialSource.account(accountId),
                    current.getProviderTemplateId(), current.getLanguageCode());
            if (!result.success()) throw providerRejected("TEMPLATE_DELETE_NOT_CONFIRMED", result.providerRequestId());
            current.setDeletedAt(now());
            current.setAllowSend(false);
            current.setUpdatedAt(now());
            templateMapper.updateById(current);
            succeed(operation, current.getProviderTemplateId(), result.providerRequestId());
            audit("WHATSAPP_PRIVATE_TEMPLATE_DELETE", "MESSAGE_TEMPLATE", current.getId(), actorUserId,
                    "{}", json(Map.of("deleted", true)), auditResult(OperationStatus.SUCCEEDED), traceId);
            return operationView(operation);
        } catch (WhatsAppTemplateException error) {
            failOperation(operation, null, error);
            auditOperation(operation, actorUserId, traceId);
            throw error;
        }
    }

    private ChannelAccountEntity requireAccount(UUID accountId) {
        ChannelAccountEntity account = accountId == null ? null : accountMapper.selectById(accountId);
        if (account == null || account.getDeletedAt() != null) {
            throw business("WHATSAPP_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND, "WhatsApp account not found");
        }
        String type = normalized(account.getChannelType());
        if (!"whatsapp".equals(type) && !"chatapp".equals(type)) {
            throw business("WHATSAPP_ACCOUNT_TYPE_INVALID", HttpStatus.BAD_REQUEST,
                    "Channel account is not a WhatsApp account");
        }
        if (!"active".equals(normalized(account.getAuthStatus()))) {
            throw business("WHATSAPP_ACCOUNT_NOT_ACTIVE", HttpStatus.CONFLICT, "WhatsApp account is not active");
        }
        return account;
    }

    /**
     * 账号属于调用者本人 —— 判据是 SQL 里的 {@code owner_user_id}
     * （{@code ChannelAccountMapper#findByIdAndOwner}），不在 Java 里二次过滤。
     *
     * <p>抽出来是给 {@link #createForActor} 用的：那条路同时覆盖<b>两个</b>域，
     * 而共享域的 {@link #create} 自己不含归属判据（它的调用方先过了 scope 门），
     * 所以域分派之前必须先查一次，否则企业 API 域会整条没有归属防线。
     */
    public ChannelAccountEntity requireOwnedAccount(UUID accountId, UUID actorUserId) {
        if (actorUserId == null) {
            throw business("WHATSAPP_ACCOUNT_OWNER_REQUIRED", HttpStatus.UNAUTHORIZED,
                    "WhatsApp 账号 owner 未登录");
        }
        ChannelAccountEntity account = accountId == null ? null : accountMapper.findByIdAndOwner(accountId, actorUserId);
        if (account == null) {
            throw business("WHATSAPP_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND,
                    "WhatsApp 账号不存在或不属于当前用户");
        }
        return account;
    }

    public ChannelAccountEntity requireOwnedBusinessAppAccount(UUID accountId, UUID actorUserId) {
        ChannelAccountEntity account = requireOwnedAccount(accountId, actorUserId);
        if (!WhatsAppAccountMode.isBusinessApp(account.getOnboardingMode())) {
            throw business("WHATSAPP_TEMPLATE_DOMAIN_MISMATCH", HttpStatus.CONFLICT,
                    "当前 WhatsApp 账号不是 Business App 私有模板账号");
        }
        return account;
    }

    private static void requireEnterpriseApiAccount(ChannelAccountEntity account) {
        if (WhatsAppAccountMode.isBusinessApp(account.getOnboardingMode())) {
            throw business("WHATSAPP_TEMPLATE_DOMAIN_MISMATCH", HttpStatus.CONFLICT,
                    "Business App 账号只能使用自己的私有模板");
        }
    }

    private PreparedCommand prepareMedia(UUID accountId, TemplateCommand command) {
        TemplateMediaAssetEntity selected = null;
        List<TemplateComponent> components = new ArrayList<>(command.components().size());
        for (TemplateComponent component : command.components()) {
            if (component.type() == ComponentType.HEADER && component.headerFormat() != null
                    && component.headerFormat() != HeaderFormat.TEXT) {
                UUID assetId;
                try {
                    assetId = UUID.fromString(component.mediaAssetId());
                } catch (RuntimeException e) {
                    throw validation("header.mediaAssetId", "must be a valid internal media asset ID");
                }
                TemplateMediaAssetEntity asset = mediaMapper.findByIdAndChannelAccountId(assetId, accountId)
                        .orElseThrow(() -> validation("header.mediaAssetId", "does not belong to this account"));
                if (!"UPLOADED".equals(asset.getAssetStatus())) {
                    throw validation("header.mediaAssetId", "must reference an UPLOADED asset");
                }
                if (!component.headerFormat().name().equals(asset.getMediaFormat())) {
                    throw validation("header.mediaAssetId", "media format does not match the header format");
                }
                selected = asset;
                components.add(new TemplateComponent(component.type(), component.headerFormat(), component.text(),
                        asset.getProviderUrl(), component.buttons()));
            } else {
                components.add(component);
            }
        }
        return new PreparedCommand(new TemplateCommand(command.name(), command.language(), command.category(),
                components, command.examples(), command.messageSendTtlSeconds(), command.clientRequestId()), selected);
    }

    private BeginOperation beginOperation(UUID accountId, String idempotencyKey, OperationType type,
                                          String templateCode, String language, Object request,
                                          UUID actorUserId, String traceId) {
        return beginOperation(accountId, idempotencyKey, type, templateCode, language, request,
                actorUserId, traceId, null, null);
    }

    private BeginOperation beginOperation(UUID accountId, String idempotencyKey, OperationType type,
                                          String templateCode, String language, Object request,
                                          UUID actorUserId, String traceId, UUID templateId,
                                          UUID changeRequestId) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw validation("clientRequestId", "must contain 1 to 255 characters");
        }
        Optional<TemplateOperationEntity> existing = operationMapper.findByIdempotency(accountId, idempotencyKey);
        if (existing.isPresent()) {
            return new BeginOperation(existing.orElseThrow(), true);
        }

        TemplateOperationEntity operation = new TemplateOperationEntity();
        operation.setId(UUID.randomUUID());
        operation.setChannelAccountId(accountId);
        operation.setTemplateId(templateId);
        operation.setChangeRequestId(changeRequestId);
        operation.setIdempotencyKey(idempotencyKey);
        operation.setOperationType(type.name());
        operation.setProviderTemplateId(templateCode);
        operation.setLanguageCode(language);
        operation.setRequestedSnapshotJsonb(json(request));
        operation.setOperationStatus(OperationStatus.PROCESSING.name());
        operation.setReconcileAttemptCount(0);
        operation.setActorUserId(actorUserId);
        operation.setTraceId(traceId);
        operation.setStartedAt(now());
        if (operationMapper.insertIgnore(operation) == 0) {
            return new BeginOperation(operationMapper.findByIdempotency(accountId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Idempotent operation disappeared")), true);
        }
        TemplateOperationEntity locked = operationMapper.findByIdForUpdate(operation.getId()).orElse(operation);
        return new BeginOperation(locked, !OperationStatus.PROCESSING.name().equals(locked.getOperationStatus()));
    }

    private TemplateEntity lockSharedTemplate(UUID providerScopeId, UUID credentialAccountId, UUID templateId) {
        ChannelAccountEntity account = requireAccount(credentialAccountId);
        if (!Objects.equals(providerScopeId, account.getProviderScopeId())) {
            throw business("WHATSAPP_PROVIDER_SCOPE_MISMATCH", HttpStatus.CONFLICT,
                    "WhatsApp account does not belong to the shared template scope");
        }
        TemplateEntity template = templateId == null ? null : templateMapper.findSharedForUpdate(templateId).orElse(null);
        if (template == null || template.getDeletedAt() != null
                || "EMPLOYEE_BUSINESS_APP".equalsIgnoreCase(template.getTemplateDomain())) {
            throw business("WHATSAPP_TEMPLATE_NOT_FOUND", HttpStatus.NOT_FOUND, "WhatsApp template not found");
        }
        if (!Objects.equals(providerScopeId, template.getProviderScopeId())) {
            throw business("WHATSAPP_PROVIDER_SCOPE_MISMATCH", HttpStatus.CONFLICT,
                    "WhatsApp template does not belong to the requested shared template scope");
        }
        return template;
    }

    private TemplateEntity lockPrivateTemplate(UUID accountId, UUID templateId) {
        TemplateEntity template = templateId == null ? null
                : templateMapper.findPrivateForUpdate(templateId, accountId).orElse(null);
        if (template == null || template.getDeletedAt() != null) {
            throw business("WHATSAPP_TEMPLATE_NOT_FOUND", HttpStatus.NOT_FOUND, "WhatsApp 私有模板不存在");
        }
        return template;
    }

    private void persistSnapshot(TemplateSnapshot snapshot, UUID providerScopeId,
                                 UUID credentialAccountId, UUID createdByUserId) {
        persistSnapshot(snapshot, providerScopeId, credentialAccountId, createdByUserId, false);
    }

    private void persistSnapshot(TemplateSnapshot snapshot, UUID providerScopeId,
                                 UUID credentialAccountId, UUID createdByUserId, boolean privateDomain) {
        if (providerScopeId == null) {
            throw new IllegalArgumentException("providerScopeId is required");
        }
        TemplateEntity shared = privateDomain
                ? templateMapper.findByPrivateIdentity(credentialAccountId, snapshot.templateCode(), snapshot.language())
                .orElseGet(TemplateEntity::new)
                : templateMapper.findBySharedIdentity(providerScopeId, snapshot.templateCode(), snapshot.language())
                .orElseGet(TemplateEntity::new);
        if (shared.getId() == null) {
            shared.setId(UUID.randomUUID());
            shared.setCreatedAt(now());
            shared.setCreatedByUserId(createdByUserId);
        }
        shared.setChannelAccountId(privateDomain ? credentialAccountId : null);
        shared.setProviderScopeId(providerScopeId);
        shared.setTemplateDomain(privateDomain ? "EMPLOYEE_BUSINESS_APP" : "ENTERPRISE_API");
        applySnapshot(shared, snapshot);
        if (privateDomain) {
            templateMapper.upsertPrivate(shared);
        } else {
            templateMapper.upsertShared(shared);
        }
    }

    private void applySnapshot(TemplateEntity entity, TemplateSnapshot snapshot) {
        entity.setProviderTemplateId(snapshot.templateCode());
        entity.setLanguageCode(snapshot.language());
        entity.setName(snapshot.templateName());
        entity.setBody(snapshot.components().stream()
                .filter(component -> component.type() == ComponentType.BODY)
                .map(TemplateComponent::text).findFirst().orElse(""));
        entity.setStatus(snapshot.reviewStatus().name());
        entity.setCategory(snapshot.category());
        entity.setTemplateType("WHATSAPP");
        entity.setComponentsJsonb(json(snapshot.components()));
        entity.setExamplesJsonb(json(snapshot.examples()));
        entity.setMessageSendTtlSeconds(snapshot.messageSendTtlSeconds());
        entity.setAllowSend(snapshot.allowSend());
        entity.setProviderAuditStatus(snapshot.rawAuditStatus());
        entity.setRejectionReason(snapshot.rejectionReason());
        entity.setProviderUpdatedAt(snapshot.providerUpdatedAt());
        entity.setLastSyncedAt(now());
        entity.setUpdatedAt(now());
        entity.setDeletedAt(snapshot.deletedAt());
    }

    private TemplateSnapshot unknownSnapshot(String templateCode, TemplateCommand command) {
        return new TemplateSnapshot(templateCode, command.name(), command.language(), command.category(),
                ReviewStatus.UNKNOWN, null, null, false, command.components(), command.examples(),
                command.messageSendTtlSeconds(), now(), null);
    }

    private Optional<TemplateSnapshot> detailAfterAcknowledgedWrite(
            TemplateCredentialSource source, String templateCode, String language) {
        try {
            return gateway.detail(source, templateCode, language);
        } catch (WhatsAppTemplateException ignored) {
            return Optional.empty();
        }
    }

    private void failOperation(TemplateOperationEntity operation, TemplateMediaAssetEntity asset,
                               WhatsAppTemplateException error) {
        if (error.retryable()) {
            operationMapper.markUnknown(operation.getId(), error.code(), error.getMessage(),
                    now().plus(1, ChronoUnit.MINUTES));
            operation.setOperationStatus(OperationStatus.SUBMISSION_UNKNOWN.name());
            operation.setErrorCode(error.code());
            if (asset != null) {
                asset.setAssetStatus("ATTACHMENT_UNKNOWN");
                mediaMapper.updateById(asset);
            }
        } else {
            operationMapper.markFailed(operation.getId(), error.providerRequestId(), error.code(),
                    error.getMessage(), now());
            operation.setOperationStatus(OperationStatus.FAILED.name());
            operation.setProviderRequestId(error.providerRequestId());
            operation.setErrorCode(error.code());
            if (asset != null) {
                mediaMapper.markOrphaned(asset.getId());
            }
        }
    }

    private void succeed(TemplateOperationEntity operation, String templateCode, String requestId) {
        operationMapper.markSucceeded(operation.getId(), templateCode, requestId, now());
        operation.setProviderTemplateId(templateCode);
        operation.setProviderRequestId(requestId);
        operation.setOperationStatus(OperationStatus.SUCCEEDED.name());
        operation.setCompletedAt(now());
    }

    private void attach(TemplateMediaAssetEntity asset) {
        if (asset != null) {
            mediaMapper.markAttached(asset.getId(), now());
            asset.setAssetStatus("ATTACHED");
            asset.setAttachedAt(now());
        }
    }

    private void auditOperation(TemplateOperationEntity operation, UUID actorUserId, String traceId) {
        audit("WHATSAPP_TEMPLATE_" + operation.getOperationType(), "TEMPLATE_OPERATION", operation.getId(),
                actorUserId, "{}", json(operationView(operation)),
                auditResult(OperationStatus.valueOf(operation.getOperationStatus())), traceId);
    }

    private void audit(String action, String resourceType, UUID resourceId, UUID actorUserId,
                       String before, String after, String result, String traceId) {
        AuditLogEntity audit = new AuditLogEntity();
        audit.setId(UUID.randomUUID());
        audit.setActorUserId(actorUserId);
        audit.setAction(action);
        audit.setResourceType(resourceType);
        audit.setResourceId(resourceId);
        audit.setBeforeSummaryJsonb(before);
        audit.setAfterSummaryJsonb(after);
        audit.setResult(result);
        audit.setTraceId(traceId);
        audit.setOccurredAt(now());
        auditLogMapper.insert(audit);
    }

    private OperationView operationView(TemplateOperationEntity operation) {
        return new OperationView(operation.getId(), OperationType.valueOf(operation.getOperationType()),
                OperationStatus.valueOf(operation.getOperationStatus()), operation.getProviderTemplateId(),
                operation.getProviderRequestId(), operation.getErrorCode());
    }

    private static Map<String, Object> templateSummary(TemplateEntity entity) {
        return Map.of("templateCode", entity.getProviderTemplateId(), "language", entity.getLanguageCode(),
                "status", entity.getStatus() == null ? "" : entity.getStatus(),
                "allowSend", Boolean.TRUE.equals(entity.getAllowSend()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize template lifecycle state", e);
        }
    }

    private Instant now() {
        return clock.instant();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String auditResult(OperationStatus status) {
        return switch (status) {
            case SUCCEEDED -> "success";
            case FAILED -> "failed";
            case SUBMISSION_UNKNOWN, PROCESSING -> "unknown";
        };
    }

    private static WhatsAppTemplateException validation(String field, String message) {
        return WhatsAppTemplateException.validation(Map.of(field, message));
    }

    private static WhatsAppTemplateException immutableName() {
        return new WhatsAppTemplateException("TEMPLATE_NAME_IMMUTABLE", HttpStatus.BAD_REQUEST,
                "WhatsApp template name cannot be changed", Map.of("name", "cannot be changed after template creation"),
                null, false);
    }

    /**
     * AllowSend 在 CAMS 侧<b>只对营销模板成立</b>，所以「暂停发送」对一个非营销模板不是「可能失败」，
     * 而是根本不成立 —— 必须在打给 CAMS 之前挡住。
     *
     * <h2>判据（2026-09-29 实测，不是推测）</h2>
     * 对同一个通知类（{@code UTILITY}）模板、同一把空间凭据，只改 allowSend：
     * <ul>
     *   <li>{@code allowSend=true} → CAMS 返回成功（历史 5 次成功记录也全是 true）；</li>
     *   <li>{@code allowSend=false} → CAMS 必然返回 {@code ERR-COMMON-001} / {@code code: 400, System error}，
     *       被网关包成 502 {@code TEMPLATE_PROVIDER_ERROR} 抛给用户，用户只看得到一串英文 request id。</li>
     * </ul>
     * 本仓全部 20 个模板都是 {@code UTILITY}，所以「暂停发送」这个动作在本租户里从来没有成功过一次。
     *
     * <p>这条判据与既有口径是同一件事，不是新增约束：{@code AliyunChatAppTemplateGateway.templateAllowsSend(...)}
     * 早就写着「非 MARKETING 模板不看 allowSend」，同步逻辑也会把非营销模板的 allowSend 直接改回来。
     * 也就是说，即使 CAMS 收下了 {@code false}，本地下一轮同步也会把「已暂停」抹掉。
     */
    private static boolean pausable(String category) {
        return category != null && "MARKETING".equalsIgnoreCase(category.trim());
    }

    private static WhatsAppTemplateException pauseUnsupported(String category) {
        return business("TEMPLATE_PAUSE_UNSUPPORTED", HttpStatus.CONFLICT,
                "无法暂停该模板：WhatsApp 只允许暂停营销模板，当前模板类别是「"
                        + (category == null || category.isBlank() ? "未知" : category)
                        + "」。通知类与验证类模板的发送状态由 Meta 按质量评分自动管理。");
    }

    private static WhatsAppTemplateException business(String code, HttpStatus status, String message) {
        return new WhatsAppTemplateException(code, status, message, Map.of(), null, false);
    }

    private static WhatsAppTemplateException providerRejected(String code, String requestId) {
        return new WhatsAppTemplateException(code, HttpStatus.BAD_GATEWAY,
                "Provider did not confirm the operation", Map.of(), requestId, false);
    }

    public record OperationView(UUID operationId, OperationType operationType, OperationStatus operationStatus,
                                String templateCode, String providerRequestId, String errorCode) {
    }

    public record OperationHistoryView(UUID operationId, String operationType, String operationStatus,
                                       String templateCode, String language, String providerRequestId,
                                       String errorCode, String errorMessage, String traceId, UUID actorUserId,
                                       Instant startedAt, Instant completedAt) {
    }

    private record PreparedCommand(TemplateCommand providerCommand, TemplateMediaAssetEntity asset) {
    }

    private record BeginOperation(TemplateOperationEntity operation, boolean existing) {
    }
}
