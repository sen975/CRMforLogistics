package com.crmforlogistics.wecomchatdata;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryAbilityTest {
    @Test
    void submitsOnlyTheOfficialSummaryTaskContract() {
        AtomicReference<String> apiName = new AtomicReference<>();
        AtomicReference<String> sdkRequest = new AtomicReference<>();
        SummaryAbility ability = new SummaryAbility((api, request) -> {
            apiName.set(api);
            sdkRequest.set(request);
            return new SummaryAbility.InvocationResult(0,
                    "{\"errcode\":0,\"errmsg\":\"ok\",\"jobid\":\"job-1\",\"fail_list\":[]}", api);
        });

        String output = ability.process("""
                {"operation":"submit","jobid":"","msg_list":[
                  {"msgid":"msg-1","secret_key":"sensitive-secret"}
                ]}
                """);

        assertEquals("create_summary_task", apiName.get());
        JSONObject request = JSON.parseObject(sdkRequest.get());
        JSONObject reference = request.getJSONArray("msg_list").getJSONObject(0);
        assertEquals("msg-1", reference.getString("msgid"));
        assertEquals("sensitive-secret",
                reference.getJSONObject("encrypt_info").getString("secret_key"));
        JSONObject response = JSON.parseObject(output);
        assertEquals(0, response.getIntValue("errcode"));
        assertEquals(0, response.getIntValue("status"));
        assertEquals("job-1", response.getString("jobid"));
        assertEquals("", response.getString("summary"));
        assertFalse(output.contains("sensitive-secret"));
    }

    @Test
    void pollsAndProjectsOnlyCompletedSummary() {
        SummaryAbility ability = new SummaryAbility((api, request) -> {
            assertEquals("get_summary_result", api);
            assertEquals("job-1", JSON.parseObject(request).getString("jobid"));
            return new SummaryAbility.InvocationResult(0, """
                    {"errcode":0,"errmsg":"ok","status":1,"fail_list":[],
                     "response_data":"客户确认了装运时间。","private":"must-not-leave-zone"}
                    """, api);
        });

        JSONObject output = JSON.parseObject(ability.process(
                "{\"operation\":\"poll\",\"jobid\":\"job-1\",\"msg_list\":[]}"));

        assertEquals(0, output.getIntValue("errcode"));
        assertEquals(1, output.getIntValue("status"));
        assertEquals("job-1", output.getString("jobid"));
        assertEquals("客户确认了装运时间。", output.getString("summary"));
        assertFalse(output.toJSONString().contains("must-not-leave-zone"));
    }

    @Test
    void preservesSafeOfficialInputTooLongCodeForBoundedSplitting() {
        SummaryAbility ability = new SummaryAbility((api, request) ->
                new SummaryAbility.InvocationResult(0,
                        "{\"errcode\":790040,\"errmsg\":\"sensitive upstream detail\"}", api));

        String output = ability.process("""
                {"operation":"submit","jobid":"","msg_list":[
                  {"msgid":"msg-1","secret_key":"secret"}
                ]}
                """);

        JSONObject response = JSON.parseObject(output);
        assertEquals(790040, response.getIntValue("errcode"));
        assertEquals(2, response.getIntValue("status"));
        assertFalse(output.contains("sensitive upstream detail"));
        assertFalse(output.contains("secret"));
    }

    @Test
    void rejectsUnknownFieldsDuplicatesAndOversizedListsWithoutCallingSdk() {
        AtomicInteger calls = new AtomicInteger();
        SummaryAbility ability = new SummaryAbility((api, request) -> {
            calls.incrementAndGet();
            return new SummaryAbility.InvocationResult(0, "{}", api);
        });
        String duplicate = """
                {"operation":"submit","jobid":"","msg_list":[
                  {"msgid":"same","secret_key":"one"},
                  {"msgid":"same","secret_key":"two"}
                ]}
                """;
        String unknown = "{\"operation\":\"poll\",\"jobid\":\"job\",\"msg_list\":[],\"api\":\"sync_msg\"}";
        StringBuilder oversized = new StringBuilder(
                "{\"operation\":\"submit\",\"jobid\":\"\",\"msg_list\":[");
        for (int index = 0; index < 1001; index++) {
            if (index > 0) oversized.append(',');
            oversized.append("{\"msgid\":\"m").append(index)
                    .append("\",\"secret_key\":\"s\"}");
        }
        oversized.append("]}");

        assertTrue(ability.process(duplicate).contains("\"errcode\":710660"));
        assertTrue(ability.process(unknown).contains("\"errcode\":710660"));
        assertTrue(ability.process(oversized.toString()).contains("\"errcode\":710660"));
        assertEquals(0, calls.get());
    }
}
