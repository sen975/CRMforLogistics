package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsException;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentialsResolver;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WhatsAppProviderScopeService {
    static final String PROVIDER = "ALIYUN_CAMS";

    private final ChannelAccountMapper accountMapper;
    private final WhatsAppProviderScopeMapper scopeMapper;
    private final ChatAppAccountCredentialsResolver credentialsResolver;

    public WhatsAppProviderScopeService(ChannelAccountMapper accountMapper,
                                        WhatsAppProviderScopeMapper scopeMapper,
                                        ChatAppAccountCredentialsResolver credentialsResolver) {
        this.accountMapper = accountMapper;
        this.scopeMapper = scopeMapper;
        this.credentialsResolver = credentialsResolver;
    }

    @Transactional
    public ScopeAccount requireOwnedActive(UUID userId) {
        List<ChannelAccountEntity> accounts = accountMapper.findByOwnerAndChannelType(userId, "chatapp");
        if (accounts == null || accounts.isEmpty()) {
            throw failure("WHATSAPP_ACCOUNT_REQUIRED", HttpStatus.CONFLICT, "请先绑定有效的 WhatsApp 账号");
        }
        if (accounts.size() != 1) {
            throw failure("WHATSAPP_ACCOUNT_AMBIGUOUS", HttpStatus.CONFLICT, "当前用户存在多个 WhatsApp 账号");
        }
        ChannelAccountEntity account = accounts.get(0);
        verifyActive(account);
        return new ScopeAccount(account, bind(account));
    }

    @Transactional
    public ScopeAccount requireAccount(UUID accountId) {
        ChannelAccountEntity account = accountMapper.selectById(accountId);
        if (account == null || account.getDeletedAt() != null || !isChatApp(account)) {
            throw failure("WHATSAPP_ACCOUNT_REQUIRED", HttpStatus.NOT_FOUND, "WhatsApp 账号不存在");
        }
        verifyActive(account);
        return new ScopeAccount(account, bind(account));
    }

    /**
     * A write into a named space needs that space's credentials, which any of its accounts carries.
     * The caller's own account is preferred, so an ordinary write keeps using the same number it
     * always did, and only a space the caller owns nothing in falls back to the space's others.
     */
    @Transactional
    public ScopeAccount requireScopeAccount(UUID userId, UUID scopeId) {
        WhatsAppProviderScopeEntity scope = requireScope(scopeId);
        List<ChannelAccountEntity> owned = accountMapper.findByOwnerAndChannelType(userId, "chatapp");
        if (owned != null) {
            for (ChannelAccountEntity account : owned) {
                if (scope.getId().equals(account.getProviderScopeId()) && usable(account)) {
                    return new ScopeAccount(account, scope);
                }
            }
        }
        for (ChannelAccountEntity account : accountMapper.findActiveByScope(scope.getId())) {
            if (usable(account)) {
                return new ScopeAccount(account, scope);
            }
        }
        throw failure("WHATSAPP_SCOPE_ACCOUNT_REQUIRED", HttpStatus.CONFLICT,
                "该 CAMS 空间下没有可用于提交的 WhatsApp 账号");
    }

    /** Resolves a space the caller named explicitly, e.g. an administrator switching between CAMS spaces. */
    public WhatsAppProviderScopeEntity requireScope(UUID scopeId) {
        WhatsAppProviderScopeEntity scope = scopeId == null ? null : scopeMapper.selectById(scopeId);
        if (scope == null || !PROVIDER.equalsIgnoreCase(scope.getProvider())) {
            throw failure("WHATSAPP_PROVIDER_SCOPE_UNAVAILABLE", HttpStatus.NOT_FOUND, "WhatsApp 模板空间不存在");
        }
        return scope;
    }

    @Transactional
    public WhatsAppProviderScopeEntity bind(ChannelAccountEntity account) {
        verifyActive(account);
        final String custSpaceId;
        try {
            custSpaceId = credentialsResolver.resolve(account).custSpaceId();
        } catch (ChatAppAccountCredentialsException exception) {
            throw failure(exception.code(), HttpStatus.CONFLICT, "WhatsApp 账号凭证不可用");
        }
        assertCompatible(custSpaceId);
        scopeMapper.insertIgnore(PROVIDER, custSpaceId);
        WhatsAppProviderScopeEntity scope = scopeMapper.findByProviderAndExternalScopeId(PROVIDER, custSpaceId);
        if (scope == null || scope.getId() == null) {
            throw failure("WHATSAPP_PROVIDER_SCOPE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "WhatsApp 模板空间暂不可用");
        }
        if (isEmptyConfig(scope.getEncryptedConfig()) && !isBlank(account.getEncryptedConfig())
                && !"{}".equals(account.getEncryptedConfig().trim())) {
            if (scopeMapper.updateEncryptedConfigIfEmpty(scope.getId(), account.getEncryptedConfig()) != 1) {
                throw failure("WHATSAPP_PROVIDER_SCOPE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                        "WhatsApp 企业凭证暂不可用");
            }
            scope.setEncryptedConfig(account.getEncryptedConfig());
        }
        if (accountMapper.bindProviderScope(account.getId(), scope.getId()) != 1) {
            throw failure("WHATSAPP_ACCOUNT_UNAVAILABLE", HttpStatus.CONFLICT, "WhatsApp 账号不可用");
        }
        account.setProviderScopeId(scope.getId());
        return scope;
    }

    /**
     * Each CAMS space keeps its own template library, so an account may name any space; the only
     * thing left to check is that it names one at all.
     */
    public void assertCompatible(String custSpaceId) {
        if (custSpaceId == null || custSpaceId.isBlank()) {
            throw failure("CHATAPP_ACCOUNT_CREDENTIALS_MISSING", HttpStatus.BAD_REQUEST,
                    "WhatsApp 账号缺少必要凭证");
        }
    }

    private static void verifyActive(ChannelAccountEntity account) {
        if (!isChatApp(account)) {
            throw failure("WHATSAPP_ACCOUNT_REQUIRED", HttpStatus.NOT_FOUND, "WhatsApp 账号不存在");
        }
        if (!"active".equalsIgnoreCase(account.getAuthStatus())) {
            throw failure("WHATSAPP_ACCOUNT_INACTIVE", HttpStatus.CONFLICT, "WhatsApp 账号未启用");
        }
    }

    /** A candidate is only usable while it is still bound to the space and able to reach CAMS. */
    private static boolean usable(ChannelAccountEntity account) {
        return isChatApp(account) && account.getDeletedAt() == null
                && "active".equalsIgnoreCase(account.getAuthStatus());
    }

    private static boolean isChatApp(ChannelAccountEntity account) {
        return account != null && ("chatapp".equalsIgnoreCase(account.getChannelType())
                || "whatsapp".equalsIgnoreCase(account.getChannelType()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isEmptyConfig(String value) {
        return isBlank(value) || "{}".equals(value.trim());
    }

    private static WhatsAppTemplateException failure(String code, HttpStatus status, String message) {
        return new WhatsAppTemplateException(code, status, message, Map.of(), null, false);
    }

    public record ScopeAccount(ChannelAccountEntity account, WhatsAppProviderScopeEntity scope) { }
}
