package mytype.mycom.mygroup;

import com.crmforlogistics.wecomchatdata.AbilityIdMatcher;
import com.crmforlogistics.wecomchatdata.AbilityDispatcher;
import com.tencent.wework.SpecCallbackSDK;
import com.tencent.wework.SpecSDK;

public final class DemoCallProgramHandler implements UserLogicHandler {
    private static final AbilityIdMatcher ABILITY_IDS = AbilityIdMatcher.fromBuildBinding();
    private static final AbilityDispatcher DISPATCHER = new AbilityDispatcher();

    @Override
    public boolean isValidAblility(String abilityId) {
        return ABILITY_IDS.matches(abilityId);
    }

    @Override
    public String process(SpecCallbackSDK callback) {
        if (callback == null || !isValidAblility(callback.GetAbilityId())) {
            return CommonUtils.getErrorResponse("ability is unavailable");
        }
        return DISPATCHER.dispatch(callback.GetAbilityId(), callback.GetData(), (apiName, request) -> {
            SpecSDK sdk = new SpecSDK(callback);
            sdk.SetRequest(request);
            int returnCode = sdk.Invoke(apiName);
            return new AbilityDispatcher.InvocationResult(returnCode, sdk.GetResponse());
        });
    }
}
