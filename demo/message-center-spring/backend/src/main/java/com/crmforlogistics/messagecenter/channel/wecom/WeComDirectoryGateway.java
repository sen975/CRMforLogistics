package com.crmforlogistics.messagecenter.channel.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 企业微信通讯录只读接口的协议映射层。 */
@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComDirectoryGateway {
    private final WeComApiClient client;

    public WeComDirectoryGateway(WeComApiClient client) {
        this.client = client;
    }

    public JsonNode getMember(ResolvedInstallation installation, String userId,
                              Duration timeout) {
        return client.get(installation, "/cgi-bin/user/get",
                Map.of("userid", userId), timeout);
    }

    public JsonNode listMembers(ResolvedInstallation installation, long departmentId,
                                boolean fetchChild, Duration timeout) {
        return client.get(installation, "/cgi-bin/user/simplelist",
                Map.of("department_id", departmentId, "fetch_child", fetchChild ? 1 : 0), timeout);
    }

    public JsonNode listDepartments(ResolvedInstallation installation, Long departmentId,
                                    Duration timeout) {
        Map<String, Object> query = new LinkedHashMap<>();
        if (departmentId != null) query.put("id", departmentId);
        return client.get(installation, "/cgi-bin/department/list", query, timeout);
    }

    public JsonNode listTags(ResolvedInstallation installation, Duration timeout) {
        return client.get(installation, "/cgi-bin/tag/list", Map.of(), timeout);
    }

    public JsonNode getTag(ResolvedInstallation installation, long tagId, Duration timeout) {
        return client.get(installation, "/cgi-bin/tag/get",
                Map.of("tagid", tagId), timeout);
    }
}
