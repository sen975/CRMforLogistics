package com.crmforlogistics.wecomchatdata;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public final class SummaryAbility {
    private static final int INTERNAL_ERROR = 710660;
    private static final int MAX_INPUT_BYTES = 1_048_576;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_SUMMARY_BYTES = 65_536;
    private static final Set<String> INPUT_FIELDS = Set.of("operation", "jobid", "msg_list");
    private static final Set<String> MESSAGE_FIELDS = Set.of("msgid", "secret_key");
    private final SdkInvoker sdk;

    public SummaryAbility(SdkInvoker sdk) {
        this.sdk = sdk;
    }

    public String process(String rawInput) {
        try {
            if (rawInput == null || utf8Length(rawInput) > MAX_INPUT_BYTES) return internalError();
            JSONObject input = JSON.parseObject(rawInput);
            if (input == null || !INPUT_FIELDS.equals(input.keySet())) return internalError();
            String operation = requiredString(input, "operation", 16);
            String jobId = optionalString(input, "jobid", 512);
            JSONArray messages = input.getJSONArray("msg_list");
            if (messages == null) return internalError();
            if ("submit".equals(operation)) return submit(jobId, messages);
            if ("poll".equals(operation)) return poll(jobId, messages);
            return internalError();
        } catch (Exception exception) {
            return internalError();
        }
    }

    private String submit(String jobId, JSONArray messages) throws Exception {
        if (!jobId.isEmpty() || messages.isEmpty() || messages.size() > 1000) return internalError();
        Set<String> messageIds = new HashSet<>();
        JSONArray sdkMessages = new JSONArray(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            JSONObject message = messages.getJSONObject(index);
            if (message == null || !MESSAGE_FIELDS.equals(message.keySet())) return internalError();
            String messageId = requiredString(message, "msgid", 256);
            String secretKey = requiredString(message, "secret_key", 512);
            if (!messageIds.add(messageId)) return internalError();
            JSONObject encryption = new JSONObject();
            encryption.put("secret_key", secretKey);
            JSONObject sdkMessage = new JSONObject();
            sdkMessage.put("msgid", messageId);
            sdkMessage.put("encrypt_info", encryption);
            sdkMessages.add(sdkMessage);
        }
        JSONObject request = new JSONObject();
        request.put("msg_list", sdkMessages);
        InvocationResult invocation = sdk.invoke("create_summary_task", request.toJSONString());
        if (!validInvocation(invocation, "create_summary_task")) return internalError();
        JSONObject response = JSON.parseObject(invocation.response());
        int errcode = integer(response, "errcode");
        if (errcode != 0) return officialError(errcode);
        if (!emptyFailureList(response)) return internalError();
        String returnedJobId = requiredString(response, "jobid", 512);
        return response(0, "ok", 0, returnedJobId, "");
    }

    private String poll(String jobId, JSONArray messages) throws Exception {
        if (jobId.isBlank() || !messages.isEmpty()) return internalError();
        JSONObject request = new JSONObject();
        request.put("jobid", jobId);
        InvocationResult invocation = sdk.invoke("get_summary_result", request.toJSONString());
        if (!validInvocation(invocation, "get_summary_result")) return internalError();
        JSONObject source = JSON.parseObject(invocation.response());
        int errcode = integer(source, "errcode");
        if (errcode != 0) return officialError(errcode);
        if (!emptyFailureList(source)) return internalError();
        int status = integer(source, "status");
        if (status == 0) return response(0, "ok", 0, jobId, "");
        if (status == 2) return response(0, "ok", 2, jobId, "");
        if (status != 1) return internalError();
        String summary = requiredString(source, "response_data", 65_536);
        if (utf8Length(summary) > MAX_SUMMARY_BYTES) return internalError();
        return response(0, "ok", 1, jobId, summary);
    }

    private static boolean validInvocation(InvocationResult invocation, String expectedApi) {
        return invocation != null && invocation.returnCode() == 0
                && expectedApi.equals(invocation.apiName()) && invocation.response() != null
                && utf8Length(invocation.response()) <= MAX_RESPONSE_BYTES;
    }

    private static boolean emptyFailureList(JSONObject response) {
        if (!response.containsKey("fail_list")) return true;
        JSONArray failures = response.getJSONArray("fail_list");
        return failures != null && failures.isEmpty();
    }

    private static int integer(JSONObject object, String field) {
        if (object == null || !(object.get(field) instanceof Number number)) {
            throw new IllegalArgumentException("integer missing");
        }
        return number.intValue();
    }

    private static String optionalString(JSONObject object, String field, int maximum) {
        Object raw = object.get(field);
        if (!(raw instanceof String value) || value.length() > maximum) {
            throw new IllegalArgumentException("string invalid");
        }
        return value;
    }

    private static String requiredString(JSONObject object, String field, int maximum) {
        String value = optionalString(object, field, maximum);
        if (value.isBlank()) throw new IllegalArgumentException("string missing");
        return value;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String officialError(int errcode) {
        if (errcode <= 0 || errcode > 9_999_999) return internalError();
        return response(errcode, "summary request failed", 2, "", "");
    }

    private static String internalError() {
        return response(INTERNAL_ERROR, "request rejected", 2, "", "");
    }

    private static String response(int errcode, String errmsg, int status, String jobId, String summary) {
        JSONObject output = new JSONObject();
        output.put("errcode", errcode);
        output.put("errmsg", errmsg);
        output.put("status", status);
        output.put("jobid", jobId);
        output.put("summary", summary);
        return output.toJSONString();
    }

    public interface SdkInvoker {
        InvocationResult invoke(String apiName, String request) throws Exception;
    }

    public record InvocationResult(int returnCode, String response, String apiName) {}
}
