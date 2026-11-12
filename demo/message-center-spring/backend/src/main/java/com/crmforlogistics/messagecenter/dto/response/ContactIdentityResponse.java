package com.crmforlogistics.messagecenter.dto.response;

import java.util.UUID;

public record ContactIdentityResponse(
        UUID id,
        String channelType,
        String identityScope,
        String identityValue,
        String displayName
) {}
