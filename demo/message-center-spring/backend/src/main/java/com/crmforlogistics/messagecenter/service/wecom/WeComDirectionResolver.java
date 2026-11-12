package com.crmforlogistics.messagecenter.service.wecom;

import java.util.List;

/** Computes message direction from the logged-in WeCom member's point of view. */
public final class WeComDirectionResolver {
    private WeComDirectionResolver() {}

    public static String resolve(String viewerWecomUserId,
                                 WeComChatDataNormalizer.PartyRef sender,
                                 List<WeComChatDataNormalizer.PartyRef> receivers) {
        if (viewerWecomUserId == null || viewerWecomUserId.isBlank()
                || sender == null || receivers == null) {
            return "unknown";
        }
        boolean senderIsViewer = "EMPLOYEE".equals(sender.partyType())
                && viewerWecomUserId.equals(sender.providerPartyId());
        if (senderIsViewer) return "outbound";
        boolean viewerReceives = receivers.stream().anyMatch(receiver ->
                receiver != null
                        && "EMPLOYEE".equals(receiver.partyType())
                        && viewerWecomUserId.equals(receiver.providerPartyId()));
        return viewerReceives ? "inbound" : "unknown";
    }
}
