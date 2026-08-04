package com.crmforlogistics.wecomchatdata;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Set;

public final class SyncMessageAbility {
    private static final int INTERNAL_ERROR = 710660;
    private static final int MAX_INPUT_BYTES = 4096;
    private static final int MAX_SDK_RESPONSE_BYTES = 1_048_576;
    private static final Set<String> INPUT_FIELDS = Set.of("cursor", "limit");
    private final SdkInvoker sdk;

    public SyncMessageAbility(SdkInvoker sdk) {
        this.sdk = sdk;
    }

    public String process(String rawInput) {
        try {
            if (rawInput == null || rawInput.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
                return error();
            }
            JSONObject input = JSON.parseObject(rawInput);
            if (input == null || !INPUT_FIELDS.containsAll(input.keySet())
                    || !input.containsKey("cursor") || !input.containsKey("limit")
                    || !(input.get("limit") instanceof Number)) {
                return error();
            }
            int limit = input.getIntValue("limit");
            String cursor = optionalBoundedString(input, "cursor", 128);
            if (limit < 1 || limit > 200) return error();

            JSONObject sdkInput = new JSONObject();
            if (!cursor.isBlank()) sdkInput.put("cursor", cursor);
            sdkInput.put("limit", limit);
            sdkInput.put("mode", 0);
            InvocationResult invocation = sdk.invoke(sdkInput.toJSONString());
            if (invocation == null || invocation.returnCode() != 0
                    || !"sync_msg".equals(invocation.apiName()) || invocation.response() == null
                    || invocation.response().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > MAX_SDK_RESPONSE_BYTES) {
                return error();
            }
            return project(invocation.response(), limit);
        } catch (Exception exception) {
            return error();
        }
    }

    private static String project(String rawResponse, int limit) {
        JSONObject source = JSON.parseObject(rawResponse);
        if (source == null || source.getIntValue("errcode", -1) != 0) return error();
        int hasMore = source.getIntValue("has_more", -1);
        String nextCursor = optionalBoundedString(source, "next_cursor", 128);
        JSONArray messages = source.getJSONArray("msg_list");
        if ((hasMore != 0 && hasMore != 1) || messages == null || messages.size() > limit
                || messages.size() > 200 || (hasMore == 1 && nextCursor.isBlank())) return error();
        JSONArray projectedMessages = new JSONArray(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            JSONObject message = messages.getJSONObject(index);
            JSONObject sender = message == null ? null : message.getJSONObject("sender");
            JSONArray receivers = message == null ? null : message.getJSONArray("receiver_list");
            JSONObject encryption = message == null ? null : message.getJSONObject("service_encrypt_info");
            if (sender == null || receivers == null || receivers.size() > 100 || encryption == null) return error();
            JSONObject projected = new JSONObject();
            projected.put("msgid", requiredBoundedString(message, "msgid", 256));
            projected.put("sender", party(sender));
            JSONArray projectedReceivers = new JSONArray(receivers.size());
            for (int receiverIndex = 0; receiverIndex < receivers.size(); receiverIndex++) {
                projectedReceivers.add(party(receivers.getJSONObject(receiverIndex)));
            }
            projected.put("receiver_list", projectedReceivers);
            String chatId = optionalBoundedString(message, "chatid", 256);
            projected.put("chatid", chatId);
            long sendTime = requiredNonNegativeLong(message, "send_time");
            int msgType = requiredInt(message, "msgtype");
            projected.put("send_time", sendTime);
            projected.put("msgtype", msgType);
            JSONObject projectedEncryption = new JSONObject();
            projectedEncryption.put("encrypted_secret_key",
                    requiredBoundedString(encryption, "encrypted_secret_key", 4096));
            int publicKeyVersion = requiredInt(encryption, "public_key_ver");
            if (publicKeyVersion < 1) return error();
            projectedEncryption.put("public_key_ver", publicKeyVersion);
            projected.put("service_encrypt_info", projectedEncryption);
            projectedMessages.add(projected);
        }
        JSONObject output = new JSONObject();
        output.put("errcode", 0);
        output.put("errmsg", "ok");
        output.put("has_more", hasMore);
        output.put("next_cursor", nextCursor);
        output.put("msg_list", projectedMessages);
        return output.toJSONString();
    }

    private static JSONObject party(JSONObject source) {
        if (source == null) throw new IllegalArgumentException("party missing");
        JSONObject result = new JSONObject();
        result.put("type", requiredInt(source, "type"));
        result.put("id", requiredBoundedString(source, "id", 128));
        return result;
    }

    private static String optionalBoundedString(JSONObject object, String field, int maximum) {
        if (!object.containsKey(field)) return "";
        Object raw = object.get(field);
        if (!(raw instanceof String value) || value.length() > maximum) {
            throw new IllegalArgumentException("invalid string");
        }
        return value;
    }

    private static String requiredBoundedString(JSONObject object, String field, int maximum) {
        String value = optionalBoundedString(object, field, maximum);
        if (value.isBlank()) throw new IllegalArgumentException("missing string");
        return value;
    }

    private static int requiredInt(JSONObject object, String field) {
        Object value = object.get(field);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("missing integer");
        return number.intValue();
    }

    private static long requiredNonNegativeLong(JSONObject object, String field) {
        Object value = object.get(field);
        if (!(value instanceof Number number) || number.longValue() < 0) {
            throw new IllegalArgumentException("missing long");
        }
        return number.longValue();
    }

    private static String error() {
        JSONObject result = new JSONObject();
        result.put("errcode", INTERNAL_ERROR);
        result.put("errmsg", "request rejected");
        return result.toJSONString();
    }

    public interface SdkInvoker {
        InvocationResult invoke(String request) throws Exception;
    }

    public record InvocationResult(int returnCode, String response, String apiName) {}
}
