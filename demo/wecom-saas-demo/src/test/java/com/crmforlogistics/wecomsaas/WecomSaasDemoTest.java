package com.crmforlogistics.wecomsaas;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class WecomSaasDemoTest {
    public static void main(String[] args) throws Exception {
        loadsConfigFromEnvFileAndKeepsSafeDefaults();
        writesAndReadsJsonlRecords();
        rendersStructuredApiError();
        preservesNullableApiErrorDetails();
    }

    private static void loadsConfigFromEnvFileAndKeepsSafeDefaults() throws Exception {
        Path dir = Files.createTempDirectory("wecom-saas-config-test");
        Path env = dir.resolve(".env");
        Files.writeString(env, ""
                + "WEB_PORT=8123\n"
                + "DATA_DIR=" + dir.resolve("data") + "\n"
                + "MAX_UPLOAD_BYTES=12345\n"
                + "WECOM_ARCHIVE_JSONL=" + dir.resolve("archive.jsonl") + "\n", StandardCharsets.UTF_8);

        Config config = Config.load(env);

        assertEquals("8123", Integer.toString(config.webPort()));
        assertEquals(dir.resolve("data").toString(), config.dataDir().toString());
        assertEquals(dir.resolve("data").resolve("uploads").toString(), config.uploadDir().toString());
        assertEquals("12345", Long.toString(config.maxUploadBytes()));
        assertEquals(dir.resolve("archive.jsonl").toString(), config.wecomArchiveJsonl().toString());
    }

    private static void writesAndReadsJsonlRecords() throws Exception {
        Path dir = Files.createTempDirectory("wecom-saas-json-test");
        Path file = dir.resolve("records.jsonl");

        JsonSupport.appendJsonl(file, JsonSupport.map("id", "one", "name", "客户A"));
        JsonSupport.appendJsonl(file, JsonSupport.map("id", "two", "name", "客户B"));

        List<java.util.Map> rows = JsonSupport.readJsonl(file, java.util.Map.class);
        assertEquals("2", Integer.toString(rows.size()));
        assertEquals("客户A", String.valueOf(rows.get(0).get("name")));
    }

    private static void rendersStructuredApiError() {
        ApiError error = new ApiError(422, "AttachmentTooLarge", "附件超过本地 demo 限制",
                JsonSupport.map("maxBytes", 10));

        java.util.Map<String, Object> body = error.body();

        assertEquals("AttachmentTooLarge", String.valueOf(body.get("error")));
        assertEquals("附件超过本地 demo 限制", String.valueOf(body.get("message")));
        assertEquals("422", Integer.toString(error.status()));
        assertContains(JsonSupport.GSON.toJson(body), "maxBytes");
    }

    private static void preservesNullableApiErrorDetails() {
        ApiError error = new ApiError(422, "ValidationError", "请求参数无效",
                JsonSupport.map("optional", null));

        java.util.Map<String, Object> body = error.body();

        if (!body.containsKey("optional") || body.get("optional") != null) {
            throw new AssertionError("Expected optional detail to be present with a null value");
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "] but got [" + actual + "]");
        }
    }

    private static void assertContains(String value, String expected) {
        if (value == null || !value.contains(expected)) {
            throw new AssertionError("Expected [" + value + "] to contain [" + expected + "]");
        }
    }
}
