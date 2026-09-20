package com.crmforlogistics.messagecenter.dto.response;

import java.util.UUID;

/**
 * Maps a WeCom external contact id to the CRM contact of the current account.
 * {@code contactId} is null when the external contact has no CRM contact yet;
 * {@code accessible} mirrors the conversation accessibility rule used by the
 * group participant projection, so the client never offers an entry it cannot open.
 */
public record WeComExternalContactLinkResponse(
        String externalUserId,
        UUID contactId,
        UUID identityId,
        boolean accessible
) {}
