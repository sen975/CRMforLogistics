package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class JsonSupport {
    private JsonSupport() {}

    static String string(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return object.get(key).toString();
        }
    }

    static String textField(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            JsonObject object = JsonParser.parseString(value).getAsJsonObject();
            return string(object, "text");
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}