package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.mapper.WeComInstallationMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComChannelAccountReconcilerTest {

    private final WeComInstallationMapper installations = mock(WeComInstallationMapper.class);
    private final WeComChannelAccountLifecycle channelAccounts =
            mock(WeComChannelAccountLifecycle.class);
    private final WeComChannelAccountReconciler reconciler =
            new WeComChannelAccountReconciler(installations, channelAccounts);

    @Test
    void noActiveInstallationDoesNotCreateAChannelAccount() {
        when(installations.findActiveForReconciliation()).thenReturn(List.of());

        reconciler.run(null);

        verify(channelAccounts, never()).ensureActive(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void uniqueActiveInstallationCreatesOrReactivatesItsChannelAccount() {
        WeComInstallationEntity installation = active("ww-corp");
        when(installations.findActiveForReconciliation()).thenReturn(List.of(installation));

        reconciler.run(null);

        verify(channelAccounts).ensureActive("ww-corp");
    }

    @Test
    void multipleActiveInstallationsViolateTheSingleCorpBoundary() {
        when(installations.findActiveForReconciliation()).thenReturn(List.of(
                active("ww-corp-a"), active("ww-corp-b")));

        assertThatThrownBy(() -> reconciler.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WECOM_SINGLE_CORP_VIOLATION");

        verify(channelAccounts, never()).ensureActive(org.mockito.ArgumentMatchers.any());
    }

    private static WeComInstallationEntity active(String authCorpId) {
        WeComInstallationEntity entity = new WeComInstallationEntity();
        entity.setAuthCorpId(authCorpId);
        entity.setAuthStatus("ACTIVE");
        return entity;
    }
}
