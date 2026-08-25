package com.crmforlogistics.messagecenter.dto.response;

import java.util.UUID;

public record ChannelCapabilityResponse(
        String channelType,
        UUID channelAccountId,
        String displayName,
        String authStatus
) {}
