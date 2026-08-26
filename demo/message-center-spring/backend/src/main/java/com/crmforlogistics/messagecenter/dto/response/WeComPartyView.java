package com.crmforlogistics.messagecenter.dto.response;

import java.util.UUID;

/** Sanitized WeCom party projection used by messages and group headers. */
public record WeComPartyView(
        UUID partyId,
        String partyType,
        String providerPartyId,
        String displayName,
        String avatarUrl,
        UUID contactId,
        boolean contactAccessible,
        boolean isCurrentViewer
) {}
