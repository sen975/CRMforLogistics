package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.wecom.WeComContactEventEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.dto.response.WeComContactEventResponse;
import com.crmforlogistics.messagecenter.dto.response.WeComExternalContactLinkResponse;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.wecom.WeComApiActor;
import com.crmforlogistics.messagecenter.service.wecom.WeComAppChatService;
import com.crmforlogistics.messagecenter.service.wecom.WeComContactEventService;
import com.crmforlogistics.messagecenter.service.wecom.WeComContactLinkService;
import com.crmforlogistics.messagecenter.service.wecom.WeComDirectoryService;
import com.crmforlogistics.messagecenter.service.wecom.WeComExternalContactService;
import com.crmforlogistics.messagecenter.service.wecom.WeComProfileBackfillService;
import com.crmforlogistics.messagecenter.service.wecom.WeComUserBindingService;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
@RequestMapping("/api/v1/wecom")
public class WeComP0Controller {
    private static final long MAX_REQUEST_BODY_BYTES = 256L * 1024L;
    private static final int MAX_CONTACT_EVENT_LIMIT = 200;
    private final AppConfig config;
    private final WeComInstallationService installations;
    private final WeComAppChatService appChats;
    private final WeComExternalContactService externalContacts;
    private final WeComDirectoryService directory;
    private final WeComProfileBackfillService profileBackfill;
    private final WeComUserBindingService bindings;
    private final WeComContactLinkService contactLinks;
    private final WeComContactEventService contactEvents;

    public WeComP0Controller(AppConfig config, WeComInstallationService installations,
                             WeComAppChatService appChats,
                             WeComExternalContactService externalContacts,
                             WeComDirectoryService directory,
                             WeComProfileBackfillService profileBackfill,
                             WeComUserBindingService bindings,
                             WeComContactLinkService contactLinks,
                             WeComContactEventService contactEvents) {
        this.config = config;
        this.installations = installations;
        this.appChats = appChats;
        this.externalContacts = externalContacts;
        this.directory = directory;
        this.profileBackfill = profileBackfill;
        this.bindings = bindings;
        this.contactLinks = contactLinks;
        this.contactEvents = contactEvents;
    }

    @GetMapping("/installations")
    public List<WeComInstallationService.InstallationSummary> installations() {
        return installations.listInstallationSummaries(config.wecomSuiteId());
    }

    @PostMapping("/installations/{authCorpId}/app-chats")
    public JsonNode createAppChat(@PathVariable String authCorpId,
                                  @RequestBody CreateAppChatRequest request,
                                  HttpServletRequest servletRequest) {
        ensureBodyWithinLimit(servletRequest);
        return appChats.create(new WeComAppChatService.CreateCommand(authCorpId, request.chatId(),
                request.name(), request.owner(), request.userList()), actor(servletRequest));
    }

    @GetMapping("/installations/{authCorpId}/app-chats/{chatId}")
    public JsonNode getAppChat(@PathVariable String authCorpId, @PathVariable String chatId,
                               HttpServletRequest servletRequest) {
        return appChats.get(authCorpId, chatId, actor(servletRequest));
    }

    @PatchMapping("/installations/{authCorpId}/app-chats/{chatId}")
    public JsonNode updateAppChat(@PathVariable String authCorpId, @PathVariable String chatId,
                                  @RequestBody UpdateAppChatRequest request,
                                  HttpServletRequest servletRequest) {
        ensureBodyWithinLimit(servletRequest);
        return appChats.update(new WeComAppChatService.UpdateCommand(authCorpId, chatId,
                request.name(), request.owner(), request.addUsers(), request.removeUsers()),
                actor(servletRequest));
    }

    @PostMapping("/installations/{authCorpId}/app-chats/{chatId}/messages")
    public JsonNode sendAppChatMessage(@PathVariable String authCorpId, @PathVariable String chatId,
                                       @RequestBody SendAppChatMessageRequest request,
                                       HttpServletRequest servletRequest) {
        ensureBodyWithinLimit(servletRequest);
        return appChats.send(new WeComAppChatService.SendCommand(authCorpId, chatId,
                request.messageType(), request.content(), request.safe()), actor(servletRequest));
    }

    @GetMapping("/installations/{authCorpId}/external-contacts")
    public JsonNode listExternalContacts(@PathVariable String authCorpId,
                                         HttpServletRequest servletRequest) {
        WeComUserBindingService.BoundIdentity binding = bindings.requireByUserId(SecurityUtil.currentUserId());
        if (!authCorpId.equals(binding.authCorpId())) {
            throw new WeComException("WECOM_BINDING_CORP_MISMATCH", 403,
                    "当前账号绑定的企业微信企业与请求不一致");
        }
        return externalContacts.list(authCorpId, binding.wecomUserId(), actor(servletRequest));
    }

    /**
     * Reports which CRM contact each 客户联系 row can open for the current account, so the
     * console only offers entries the account may actually read. This reads CRM data only.
     */
    @GetMapping("/installations/{authCorpId}/external-contacts/contact-links")
    public List<WeComExternalContactLinkResponse> externalContactLinks(
            @PathVariable String authCorpId,
            @RequestParam("externalUserIds") List<String> externalUserIds) {
        UUID userId = SecurityUtil.currentUserId();
        WeComUserBindingService.BoundIdentity binding = bindings.requireByUserId(userId);
        if (!authCorpId.equals(binding.authCorpId())) {
            throw new WeComException("WECOM_BINDING_CORP_MISMATCH", 403,
                    "当前账号绑定的企业微信企业与请求不一致");
        }
        return contactLinks.resolve(userId, externalUserIds);
    }

    /**
     * 「客户动态」：客户关系变化的流水（新增 / 编辑 / 免验证添加 / 删除客户 / 删除跟进成员 / 接替失败）。
     *
     * <p>与「客户联系」面板的区别：那个是**当前快照**（每次进页面现拉 {@code externalcontact/list}），
     * 这里是**流水**。快照回答「现在有哪些客户」，只有流水才能回答「这个客户是什么时候、由谁、
     * 经哪个渠道加进来的」以及「什么时候流失的」。
     *
     * <p>约束：
     * <ul>
     *   <li>单次数据库查询完成，**不在列表接口回查企微** —— 一页 200 条会变成 200 个外部请求；</li>
     *   <li>首期不返回昵称。拿不到名称时前端显示「未获取昵称」，不伪造；</li>
     *   <li>数据自接入日起，**不含接入前的历史** —— 企微不提供历史事件查询接口。</li>
     * </ul>
     */
    @GetMapping("/installations/{authCorpId}/contact-events")
    public List<WeComContactEventResponse> contactEvents(
            @PathVariable String authCorpId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(required = false) String changeType,
            @RequestParam(defaultValue = "50") int limit) {
        WeComUserBindingService.BoundIdentity binding =
                bindings.requireByUserId(SecurityUtil.currentUserId());
        if (!authCorpId.equals(binding.authCorpId())) {
            throw new WeComException("WECOM_BINDING_CORP_MISMATCH", 403,
                    "当前账号绑定的企业微信企业与请求不一致");
        }
        if (limit < 1 || limit > MAX_CONTACT_EVENT_LIMIT) {
            throw new WeComException("WECOM_CONTACT_EVENT_LIMIT_INVALID", 400,
                    "limit 必须在 1 到 " + MAX_CONTACT_EVENT_LIMIT + " 之间");
        }
        // 当前配置的 suite_id + 密文 corpid 才能唯一定位安装；只按 auth_corp_id 会在多 suite 下串读。
        var installation = installations.resolveActive(config.wecomSuiteId(), authCorpId);
        return contactEvents.listTimeline(installation.getId(), since, changeType, limit).stream()
                .map(WeComP0Controller::toContactEventResponse)
                .toList();
    }

    @GetMapping("/installations/{authCorpId}/external-contacts/{externalUserId}")
    public JsonNode getExternalContact(@PathVariable String authCorpId,
                                       @PathVariable String externalUserId,
                                       @RequestParam(required = false) String cursor,
                                       HttpServletRequest servletRequest) {
        return externalContacts.get(authCorpId, externalUserId, cursor, actor(servletRequest));
    }
    @PostMapping("/installations/{authCorpId}/external-contacts:batchGet")
    public JsonNode batchGetExternalContacts(@PathVariable String authCorpId,
                                              @RequestBody BatchExternalContactsRequest request,
                                              HttpServletRequest servletRequest) {
        ensureBodyWithinLimit(servletRequest);
        return externalContacts.batchGet(new WeComExternalContactService.BatchGetCommand(
                authCorpId, request.userIds(), request.cursor(), request.limit()), actor(servletRequest));
    }

    @PatchMapping("/installations/{authCorpId}/external-contacts/{externalUserId}/remark")
    public JsonNode updateExternalContactRemark(@PathVariable String authCorpId,
                                                @PathVariable String externalUserId,
                                                @RequestParam String userId,
                                                @RequestBody RemarkRequest request,
                                                HttpServletRequest servletRequest) {
        ensureBodyWithinLimit(servletRequest);
        return externalContacts.remark(new WeComExternalContactService.RemarkCommand(authCorpId, userId,
                externalUserId, request.remark(), request.description(), request.remarkCompany(),
                request.remarkMobiles()), actor(servletRequest));
    }

    @PostMapping("/installations/{authCorpId}/customer-groups:search")
    public JsonNode listCustomerGroups(@PathVariable String authCorpId,
                                       @RequestBody CustomerGroupSearchRequest request,
                                       HttpServletRequest servletRequest) {
        ensureBodyWithinLimit(servletRequest);
        return externalContacts.groupList(new WeComExternalContactService.GroupListCommand(authCorpId,
                request.statusFilter(), request.ownerFilter(), request.cursor(), request.limit()),
                actor(servletRequest));
    }

    @GetMapping("/installations/{authCorpId}/customer-groups/{chatId}")
    public JsonNode getCustomerGroup(@PathVariable String authCorpId, @PathVariable String chatId,
                                     @RequestParam(defaultValue = "false") boolean needName,
                                     HttpServletRequest servletRequest) {
        return externalContacts.groupGet(authCorpId, chatId, needName, actor(servletRequest));
    }

    @GetMapping("/installations/{authCorpId}/directory/members/{userId}")
    public JsonNode getMember(@PathVariable String authCorpId, @PathVariable String userId,
                              HttpServletRequest servletRequest) {
        return directory.getMember(authCorpId, userId, actor(servletRequest));
    }

    @GetMapping("/installations/{authCorpId}/directory/members")
    public JsonNode listMembers(@PathVariable String authCorpId,
                                @RequestParam long departmentId,
                                @RequestParam(defaultValue = "false") boolean fetchChild,
                                HttpServletRequest servletRequest) {
        return directory.listMembers(authCorpId, departmentId, fetchChild, actor(servletRequest));
    }

    @GetMapping("/installations/{authCorpId}/directory/departments")
    public JsonNode listDepartments(@PathVariable String authCorpId,
                                    @RequestParam(required = false) Long departmentId,
                                    HttpServletRequest servletRequest) {
        return directory.listDepartments(authCorpId, departmentId, actor(servletRequest));
    }

    @PostMapping("/installations/{authCorpId}/directory/profile-sync")
    public com.crmforlogistics.messagecenter.service.wecom.WeComPartyProfileService.DirectorySyncResult syncDirectoryProfiles(
            @PathVariable String authCorpId, HttpServletRequest servletRequest) {
        return directory.syncProfiles(authCorpId, actor(servletRequest));
    }

    @PostMapping("/installations/{authCorpId}/profile-backfill")
    public WeComProfileBackfillService.BackfillResult backfillProfiles(
            @PathVariable String authCorpId,
            @RequestParam(required = false) Integer limit) {
        requireAdmin();
        return profileBackfill.backfill(authCorpId, limit);
    }

    @GetMapping("/installations/{authCorpId}/directory/tags")
    public JsonNode listTags(@PathVariable String authCorpId, HttpServletRequest servletRequest) {
        return directory.listTags(authCorpId, actor(servletRequest));
    }

    @GetMapping("/installations/{authCorpId}/directory/tags/{tagId}")
    public JsonNode getTag(@PathVariable String authCorpId, @PathVariable long tagId,
                           HttpServletRequest servletRequest) {
        return directory.getTag(authCorpId, tagId, actor(servletRequest));
    }

    private static WeComContactEventResponse toContactEventResponse(WeComContactEventEntity event) {
        return new WeComContactEventResponse(event.getId(), event.getChangeType(),
                event.getExternalUserId(), event.getWecomUserId(), event.getState(),
                event.getFailReason(), event.getProviderSource(), event.getProviderCreatedAt());
    }

    private static WeComApiActor actor(HttpServletRequest request) {
        return new WeComApiActor(SecurityUtil.currentUserId(), traceId(request));
    }

    private static String traceId(HttpServletRequest request) {
        String supplied = request.getHeader("X-Trace-Id");
        return supplied != null && supplied.matches("[A-Za-z0-9_-]{1,128}")
                ? supplied : java.util.UUID.randomUUID().toString();
    }

    private static void ensureBodyWithinLimit(HttpServletRequest request) {
        long length = request.getContentLengthLong();
        if (length > MAX_REQUEST_BODY_BYTES) {
            throw new WeComException("WECOM_REQUEST_TOO_LARGE", 413,
                    "企业微信请求体超过大小限制");
        }
    }

    private static void requireAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = authentication != null && authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch("ROLE_ADMIN"::equals);
        if (!isAdmin) {
            throw new WeComException("FORBIDDEN", 403, "仅管理员可以回填企业微信资料");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateAppChatRequest(String chatId, String name, String owner,
                                       List<String> userList) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown request field: " + name);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UpdateAppChatRequest(String name, String owner, List<String> addUsers,
                                       List<String> removeUsers) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown request field: " + name);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SendAppChatMessageRequest(String messageType, JsonNode content, Boolean safe) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown request field: " + name);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BatchExternalContactsRequest(List<String> userIds, String cursor, Integer limit) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown request field: " + name);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RemarkRequest(String remark, String description, String remarkCompany,
                                List<String> remarkMobiles) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown request field: " + name);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CustomerGroupSearchRequest(Integer statusFilter, List<String> ownerFilter,
                                             String cursor, Integer limit) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown request field: " + name);
        }
    }
}
