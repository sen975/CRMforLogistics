package com.crmforlogistics.messagecenter.service.channel;

import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppMessageSyncService;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppTemplateSyncService;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppProviderScopeService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WhatsApp 渠道定义（{@code channel_accounts.channel_type = 'chatapp'}）。
 *
 * <h2>为什么 key 是 chatapp 而不是 whatsapp</h2>
 * 落库值与对外协议一直用 {@code chatapp}；{@code whatsapp} 是历史命名，底层与 chatapp
 * 同为阿里云 CAMS，因此登记为 {@link #aliases()}，两者共用同一份凭证解析与网关。
 * 这条「命名把同一供应商拆成两个域」的问题，在这里收口成一个定义。
 *
 * <h2>为什么不能走通用设置入口</h2>
 * WhatsApp 账号必须经管理员入驻流程（CAMS 平台配置 → 号码校验 → 分配）创建，
 * {@code POST /api/channel-accounts} 自助绑定会绕过能力校验，所以
 * {@link #assertBindableFromSettings()} 直接拒绝。
 */
@Service
public class ChatAppChannelType implements ChannelType {

    private static final Set<String> CREDENTIAL_FIELDS = Set.of(
            "accessKeyId", "accessKeySecret", "region", "endpoint", "custSpaceId", "chatappFrom");
    private static final Set<String> SECRET_FIELDS = Set.of("accessKeySecret");

    private final ChatAppMessageSyncService messageSyncService;
    private final ChatAppTemplateSyncService templateSyncService;
    private final WhatsAppProviderScopeService providerScopeService;

    public ChatAppChannelType(ChatAppMessageSyncService messageSyncService,
                              ChatAppTemplateSyncService templateSyncService,
                              WhatsAppProviderScopeService providerScopeService) {
        this.messageSyncService = messageSyncService;
        this.templateSyncService = templateSyncService;
        this.providerScopeService = providerScopeService;
    }

    @Override
    public String key() {
        return "chatapp";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("whatsapp");
    }

    @Override
    public Set<String> credentialFields() {
        return CREDENTIAL_FIELDS;
    }

    @Override
    public Set<String> secretFields() {
        return SECRET_FIELDS;
    }

    /** 手机号落库时忽略首尾空白、空格、括号与连字符，避免同一号码因格式差异重复建号。 */
    @Override
    public String normalizeIdentifier(String identifier) {
        return trimmed(identifier).replaceAll("[\\s()\\-]", "");
    }

    @Override
    public void assertBindableFromSettings() {
        throw new ChannelAccountException("WHATSAPP_ONBOARDING_REQUIRED", HttpStatus.CONFLICT);
    }

    @Override
    public void assertScope(Map<String, String> credentials) {
        providerScopeService.assertCompatible(credentials.get("custSpaceId"));
    }

    @Override
    public void bindScope(ChannelAccountEntity account) {
        providerScopeService.bind(account);
    }

    @Override
    public Object syncAccount(UUID ownerId, UUID accountId) {
        var messageResult = messageSyncService.runAccount(accountId);
        var templateResult = templateSyncService.runAccount(accountId);
        return Map.of(
                "messageSync", messageResult,
                "templateSync", templateResult);
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
