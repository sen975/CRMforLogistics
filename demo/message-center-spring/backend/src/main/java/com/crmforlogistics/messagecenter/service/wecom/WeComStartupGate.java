package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWeComEnabled
public final class WeComStartupGate {
    private final AtomicBoolean open = new AtomicBoolean();
    private volatile String failureCode;
    private volatile String failureMessage;

    public void open() {
        failureCode = null;
        failureMessage = null;
        open.set(true);
    }

    public void fail(String code, String message) {
        if (code == null || !code.matches("[A-Z0-9_]{1,128}")) {
            throw new IllegalArgumentException("startup gate failure code is invalid");
        }
        failureMessage = message == null || message.isBlank()
                ? "企业微信授权审计需要人工对账" : message;
        failureCode = code;
        open.set(false);
    }

    public void requireOpen() {
        if (!open.get()) {
            String code = failureCode;
            throw new WeComException(code == null ? "WECOM_STARTUP_MIGRATION_PENDING" : code, 503,
                    code == null ? "企业微信凭据迁移尚未完成" : failureMessage);
        }
    }
}
