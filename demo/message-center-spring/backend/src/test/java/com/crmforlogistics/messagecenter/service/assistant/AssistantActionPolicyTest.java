package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.service.aitopic.AiTopicService;
import com.crmforlogistics.messagecenter.service.assistant.mcp.AiTopicAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ChatAppTemplateAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ContactAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ContactTimelineAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ContactWriteAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ConversationAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.MessageAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.TodoAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolDefinition;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolResult;
import com.crmforlogistics.messagecenter.service.assistant.mcp.WeComAssistantTools;
import com.crmforlogistics.messagecenter.service.callrecord.ContactTimelineService;
import com.crmforlogistics.messagecenter.service.contact.ContactGroupService;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import com.crmforlogistics.messagecenter.service.message.MessageQueryService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppSharedTemplateCatalogService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService;
import com.crmforlogistics.messagecenter.service.wecom.WeComSelfPushService;
import com.crmforlogistics.messagecenter.service.wecom.WeComSummaryReadService;
import io.modelcontextprotocol.spec.McpSchema;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaCatalogService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaIngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 动作策略：白名单持有权威，注解只作声明（设计文档 §9）。
 *
 * <p>两个方向都要验：白名单外的动作**必须**要确认（防止删除被静默执行）；
 * 白名单内但注解说「破坏性」的**也必须**要确认（fail-closed —— 任何情况下都不会因为
 * 服务端注解说「无害」就静默执行）。
 */
class AssistantActionPolicyTest {

    private final AssistantActionPolicy policy = new AssistantActionPolicy();

    private final TodoAssistantTools tools =
            new TodoAssistantTools(new com.crmforlogistics.messagecenter.service.todo.TodoItemService(
                    org.mockito.Mockito.mock(com.crmforlogistics.messagecenter.mapper.TodoItemMapper.class)));

    /**
     * AUTO 档的<b>全部</b>成员。
     *
     * <p>写成逐个列举而不是 {@code contains}：这一档是「不打招呼就执行」，
     * 多一个成员就是多一条无人复核的路径。加工具时这条断言必须一起改 ——
     * 那一步正是让人停下来回答「最坏情况用户会失去什么」的地方。
     *
     * <p>2026-09-28 从一条变两条：{@code wecom.push_self} 能进来靠的是
     * <b>收件人由调用方身份解析、不是参数</b>（模型编不出别人），最坏情况是「自己多收一条消息」。
     * 这与 {@code message.send_email} 必须确认并不矛盾 —— 两者的差别在「能不能发给第三方」。
     */
    @Test
    void onlyTheTwoDeliberateActionsRunWithoutConfirmation() {
        assertThat(AssistantActionPolicy.AUTO_EXECUTE_ALLOWLIST).containsExactlyInAnyOrder(
                TodoAssistantTools.TOOL_CREATE,
                WeComAssistantTools.TOOL_PUSH_SELF);
    }

    @Test
    void createRunsWithoutConfirmation() {
        assertThat(policy.decide(tools.todoCreateTool())).isEqualTo(AssistantActionPolicy.Decision.AUTO);
    }

    @Test
    void completeDeleteAndUpdateAllRequireConfirmation() {
        assertThat(policy.decide(tools.todoCompleteTool())).isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
        assertThat(policy.decide(tools.todoDeleteTool())).isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
        assertThat(policy.decide(tools.todoUpdateTool())).isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
    }

    @Test
    void aDestructiveToolOutsideTheAllowlistNeverRunsAutomaticallyEvenIfDeclaredHarmless() {
        assertThat(policy.decide(tool("todo.archive", false, false)))
                .isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
    }

    /** 配置错误：白名单内却被声明为破坏性 → 按更保守的一边（仍要确认）。 */
    @Test
    void anAllowlistedButDestructiveToolFallsBackToConfirmation() {
        ToolDefinition contradictory = tool(TodoAssistantTools.TOOL_CREATE, true, false);

        assertThat(policy.decide(contradictory)).isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
    }

    /**
     * 注解缺省的保守解读：规范里未声明 {@code destructiveHint} 按 {@code true} 算。
     * 若这里按 Java 的 {@code boolean} 默认值（false）去判，一个忘了写注解的删除动作
     * 就会变成免确认执行 —— 恰好把「未知」报成「安全」。
     */
    @Test
    void missingAnnotationsAreInterpretedAsDestructiveNotAsSafe() {
        ToolDefinition withoutAnnotations = new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TodoAssistantTools.TOOL_CREATE)
                        .title("新建待办")
                        .description("描述")
                        .inputSchema(objectSchema())
                        .build(),
                (UUID userId, Map<String, Object> arguments) -> ToolResult.ok("ok", Map.of()));

        assertThat(AssistantActionPolicy.declaredDestructive(withoutAnnotations)).isTrue();
        assertThat(policy.decide(withoutAnnotations)).isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
    }

    @Test
    void declaredDestructiveReadsTheExplicitHintWhenPresent() {
        assertThat(AssistantActionPolicy.declaredDestructive(tool("todo.create", false, false))).isFalse();
        assertThat(AssistantActionPolicy.declaredDestructive(tool("todo.create", true, false))).isTrue();
    }

    // ---------- 只读档 ----------

    private final ContactAssistantTools contactTools = new ContactAssistantTools(
            org.mockito.Mockito.mock(ContactCandidateProvider.class),
            org.mockito.Mockito.mock(ContactBriefProvider.class));

    /**
     * 后加的两组工具。
     *
     * <p>依赖一律 mock：这个测试要验的是「策略怎么判一个声明」，与工具能不能真跑无关。
     * 用真实 service 会把「策略判对了吗」和「数据库在不在」两件事搅在一起 ——
     * 后者失败了，前者的问题就会被报成一个不相干的错误。
     */
    private final ContactTimelineAssistantTools timelineTools = new ContactTimelineAssistantTools(
            org.mockito.Mockito.mock(ContactTimelineService.class),
            org.mockito.Mockito.mock(ContactService.class));

    private final ContactWriteAssistantTools writeTools = new ContactWriteAssistantTools(
            org.mockito.Mockito.mock(ContactGroupService.class),
            org.mockito.Mockito.mock(ContactService.class));

    private final AiTopicAssistantTools topicTools = new AiTopicAssistantTools(
            org.mockito.Mockito.mock(AiTopicService.class));

    /** 消息域与企微域：各只有一个只读工具，但两者的"只读"含义不同（见各自的类注释）。 */
    private final MessageAssistantTools messageTools = new MessageAssistantTools(
            org.mockito.Mockito.mock(MessageQueryService.class));

    private final WeComAssistantTools wecomTools =
            new WeComAssistantTools(absentSummaryService(), absentPushService());

    /** chatapp 模板域：一个只读（清单）、一个写（申请）。依赖全 mock，理由同上面那两组。 */
    private final ChatAppTemplateAssistantTools templateTools = new ChatAppTemplateAssistantTools(
            org.mockito.Mockito.mock(ChatAppAccountProvider.class),
            org.mockito.Mockito.mock(WhatsAppSharedTemplateCatalogService.class),
            org.mockito.Mockito.mock(WhatsAppTemplateApplicationService.class),
            org.mockito.Mockito.mock(TemplateMediaProvider.class),
            org.mockito.Mockito.mock(WhatsAppTemplateMediaCatalogService.class),
            org.mockito.Mockito.mock(WhatsAppTemplateMediaIngestService.class));

    /**
     * 「企微没启用」时那个 provider：{@code getIfAvailable()} 恒返回 {@code null}。
     *
     * <p>刻意不用 mock 一个「有值」的摘要服务：本类只枚举「声明了什么」，而服务缺席
     * 正是关掉企微时的真实形态 —— 用它当桩顺带覆盖了「工具仍在、服务取不到」这条路径。
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<WeComSummaryReadService> absentSummaryService() {
        return org.mockito.Mockito.mock(ObjectProvider.class);
    }

    /** 同上，给推送门面。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<WeComSelfPushService> absentPushService() {
        return org.mockito.Mockito.mock(ObjectProvider.class);
    }

    /**
     * 只读清单的<b>全部</b>成员。
     *
     * <p>写成逐个列举而不是 {@code contains}：这个集合决定的是「免确认且可以循环」，
     * 多一个成员就是多一条无人复核的执行路径。加工具时这条断言必须一起改 ——
     * 那一步正是让人停下来问「它真的只读吗」的地方。
     *
     * <p>其中 {@code message.read} 是<b>第一个故意回原文</b>的成员：2026-09-23 用户拍板
     * 「允许原文进上下文」，于是只读档的第 3 条判据从「有没有原文」变成
     * 「这份原文是不是用户点名要的那一份」。它进得来正是靠这次口径变更，
     * 而不是靠有人忘了检查 —— 所以这条断言里它必须出现，不能让后来的人以为漏了。
     */
    @Test
    void theReadOnlyAllowlistHoldsExactlyTheDeclaredReadTools() {
        assertThat(AssistantActionPolicy.READ_ONLY_ALLOWLIST).containsExactlyInAnyOrder(
                ConversationAssistantTools.TOOL_SEARCH,
                ContactAssistantTools.TOOL_SEARCH,
                ContactAssistantTools.TOOL_BRIEF,
                ContactTimelineAssistantTools.TOOL_TIMELINE,
                AiTopicAssistantTools.TOOL_TOPICS_READ,
                MessageAssistantTools.TOOL_READ,
                WeComAssistantTools.TOOL_SUMMARY_READ,
                ChatAppTemplateAssistantTools.TOOL_TEMPLATE_LIST,
                ChatAppTemplateAssistantTools.TOOL_TEMPLATE_MEDIA_LIST);
    }

    /**
     * 素材这一对：清单免确认、上传要确认。
     *
     * <p>上传<b>刻意不进</b> {@code AUTO_EXECUTE_ALLOWLIST}，理由与它为什么不进只读清单一样：
     * 它会让服务端去访问一个外部地址、再把内容写进服务商的存储。最坏结果虽然是
     * 「多一条能删掉的记录」，但那条记录躺在别人家的存储里、地址还是公网可达的 ——
     * 这一步值得用户看一眼再点。
     */
    @Test
    void theMediaListRunsWithoutConfirmationButTheUploadWaits() {
        assertThat(policy.decide(templateTools.chatAppTemplateMediaListTool()))
                .isEqualTo(AssistantActionPolicy.Decision.READ);
        assertThat(policy.decide(templateTools.chatAppTemplateMediaUploadTool()))
                .isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
    }

    @Test
    void readOnlyToolsRunWithoutConfirmation() {
        assertThat(policy.decide(contactTools.contactSearchTool()))
                .isEqualTo(AssistantActionPolicy.Decision.READ);
        assertThat(policy.decide(contactTools.contactBriefTool()))
                .isEqualTo(AssistantActionPolicy.Decision.READ);
        assertThat(policy.decide(timelineTools.contactTimelineTool()))
                .isEqualTo(AssistantActionPolicy.Decision.READ);
        assertThat(policy.decide(topicTools.contactTopicsReadTool()))
                .isEqualTo(AssistantActionPolicy.Decision.READ);
        assertThat(policy.decide(messageTools.messageReadTool()))
                .isEqualTo(AssistantActionPolicy.Decision.READ);
        assertThat(policy.decide(wecomTools.wecomSummaryReadTool()))
                .isEqualTo(AssistantActionPolicy.Decision.READ);
        assertThat(policy.decide(templateTools.chatAppTemplateListTool()))
                .isEqualTo(AssistantActionPolicy.Decision.READ);
    }

    /**
     * 联系人域与话题域的写工具<b>全部</b>要确认。
     *
     * <p>逐个列举而不是 {@code allMatch(...)}：这条断言的价值在于「加一个写工具时有人被迫停下来问一句
     * 它能不能免确认」。写成集合断言的话，新增的工具不会被检查到 —— 而那正是需要被检查的那一刻。
     *
     * <p>其中 {@code contact.mark_read} 值得单独记一笔：它看起来像"只是清个红点"，
     * 但作用范围是这个人名下<b>全部会话</b>，且读过之后没有"标回未读"，
     * 所以它答不出「最坏情况只是多出一条用户能删掉的记录」这句 AUTO 档的前提。
     */
    @Test
    void everyContactAndTopicWriteToolRequiresConfirmation() {
        assertThat(policy.decide(writeTools.contactUpdateRemarkTool()))
                .isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
        assertThat(policy.decide(writeTools.contactUpdateProfileTool()))
                .isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
        assertThat(policy.decide(writeTools.contactSetTagsTool()))
                .isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
        assertThat(policy.decide(writeTools.contactMarkReadTool()))
                .isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
        assertThat(policy.decide(topicTools.contactTopicsRetryTool()))
                .isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
        assertThat(policy.decide(topicTools.contactTopicUpdateTool()))
                .isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
        assertThat(policy.decide(topicTools.contactTopicsMergeTool()))
                .isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
    }

    /**
     * READ 档的不一致<b>不允许</b>退化成 CONFIRM（与 AUTO 档刻意不同）。
     *
     * <p>只读清单意味着「免确认且可循环」，所以「名字在清单里、注解却说它可能写」这个方向
     * 一旦按保守处理，就会留下一条<b>写动作被免确认连续执行</b>的路径。退保守在这里兜不住，
     * 只能抛 —— 而且要在启动自检时抛（{@code AssistantPolicySelfCheck}），不是等用户发消息时才 500。
     */
    @Test
    void aToolInTheReadOnlyListThatIsNotDeclaredReadOnlyIsAConfigurationError() {
        ToolDefinition contradictory = new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(ContactAssistantTools.TOOL_BRIEF)
                        .title("查询联系人情况")
                        .description("描述")
                        .inputSchema(objectSchema())
                        .annotations(McpSchema.ToolAnnotations.builder()
                                .readOnlyHint(false)
                                .destructiveHint(true)
                                .build())
                        .build(),
                (UUID userId, Map<String, Object> arguments) -> ToolResult.ok("ok", Map.of()));

        assertThatThrownBy(() -> policy.decide(contradictory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ContactAssistantTools.TOOL_BRIEF);
    }

    // ---------- 2026-09-28 新增的两条 ----------

    /**
     * 推送给自己：免确认。
     *
     * <p>与 {@code createRunsWithoutConfirmation} 是同一种断言，但更值得钉住 ——
     * 它破的是「一切对外动作必过确认」那条惯例。退回确认档不会报错，
     * 只会让用户每次说「推给我」之后还要再点一下，而那种退化不会有人注意到。
     */
    @Test
    void pushingToMyOwnWeComRunsWithoutConfirmation() {
        assertThat(policy.decide(wecomTools.wecomPushSelfTool()))
                .isEqualTo(AssistantActionPolicy.Decision.AUTO);
    }

    /**
     * 申请模板：必须确认。
     *
     * <p>「不进任何白名单」就是全部理由：模板一旦提交就进 Meta 的审核队列，
     * 这边改不掉也撤不回，被拒还会在这个账号上留下记录。
     * 这条断言同时钉住「有人图方便把它加进 AUTO」这个改动。
     */
    @Test
    void applyingATemplateAlwaysRequiresConfirmation() {
        assertThat(policy.decide(templateTools.chatAppTemplateApplyTool()))
                .isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
    }

    // ---------- 夹具 ----------

    private static ToolDefinition tool(String name, boolean destructive, boolean readOnly) {
        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(name)
                        .title(name)
                        .description(name + " 的描述")
                        .inputSchema(objectSchema())
                        .annotations(McpSchema.ToolAnnotations.builder()
                                .readOnlyHint(readOnly)
                                .destructiveHint(destructive)
                                .idempotentHint(true)
                                .openWorldHint(false)
                                .build())
                        .build(),
                (UUID userId, Map<String, Object> arguments) -> ToolResult.ok("ok", Map.of()));
    }

    private static Map<String, Object> objectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("todoId", Map.of("type", "string")));
        schema.put("required", List.of());
        schema.put("additionalProperties", false);
        return schema;
    }
}
