package com.crmforlogistics.messagecenter.channel.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComAppChatGateway {
    private final WeComApiClient client;
    private final ObjectMapper objectMapper;

    public WeComAppChatGateway(WeComApiClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public JsonNode create(ResolvedInstallation installation, String chatId, String name,
                           String owner, List<String> userList, Duration timeout) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (chatId != null && !chatId.isBlank()) body.put("chatid", chatId);
        body.put("name", name);
        body.put("owner", owner);
        body.put("userlist", userList);
        return client.post(installation, "/cgi-bin/appchat/create", body, timeout);
    }

    public JsonNode get(ResolvedInstallation installation, String chatId, Duration timeout) {
        return client.get(installation, "/cgi-bin/appchat/get", Map.of("chatid", chatId), timeout);
    }

    public JsonNode update(ResolvedInstallation installation, String chatId, String name,
                           String owner, List<String> addUsers, List<String> removeUsers,
                           Duration timeout) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chatid", chatId);
        if (name != null) body.put("name", name);
        if (owner != null) body.put("owner", owner);
        if (addUsers != null && !addUsers.isEmpty()) body.put("add_user_list", addUsers);
        if (removeUsers != null && !removeUsers.isEmpty()) body.put("del_user_list", removeUsers);
        return client.post(installation, "/cgi-bin/appchat/update", body, timeout);
    }

    public JsonNode send(ResolvedInstallation installation, String chatId, String messageType,
                         JsonNode content, boolean safe, Duration timeout) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("chatid", chatId);
        body.put("msgtype", messageType);
        body.set(messageType, content.deepCopy());
        if (safe) body.put("safe", 1);
        return client.post(installation, "/cgi-bin/appchat/send", body, timeout);
    }
}
