package com.crmforlogistics.messagecenter.dto.response;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;

import java.time.Instant;
import java.util.UUID;

public record ChannelAccountSummary(
        UUID id, String channelType, String name, String accountIdentifier,
        String authStatus, String syncStatus, Instant lastSyncedAt,
        Instant createdAt, String onboardingMode, UUID providerScopeId) {

    public static ChannelAccountSummary from(ChannelAccountEntity e) {
        return new ChannelAccountSummary(e.getId(), e.getChannelType(), e.getName(),
                e.getAccountIdentifier(), e.getAuthStatus(), e.getSyncStatus(),
                e.getLastSyncedAt(), e.getCreatedAt(), e.getOnboardingMode(), e.getProviderScopeId());
    }
}
