package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;

public record ViewerSyncContext(String wecomUserId, ResolvedInstallation installation) {
}
