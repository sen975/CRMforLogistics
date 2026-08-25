package com.crmforlogistics.messagecenter.channel.wecom;

import com.fasterxml.jackson.databind.JsonNode;
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
public class WeComExternalContactGateway {
    private final WeComApiClient client;

    public WeComExternalContactGateway(WeComApiClient client) {
        this.client = client;
    }

    public JsonNode list(ResolvedInstallation installation, String userId, Duration timeout) {
        return client.get(installation, "/cgi-bin/externalcontact/list",
                Map.of("userid", userId), timeout);
    }

    public JsonNode get(ResolvedInstallation installation, String externalUserId,
                        String cursor, Duration timeout) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("external_userid", externalUserId);
        if (cursor != null && !cursor.isBlank()) query.put("cursor", cursor);
        return client.get(installation, "/cgi-bin/externalcontact/get", query, timeout);
    }

    public JsonNode batchGet(ResolvedInstallation installation, List<String> userIds,
                             String cursor, int limit, Duration timeout) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userid_list", userIds);
        if (cursor != null && !cursor.isBlank()) body.put("cursor", cursor);
        body.put("limit", limit);
        return client.post(installation, "/cgi-bin/externalcontact/batch/get_by_user", body, timeout);
    }

    public JsonNode remark(ResolvedInstallation installation, String userId, String externalUserId,
                           String remark, String description, String remarkCompany,
                           List<String> remarkMobiles, Duration timeout) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userid", userId);
        body.put("external_userid", externalUserId);
        if (remark != null && !remark.isBlank()) body.put("remark", remark);
        if (description != null && !description.isBlank()) body.put("description", description);
        if (remarkCompany != null && !remarkCompany.isBlank()) body.put("remark_company", remarkCompany);
        if (remarkMobiles != null && !remarkMobiles.isEmpty()) body.put("remark_mobiles", remarkMobiles);
        return client.post(installation, "/cgi-bin/externalcontact/remark", body, timeout);
    }

    public JsonNode groupList(ResolvedInstallation installation, Integer statusFilter,
                              List<String> ownerFilter, String cursor, int limit, Duration timeout) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (statusFilter != null) body.put("status_filter", statusFilter);
        if (ownerFilter != null && !ownerFilter.isEmpty()) body.put("owner_filter", ownerFilter);
        if (cursor != null && !cursor.isBlank()) body.put("cursor", cursor);
        body.put("limit", limit);
        return client.post(installation, "/cgi-bin/externalcontact/groupchat/list", body, timeout);
    }

    public JsonNode groupGet(ResolvedInstallation installation, String chatId,
                             boolean needName, Duration timeout) {
        return client.post(installation, "/cgi-bin/externalcontact/groupchat/get",
                Map.of("chat_id", chatId, "need_name", needName ? 1 : 0), timeout);
    }
}
