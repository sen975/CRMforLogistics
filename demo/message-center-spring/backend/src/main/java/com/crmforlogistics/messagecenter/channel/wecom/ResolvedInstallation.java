package com.crmforlogistics.messagecenter.channel.wecom;

/**
 * A fully resolved WeCom authorization installation with its plaintext
 * permanent code, shared across the chatdata / viewer / login flows.
 */
public record ResolvedInstallation(String installationId, String suiteId, String authCorpId,
                                   String agentId, String permanentCode, long version) {
}
