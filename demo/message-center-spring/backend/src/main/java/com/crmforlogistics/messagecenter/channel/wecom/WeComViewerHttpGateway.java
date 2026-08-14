package com.crmforlogistics.messagecenter.channel.wecom;

public interface WeComViewerHttpGateway {
    TicketResponse fetchCorpJsapiTicket();

    TicketResponse fetchAgentJsapiTicket();

    TicketResponse fetchCorpJsapiTicket(ResolvedInstallation installation);

    TicketResponse fetchAgentJsapiTicket(ResolvedInstallation installation);

    String exchangeLoginCode(String code);

    WeComAuthorizationGateway.LoginIdentity exchangeLoginIdentity(String code);

    record TicketResponse(int errcode, String errmsg, String ticket, int expiresIn) {}
}
