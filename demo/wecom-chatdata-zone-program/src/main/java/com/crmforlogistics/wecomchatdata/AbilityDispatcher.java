package com.crmforlogistics.wecomchatdata;

public final class AbilityDispatcher {
    private final AbilityIdMatcher abilities = AbilityIdMatcher.fromBuildBinding();

    public String dispatch(String abilityId, String data, SdkInvoker sdk) {
        if (!abilities.matches(abilityId) || sdk == null) return error();
        if (AbilityIdMatcher.VIEWER_SYNC.equals(abilityId)) {
            SyncMessageAbility ability = new SyncMessageAbility(request -> {
                InvocationResult result = sdk.invoke("sync_msg", request);
                return result == null ? null : new SyncMessageAbility.InvocationResult(
                        result.returnCode(), result.response(), "sync_msg");
            });
            return ability.process(data);
        }
        if (AbilityIdMatcher.DAILY_SUMMARY.equals(abilityId)) {
            SummaryAbility ability = new SummaryAbility((apiName, request) -> {
                InvocationResult result = sdk.invoke(apiName, request);
                return result == null ? null : new SummaryAbility.InvocationResult(
                        result.returnCode(), result.response(), apiName);
            });
            return ability.process(data);
        }
        return error();
    }

    private static String error() {
        return "{\"errcode\":710660,\"errmsg\":\"request rejected\"}";
    }

    public interface SdkInvoker {
        InvocationResult invoke(String apiName, String request) throws Exception;
    }

    public record InvocationResult(int returnCode, String response) {}
}
