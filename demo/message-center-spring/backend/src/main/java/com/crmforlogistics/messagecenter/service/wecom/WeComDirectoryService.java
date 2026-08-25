package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComDirectoryGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** 企业微信通讯录只读能力的输入校验、安装解析和审计 owner。 */
@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComDirectoryService {
    private final AppConfig config;
    private final WeComInstallationService installations;
    private final WeComDirectoryGateway gateway;
    private final WeComApiAuditTrail audit;
    private final WeComPartyProfileService profiles;

    WeComDirectoryService(AppConfig config, WeComInstallationService installations,
                                 WeComDirectoryGateway gateway, WeComApiAuditTrail audit) {
        this(config, installations, gateway, audit, null);
    }

    @Autowired
    public WeComDirectoryService(AppConfig config, WeComInstallationService installations,
                                 WeComDirectoryGateway gateway, WeComApiAuditTrail audit,
                                 WeComPartyProfileService profiles) {
        this.config = config;
        this.installations = installations;
        this.gateway = gateway;
        this.audit = audit;
        this.profiles = profiles;
    }

    public JsonNode getMember(String authCorpId, String userId, WeComApiActor actor) {
        requireActor(actor);
        String corp = required(authCorpId, "authCorpId", 128);
        String user = required(userId, "userId", 128);
        ResolvedInstallation installation = resolve(corp);
        return execute(installation, actor, "wecom.api.directory.member_get",
                "/cgi-bin/user/get", () -> gateway.getMember(installation, user, timeout()));
    }

    public JsonNode listMembers(String authCorpId, long departmentId,
                                boolean fetchChild, WeComApiActor actor) {
        requireActor(actor);
        String corp = required(authCorpId, "authCorpId", 128);
        positive(departmentId, "departmentId");
        ResolvedInstallation installation = resolve(corp);
        return execute(installation, actor, "wecom.api.directory.member_list",
                "/cgi-bin/user/simplelist",
                () -> gateway.listMembers(installation, departmentId, fetchChild, timeout()));
    }

    public JsonNode listDepartments(String authCorpId, Long departmentId,
                                    WeComApiActor actor) {
        requireActor(actor);
        String corp = required(authCorpId, "authCorpId", 128);
        if (departmentId != null) positive(departmentId, "departmentId");
        ResolvedInstallation installation = resolve(corp);
        return execute(installation, actor, "wecom.api.directory.department_list",
                "/cgi-bin/department/list",
                () -> gateway.listDepartments(installation, departmentId, timeout()));
    }

    public JsonNode listTags(String authCorpId, WeComApiActor actor) {
        requireActor(actor);
        String corp = required(authCorpId, "authCorpId", 128);
        ResolvedInstallation installation = resolve(corp);
        return execute(installation, actor, "wecom.api.directory.tag_list",
                "/cgi-bin/tag/list", () -> gateway.listTags(installation, timeout()));
    }

    public JsonNode getTag(String authCorpId, long tagId, WeComApiActor actor) {
        requireActor(actor);
        String corp = required(authCorpId, "authCorpId", 128);
        positive(tagId, "tagId");
        ResolvedInstallation installation = resolve(corp);
        return execute(installation, actor, "wecom.api.directory.tag_get",
                "/cgi-bin/tag/get", () -> gateway.getTag(installation, tagId, timeout()));
    }

    public WeComPartyProfileService.DirectorySyncResult syncProfiles(String authCorpId,
                                                                       WeComApiActor actor) {
        requireActor(actor);
        String corp = required(authCorpId, "authCorpId", 128);
        if (profiles == null) throw new IllegalStateException("directory profile sync unavailable");
        ResolvedInstallation installation = resolve(corp);
        WeComApiAuditTrail.Attempt attempt = audit.begin(actor.userId(), installation,
                "wecom.api.directory.profile_sync", "/cgi-bin/department/list", actor.traceId());
        try {
            WeComPartyProfileService.DirectorySyncResult result = profiles.syncDirectory(
                    installation, timeout());
            audit.success(attempt);
            return result;
        } catch (WeComException exception) {
            audit.failed(attempt, exception);
            throw exception;
        }
    }

    private ResolvedInstallation resolve(String authCorpId) {
        return installations.resolveInstallation(config.wecomSuiteId(), authCorpId);
    }

    private JsonNode execute(ResolvedInstallation installation, WeComApiActor actor,
                             String action, String path, Operation operation) {
        WeComApiAuditTrail.Attempt attempt = audit.begin(actor.userId(), installation,
                action, path, actor.traceId());
        try {
            JsonNode result = operation.run();
            audit.success(attempt);
            return result;
        } catch (WeComException exception) {
            audit.failed(attempt, exception);
            throw exception;
        }
    }

    private Duration timeout() {
        return Duration.ofSeconds(config.wecomApiTimeoutSeconds());
    }

    private static void requireActor(WeComApiActor actor) {
        if (actor == null) throw new IllegalArgumentException("actor is invalid");
    }

    private static void positive(long value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " is invalid");
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }

    @FunctionalInterface
    private interface Operation {
        JsonNode run();
    }
}
