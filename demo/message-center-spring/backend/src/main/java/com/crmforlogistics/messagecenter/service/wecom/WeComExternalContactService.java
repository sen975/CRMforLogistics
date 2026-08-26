package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComExternalContactGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashSet;
import java.util.List;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComExternalContactService {
    private static final Logger log = LoggerFactory.getLogger(WeComExternalContactService.class);
    private final AppConfig config;
    private final WeComInstallationService installations;
    private final WeComExternalContactGateway gateway;
    private final WeComApiAuditTrail audit;
    private final Map<String, CachedDisplayName> displayNameCache = new ConcurrentHashMap<>();

    public WeComExternalContactService(AppConfig config, WeComInstallationService installations,
                                       WeComExternalContactGateway gateway,
                                       WeComApiAuditTrail audit) {
        this.config = config;
        this.installations = installations;
        this.gateway = gateway;
        this.audit = audit;
    }

    public JsonNode list(String authCorpId, String userId, WeComApiActor actor) {
        requireActor(actor);
        String corp = required(authCorpId, "authCorpId", 128);
        String user = required(userId, "userId", 128);
        ResolvedInstallation installation = resolve(corp);
        return execute(installation, actor, "wecom.api.external_contact.list",
                "/cgi-bin/externalcontact/list",
                () -> gateway.list(installation, user, timeout()));
    }

    public JsonNode get(String authCorpId, String externalUserId, String cursor, WeComApiActor actor) {
        requireActor(actor);
        String corp = required(authCorpId, "authCorpId", 128);
        String external = required(externalUserId, "externalUserId", 128);
        String normalizedCursor = cursor == null ? "" : bounded(cursor, "cursor", 1024);
        ResolvedInstallation installation = resolve(corp);
        return execute(installation, actor, "wecom.api.external_contact.get",
                "/cgi-bin/externalcontact/get",
                () -> gateway.get(installation, external, normalizedCursor, timeout()));
    }

    public JsonNode batchGet(BatchGetCommand command, WeComApiActor actor) {
        requireActor(actor);
        String corp = required(command.authCorpId(), "authCorpId", 128);
        List<String> users = ids(command.userIds(), 100, "userId");
        int limit = limit(command.limit(), 1, 100);
        String cursor = command.cursor() == null ? "" : bounded(command.cursor(), "cursor", 1024);
        ResolvedInstallation installation = resolve(corp);
        return execute(installation, actor, "wecom.api.external_contact.batch_get",
                "/cgi-bin/externalcontact/batch/get_by_user",
                () -> gateway.batchGet(installation, users, cursor, limit, timeout()));
    }

    public JsonNode remark(RemarkCommand command, WeComApiActor actor) {
        requireActor(actor);
        String corp = required(command.authCorpId(), "authCorpId", 128);
        String user = required(command.userId(), "userId", 128);
        String external = required(command.externalUserId(), "externalUserId", 128);
        String remark = optional(command.remark(), "remark", 256);
        String description = optional(command.description(), "description", 1024);
        String company = optional(command.remarkCompany(), "remarkCompany", 256);
        List<String> mobiles = command.remarkMobiles() == null ? List.of()
                : ids(command.remarkMobiles(), 10, "remarkMobile");
        ResolvedInstallation installation = resolve(corp);
        return execute(installation, actor, "wecom.api.external_contact.remark",
                "/cgi-bin/externalcontact/remark",
                () -> gateway.remark(installation, user, external, remark, description,
                        company, mobiles, timeout()));
    }

    public JsonNode groupList(GroupListCommand command, WeComApiActor actor) {
        requireActor(actor);
        String corp = required(command.authCorpId(), "authCorpId", 128);
        Integer status = command.statusFilter();
        if (status != null && (status < 0 || status > 2)) {
            throw new IllegalArgumentException("statusFilter is invalid");
        }
        List<String> owners = command.ownerFilter() == null ? List.of()
                : ids(command.ownerFilter(), 100, "owner");
        String cursor = command.cursor() == null ? "" : bounded(command.cursor(), "cursor", 1024);
        int limit = limit(command.limit(), 1, 1000);
        ResolvedInstallation installation = resolve(corp);
        return execute(installation, actor, "wecom.api.customer_group.list",
                "/cgi-bin/externalcontact/groupchat/list",
                () -> gateway.groupList(installation, status, owners, cursor, limit, timeout()));
    }

    public JsonNode groupGet(String authCorpId, String chatId, boolean needName, WeComApiActor actor) {
        requireActor(actor);
        String corp = required(authCorpId, "authCorpId", 128);
        String group = required(chatId, "chatId", 128);
        ResolvedInstallation installation = resolve(corp);
        return execute(installation, actor, "wecom.api.customer_group.get",
                "/cgi-bin/externalcontact/groupchat/get",
                () -> gateway.groupGet(installation, group, needName, timeout()));
    }

    /**
     * Resolves a customer-group member snapshot. This method is intentionally not called from
     * the generic ChatData scheduler: a ChatData chatid does not identify whether the source is a
     * customer group or an internal group. Internal groups must keep their observed roster only.
     */
    public GroupMemberSnapshot groupMembersForSync(ResolvedInstallation installation, String chatId) {
        String group = required(chatId, "chatId", 128);
        if (installation == null || installation.authCorpId() == null || installation.authCorpId().isBlank()) {
            return new GroupMemberSnapshot(false, List.of(), "INSTALLATION_INVALID");
        }
        try {
            JsonNode response = gateway.groupGet(installation, group, true, timeout());
            JsonNode members = response.path("group_chat").path("member_list");
            if (!members.isArray()) {
                return new GroupMemberSnapshot(false, List.of(), "GROUP_MEMBER_LIST_MISSING");
            }
            List<GroupMember> result = new java.util.ArrayList<>();
            int maximum = Math.min(members.size(), 200);
            for (int index = 0; index < maximum; index++) {
                JsonNode member = members.get(index);
                int type = member.path("type").asInt(0);
                String partyType = type == 1 ? "EMPLOYEE" : type == 2 ? "EXTERNAL_CONTACT" : "";
                String providerId = type == 1
                        ? member.path("userid").asText("")
                        : member.path("external_userid").asText("");
                if (partyType.isBlank() || providerId.isBlank() || providerId.length() > 128) continue;
                String displayName = firstText(member, "name", "group_nickname", "nickname");
                String avatarUrl = firstText(member, "avatar");
                result.add(new GroupMember(partyType, providerId, displayName, avatarUrl));
            }
            return new GroupMemberSnapshot(true, List.copyOf(result), "");
        } catch (RuntimeException failure) {
            log.warn("event=wecom.external_contact.group_members_sync_failed code={} chatIdDigest={}",
                    failure instanceof WeComException exception ? exception.code() : "UNEXPECTED",
                    Integer.toHexString(group.hashCode()));
            return new GroupMemberSnapshot(false, List.of(),
                    failure instanceof WeComException exception ? exception.code() : "GROUP_MEMBER_LOOKUP_FAILED");
        }
    }

    /**
     * Sync-only profile lookup. It deliberately does not use the interactive API audit owner,
     * because chat-data projection runs without an authenticated CRM user.
     */
    public String displayNameForSync(String authCorpId, String externalUserId) {
        String corp = required(authCorpId, "authCorpId", 128);
        String external = required(externalUserId, "externalUserId", 128);
        String cacheKey = corp + "\n" + external;
        long now = Instant.now().getEpochSecond();
        CachedDisplayName cached = displayNameCache.get(cacheKey);
        if (cached != null && now < cached.expiresAtEpochSecond()) return cached.value();
        try {
            JsonNode result = gateway.get(resolve(corp), external, "", timeout());
            JsonNode profile = result.path("external_contact");
            String name = firstText(profile, "name", "nickname", "alias", "remark");
            String value = name.equals(external) ? "" : name;
            displayNameCache.put(cacheKey, new CachedDisplayName(value, now + 3600));
            return value;
        } catch (WeComException exception) {
            log.warn("event=wecom.external_contact.display_name_failed code={} upstreamErrcode={} upstreamPath={} authCorpId={} externalUserId={}",
                    exception.code(), exception.upstreamErrcode(), exception.upstreamPath(), corp, external);
            displayNameCache.put(cacheKey, new CachedDisplayName("", now + 60));
            return "";
        } catch (RuntimeException exception) {
            log.warn("event=wecom.external_contact.display_name_failed code=UNEXPECTED authCorpId={} externalUserId={}",
                    corp, external, exception);
            displayNameCache.put(cacheKey, new CachedDisplayName("", now + 60));
            return "";
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

    private static List<String> ids(List<String> values, int max, String field) {
        if (values == null || values.isEmpty() || values.size() > max) {
            throw new IllegalArgumentException(field + " list is invalid");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        values.forEach(value -> unique.add(required(value, field, 128)));
        return List.copyOf(unique);
    }

    private static int limit(Integer value, int min, int max) {
        int normalized = value == null ? min : value;
        if (normalized < min || normalized > max) throw new IllegalArgumentException("limit is invalid");
        return normalized;
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }

    private static String bounded(String value, String field, int max) {
        return required(value, field, max);
    }

    private static String optional(String value, String field, int max) {
        return value == null || value.isBlank() ? null : required(value, field, max);
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank() && value.length() <= 200) return value;
        }
        return "";
    }

    @FunctionalInterface
    private interface Operation { JsonNode run(); }

    private record CachedDisplayName(String value, long expiresAtEpochSecond) {}

    public record BatchGetCommand(String authCorpId, List<String> userIds,
                                  String cursor, Integer limit) {}

    public record RemarkCommand(String authCorpId, String userId, String externalUserId,
                                String remark, String description, String remarkCompany,
                                List<String> remarkMobiles) {}

    public record GroupListCommand(String authCorpId, Integer statusFilter,
                                   List<String> ownerFilter, String cursor, Integer limit) {}

    public record GroupMember(String partyType, String providerPartyId,
                              String displayName, String avatarUrl) {}

    public record GroupMemberSnapshot(boolean available, List<GroupMember> members, String errorCode) {}
}
