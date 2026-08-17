package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWeComEnabled
public final class WeComStartupGate {
    private final AtomicBoolean open = new AtomicBoolean();

    public void open() {
        open.set(true);
    }

    public void requireOpen() {
        if (!open.get()) {
            throw new WeComException("WECOM_STARTUP_MIGRATION_PENDING", 503,
                    "企业微信凭据迁移尚未完成");
        }
    }
}
