package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComChannelAccountLifecycle {
    private static final int MAX_CORP_ID_LENGTH = 128;
    private static final String ACCOUNT_NAME = "企业微信";

    private final ChannelAccountMapper accounts;

    public WeComChannelAccountLifecycle(ChannelAccountMapper accounts) {
        this.accounts = Objects.requireNonNull(accounts);
    }

    public void ensureActive(String authCorpId) {
        String normalized = requireCorpId(authCorpId);
        if (accounts.upsertWeComAccount(UUID.randomUUID(), normalized, ACCOUNT_NAME) != 1) {
            throw new WeComException("WECOM_CHANNEL_ACCOUNT_WRITE_FAILED", 500,
                    "企业微信渠道账号写入失败");
        }
    }

    public ChannelAccountEntity requireActive(String authCorpId) {
        ChannelAccountEntity account = accounts.selectActiveWeComAccount(requireCorpId(authCorpId));
        if (account == null) {
            throw new WeComException("WECOM_CHANNEL_ACCOUNT_NOT_FOUND", 503,
                    "企业微信渠道账号尚未就绪");
        }
        return account;
    }

    public void disable(String authCorpId) {
        accounts.disableWeComAccount(requireCorpId(authCorpId));
    }

    private static String requireCorpId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > MAX_CORP_ID_LENGTH) {
            throw new IllegalArgumentException("authCorpId is invalid");
        }
        return normalized;
    }
}
