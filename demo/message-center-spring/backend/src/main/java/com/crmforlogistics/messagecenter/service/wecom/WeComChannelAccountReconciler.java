package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.mapper.WeComInstallationMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComChannelAccountReconciler implements ApplicationRunner {
    private final WeComInstallationMapper installations;
    private final WeComChannelAccountLifecycle channelAccounts;

    public WeComChannelAccountReconciler(WeComInstallationMapper installations,
                                         WeComChannelAccountLifecycle channelAccounts) {
        this.installations = Objects.requireNonNull(installations);
        this.channelAccounts = Objects.requireNonNull(channelAccounts);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        List<WeComInstallationEntity> active = installations.findActiveForReconciliation();
        if (active == null || active.isEmpty()) return;
        if (active.size() != 1) {
            throw new IllegalStateException("WECOM_SINGLE_CORP_VIOLATION");
        }
        channelAccounts.ensureActive(active.get(0).getAuthCorpId());
    }
}
