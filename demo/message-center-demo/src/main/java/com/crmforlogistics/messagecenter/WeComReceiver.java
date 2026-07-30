package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WeComReceiver {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_LIMIT = 1000;

    private final Config config;
    private String pendingToken;
    private String pendingOpenKfid;
    private long tokenExpiresAt;

    // WeCom access_token cache
    private String accessToken;
    private long accessTokenExpiresAt;

    public WeComReceiver(Config config) {
        this.config = config;
    }

    // ---- callback: POST /webhook/wecom (simulates WeCom XML event push) ----

    public record CallbackResult(String msgType, String event, String token, String openKfid) {}

    public CallbackResult handleCallback(String raw) {
        String msgType = xmlText(raw, "MsgType");
        String event = xmlText(raw, "Event");
        String token = xmlText(raw, "Token");
        String openKfid = xmlText(raw, "OpenKfId");

        if (token == null || token.isBlank()) {
            token = "demo-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        this.pendingToken = token;
        this.pendingOpenKfid = openKfid == null || openKfid.isBlank() ? "kf_demo" : openKfid;
        this.tokenExpiresAt = System.currentTimeMillis() + 600_000; // 10 min

        return new CallbackResult(
                msgType == null || msgType.isBlank() ? "event" : msgType,
                event == null || event.isBlank() ? "kf_msg_or_event" : event,
                this.pendingToken,
                this.pendingOpenKfid);
    }

    // ---- sync_msg: POST /api/wecom/sync_msg (simulates WeCom message pull API) ----

    public record SyncMsgRequest(String cursor, String token, int limit, String openKfid) {}

    public record SyncMsgResponse(int errcode, String errmsg, String nextCursor, int hasMore,
                                   List<Map<String, Object>> msgList) {}

    public SyncMsgResponse syncMessages(String cursor, String token, int limit, String openKfid) throws IOException {
        if (token != null && !token.isBlank() && pendingToken != null && !pendingToken.equals(token)) {
            return new SyncMsgResponse(40001, "invalid token", "", 0, List.of());
        }
        if (token != null && !token.isBlank() && System.currentTimeMillis() > tokenExpiresAt) {
            return new SyncMsgResponse(42001, "token expired", "", 0, List.of());
        }

        int effectiveLimit = limit <= 0 || limit > MAX_LIMIT ? MAX_LIMIT : limit;
        String effectiveOpenKfid = openKfid != null && !openKfid.isBlank() ? openKfid
                : pendingOpenKfid != null ? pendingOpenKfid : "kf_demo";

        List<Map<String, Object>> allMessages = readStoredMessages(effectiveOpenKfid);
        int startIdx = 0;
        if (cursor != null && !cursor.isBlank()) {
            for (int i = 0; i < allMessages.size(); i++) {
                if (cursor.equals(allMessages.get(i).get("msgid"))) {
                    startIdx = i + 1;
                    break;
                }
            }
        }

        int endIdx = Math.min(startIdx + effectiveLimit, allMessages.size());
        List<Map<String, Object>> page = allMessages.subList(startIdx, endIdx);
        String nextCursor = endIdx >= allMessages.size() ? (cursor == null ? "" : cursor)
                : (String) allMessages.get(endIdx - 1).get("msgid");
        int hasMore = endIdx < allMessages.size() ? 1 : 0;

        pendingToken = null;
        return new SyncMsgResponse(0, "ok", nextCursor, hasMore, page);
    }

    // ---- inject: POST /api/wecom/messages (seed test messages) ----

    public Map<String, Object> injectMessage(String raw) throws IOException {
        JsonElement root;
        try {
            root = JsonParser.parseString(raw);
        } catch (RuntimeException ex) {
            return Map.of("errcode", 400, "errmsg", "invalid json: " + ex.getMessage());
        }

        String msgid = firstField(root, "msgid", "MsgId", "id");
        if (msgid.isBlank()) {
            msgid = "wecom-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        String msgtype = firstField(root, "msgtype", "msg_type", "MsgType");
        String externalUserid = firstField(root, "external_userid", "ExternalUserID", "from");
        String openKfid = firstField(root, "open_kfid", "OpenKfId", "to");
        long sendTime = parseSendTime(root);

        if (containsMessage(msgid)) {
            return Map.of("errcode", 0, "errmsg", "ok", "msgid", msgid, "dup", true);
        }

        Path file = config.wecomDataFile();
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("msgid", msgid);
        record.put("msgtype", msgtype);
        record.put("external_userid", externalUserid);
        record.put("open_kfid", openKfid);
        record.put("send_time", sendTime > 0 ? sendTime : Instant.now().getEpochSecond());
        record.put("origin", 3); // 3 = from WeChat customer
        if (root != null && root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            for (String key : List.of("text", "image", "voice", "video", "file", "location", "link",
                    "business_card", "miniprogram", "msgmenu", "merged_msg",
                    "channels_shop_product", "channels_shop_order")) {
                JsonElement val = obj.get(key);
                if (val != null && !val.isJsonNull()) {
                    record.put(key, GSON.fromJson(val.toString(), Object.class));
                }
            }
        }
        record.put("_raw", raw);
        Files.writeString(file, GSON.toJson(record) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        return Map.of("errcode", 0, "errmsg", "ok", "msgid", msgid);
    }

    // ---- gettoken: GET /api/wecom/gettoken ----

    public record TokenResponse(int errcode, String errmsg, int expiresIn) {}

    public TokenResponse getToken() throws IOException, InterruptedException {
        String corpid = config.value("WECOM_CORP_ID", "");
        String secret = config.value("WECOM_SECRET", "");

        if (corpid.isBlank() || secret.isBlank()) {
            return new TokenResponse(40001, "missing corpid or secret", 0);
        }

        // return cached token if still valid (with 5 min buffer)
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpiresAt - 300_000) {
            return new TokenResponse(0, "ok (cached)",
                    (int) ((accessTokenExpiresAt - System.currentTimeMillis()) / 1000));
        }

        return fetchCorpToken(corpid, secret);
    }

    private TokenResponse fetchCorpToken(String corpid, String corpSecret) throws IOException, InterruptedException {
        String url = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=" + corpid + "&corpsecret=" + corpSecret;
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        int errcode = body.has("errcode") ? body.get("errcode").getAsInt() : -1;
        String errmsg = JsonSupport.string(body, "errmsg");
        if (errcode != 0) {
            return new TokenResponse(errcode, errmsg, 0);
        }

        String token = JsonSupport.string(body, "access_token");
        int expiresIn = body.has("expires_in") ? body.get("expires_in").getAsInt() : 7200;
        this.accessToken = token;
        this.accessTokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000L;
        return new TokenResponse(0, "ok", expiresIn);
    }

    // ---- internal ----

    private List<Map<String, Object>> readStoredMessages(String openKfid) throws IOException {
        Path file = config.wecomDataFile();
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.exists(file)) {
            return result;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            try {
                JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                String msgOpenKfid = JsonSupport.string(obj, "open_kfid");
                if (!openKfid.isBlank() && !msgOpenKfid.isBlank() && !openKfid.equals(msgOpenKfid)) {
                    continue;
                }
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("msgid", JsonSupport.string(obj, "msgid"));
                msg.put("msgtype", JsonSupport.string(obj, "msgtype"));
                msg.put("external_userid", JsonSupport.string(obj, "external_userid"));
                msg.put("open_kfid", msgOpenKfid);
                msg.put("send_time", obj.has("send_time") ? obj.get("send_time").getAsLong() : 0);
                msg.put("origin", obj.has("origin") ? obj.get("origin").getAsInt() : 3);
                String type = JsonSupport.string(obj, "msgtype");
                for (String key : List.of("text", "image", "voice", "video", "file", "location", "link",
                        "business_card", "miniprogram", "msgmenu", "merged_msg",
                        "channels_shop_product", "channels_shop_order")) {
                    if (obj.has(key) && !obj.get(key).isJsonNull()) {
                        msg.put(type, GSON.fromJson(obj.get(key).toString(), Object.class));
                        break;
                    }
                }
                result.add(msg);
            } catch (RuntimeException ignored) {
            }
        }
        result.sort(Comparator.comparingLong(m -> ((Number) m.getOrDefault("send_time", 0L)).longValue()));
        return result;
    }

    private boolean containsMessage(String msgid) throws IOException {
        if (msgid == null || msgid.isBlank() || !Files.exists(config.wecomDataFile())) return false;
        for (String line : Files.readAllLines(config.wecomDataFile(), StandardCharsets.UTF_8)) {
            if (line.contains("\"msgid\":\"" + msgid + "\"")) {
                return true;
            }
        }
        return false;
    }

    // ---- XML parsing helpers ----

    private static String xmlText(String xml, String tag) {
        if (xml == null || xml.isBlank()) return "";
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        if (start < 0) {
            // Try CDATA / attribute forms
            String cdataStart = "<" + tag + "><![CDATA[";
            int cs = xml.indexOf(cdataStart);
            if (cs >= 0) {
                int ce = xml.indexOf("]]></" + tag + ">", cs);
                if (ce > cs) return xml.substring(cs + cdataStart.length(), ce);
            }
            return "";
        }
        int end = xml.indexOf(close, start + open.length());
        if (end < 0) return "";
        String content = xml.substring(start + open.length(), end);
        if (content.startsWith("<![CDATA[") && content.endsWith("]]>")) {
            content = content.substring(9, content.length() - 3);
        }
        return content;
    }

    // ---- field extraction ----

    private static String firstField(JsonElement root, String... keys) {
        if (root == null) return "";
        for (String key : keys) {
            String val = field(root, key);
            if (!val.isBlank()) return val;
        }
        return "";
    }

    private static String field(JsonElement element, String key) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) return "";
        JsonElement val = element.getAsJsonObject().get(key);
        if (val == null || val.isJsonNull()) return "";
        return val.isJsonPrimitive() ? val.getAsString() : val.toString();
    }

    private static long parseSendTime(JsonElement root) {
        String s = firstField(root, "send_time", "sendTime", "timestamp");
        if (!s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    // ---- pending state for token flow ----

    public String pendingToken() { return pendingToken; }
    public String pendingOpenKfid() { return pendingOpenKfid; }
}
