package com.crmforlogistics.wecomchatdata;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilityDispatcherTest {
    @Test
    void routesOnlyTheRegisteredAbilityToItsSdkApi() {
        AbilityDispatcher dispatcher = new AbilityDispatcher();
        AtomicReference<String> apiName = new AtomicReference<>();

        String output = dispatcher.dispatch("conversation_daily_summary",
                "{\"operation\":\"poll\",\"jobid\":\"job-1\",\"msg_list\":[]}",
                (api, request) -> {
                    apiName.set(api);
                    return new AbilityDispatcher.InvocationResult(0,
                            "{\"errcode\":0,\"errmsg\":\"ok\",\"status\":0,\"fail_list\":[]}");
                });

        assertEquals("get_summary_result", apiName.get());
        assertEquals(0, JSON.parseObject(output).getIntValue("errcode"));
        assertTrue(dispatcher.dispatch("other", "{}", (api, request) -> null)
                .contains("\"errcode\":710660"));
    }
}
