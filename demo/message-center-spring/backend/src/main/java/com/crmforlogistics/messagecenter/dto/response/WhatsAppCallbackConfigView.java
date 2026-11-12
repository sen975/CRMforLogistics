package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WhatsAppCallbackConfigView(
        UUID scopeId,
        List<PhoneConfig> phoneConfigs,
        AccountConfig accountConfig,
        String ingressPath
) {
    public record PhoneConfig(
            UUID channelAccountId,
            String maskedPhone,
            String desiredUpCallbackUrl,
            String desiredStatusCallbackUrl,
            String httpFlag,
            String queueFlag,
            String providerState,
            String lastApplyStatus,
            String lastErrorCode,
            Instant lastAppliedAt,
            long version
    ) { }

    public record AccountConfig(
            String desiredStatusCallbackUrl,
            String httpFlag,
            String queueFlag,
            String providerState,
            String lastApplyStatus,
            String lastErrorCode,
            Instant lastAppliedAt,
            long version
    ) { }
}
