package com.crmforlogistics.wecomchatdata;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncMessageAbilityTest {
    @Test
    void invokesOnlySyncMessageWithFixedModeAndProjectsViewerFields() {
        AtomicReference<String> sdkRequest = new AtomicReference<>();
        SyncMessageAbility ability = new SyncMessageAbility(request -> {
            sdkRequest.set(request);
            return new SyncMessageAbility.InvocationResult(0, """
                    {"errcode":0,"errmsg":"ok","has_more":0,"next_cursor":"cursor-2","msg_list":[
                      {"msgid":"msg-1","sender":{"type":1,"id":"employee-1"},
                       "receiver_list":[{"type":2,"id":"external-1"}],"chatid":"","send_time":123,
                       "msgtype":2,"content":"must-not-leave-zone","extra_info":{"topic":"no"},
                       "service_encrypt_info":{"encrypted_secret_key":"cipher","public_key_ver":1}}
                    ]}
                    """, "sync_msg");
        });

        String output = ability.process("{\"cursor\":\"cursor-1\",\"limit\":200}");

        JSONObject request = JSON.parseObject(sdkRequest.get());
        assertEquals("cursor-1", request.getString("cursor"));
        assertEquals(200, request.getIntValue("limit"));
        assertEquals(0, request.getIntValue("mode"));
        JSONObject response = JSON.parseObject(output);
        assertEquals(0, response.getIntValue("errcode"));
        assertEquals("msg-1", response.getJSONArray("msg_list").getJSONObject(0).getString("msgid"));
        assertFalse(output.contains("must-not-leave-zone"));
        assertFalse(output.contains("topic"));
    }

    @Test
    void rejectsUnknownInputWithoutCallingSdk() {
        AtomicInteger calls = new AtomicInteger();
        SyncMessageAbility ability = new SyncMessageAbility(request -> {
            calls.incrementAndGet();
            return new SyncMessageAbility.InvocationResult(0, "{}", "sync_msg");
        });

        JSONObject output = JSON.parseObject(ability.process("{\"limit\":200,\"api_name\":\"search_msg\"}"));

        assertEquals(710660, output.getIntValue("errcode"));
        assertEquals(0, calls.get());
    }

    @Test
    void acceptsFinalPageWithoutNextCursor() {
        SyncMessageAbility ability = new SyncMessageAbility(request ->
                new SyncMessageAbility.InvocationResult(0, """
                        {"errcode":0,"errmsg":"ok","has_more":0,"msg_list":[]}
                        """, "sync_msg"));

        JSONObject output = JSON.parseObject(ability.process("{\"limit\":200}"));

        assertEquals(0, output.getIntValue("errcode"));
        assertEquals("", output.getString("next_cursor"));
    }

    @Test
    void boundsLimitCursorAndSdkResponse() {
        SyncMessageAbility ability = new SyncMessageAbility(request ->
                new SyncMessageAbility.InvocationResult(-910006, "secret upstream body", "sync_msg"));

        String invalidLimit = ability.process("{\"limit\":201}");
        String invalidCursor = ability.process("{\"limit\":200,\"cursor\":\"" + "x".repeat(129) + "\"}");
        String sdkFailure = ability.process("{\"limit\":200}");

        assertTrue(invalidLimit.contains("\"errcode\":710660"));
        assertTrue(invalidCursor.contains("\"errcode\":710660"));
        assertTrue(sdkFailure.contains("\"errcode\":710660"));
        assertFalse(sdkFailure.contains("secret upstream body"));
    }
}
