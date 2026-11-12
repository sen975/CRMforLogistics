package com.crmforlogistics.messagecenter.service.wecom;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WeComGroupNameRefreshServiceTest {
    @Test
    void automaticRefreshIsOnlyDueAfterTwentyFourHours() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");

        assertThat(WeComGroupNameRefreshService.isAutomaticRefreshDue(null, now)).isTrue();
        assertThat(WeComGroupNameRefreshService.isAutomaticRefreshDue(now.minusSeconds(86_399), now)).isFalse();
        assertThat(WeComGroupNameRefreshService.isAutomaticRefreshDue(now.minusSeconds(86_400), now)).isTrue();
    }

    @Test
    void classifiesPermissionAndEmptyNameAsUnavailable() {
        assertThat(WeComGroupNameRefreshService.classifyFailure("WECOM_API_PERMISSION_DENIED", 403))
                .isEqualTo(WeComGroupNameRefreshService.FailureDisposition.UNAVAILABLE);
        assertThat(WeComGroupNameRefreshService.emptyNameDisposition())
                .isEqualTo(WeComGroupNameRefreshService.FailureDisposition.UNAVAILABLE);
        assertThat(WeComGroupNameRefreshService.classifyFailure("WECOM_UPSTREAM_UNAVAILABLE", 503))
                .isEqualTo(WeComGroupNameRefreshService.FailureDisposition.RETRY);
    }
}
