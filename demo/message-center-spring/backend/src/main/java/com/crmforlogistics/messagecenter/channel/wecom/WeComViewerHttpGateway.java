package com.crmforlogistics.messagecenter.channel.wecom;

public interface WeComViewerHttpGateway {
    TicketResponse fetchCorpJsapiTicket(ResolvedInstallation installation);

    TicketResponse fetchAgentJsapiTicket(ResolvedInstallation installation);

    /** Deprecated legacy login adapter; production login uses exchangeLoginIdentity. */
    String exchangeLoginCode(String code);

    WeComAuthorizationGateway.LoginIdentity exchangeLoginIdentity(ResolvedInstallation installation, String code);

    record TicketResponse(int errcode, String errmsg, String ticket, int expiresIn) {}
}
