package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.dto.request.ContactTagsRequest;
import com.crmforlogistics.messagecenter.dto.response.ContactResponse;
import com.crmforlogistics.messagecenter.service.assistant.ContactCandidates;
import com.crmforlogistics.messagecenter.service.contact.ContactGroupService;
import com.crmforlogistics.messagecenter.service.contact.ContactAccessException;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 联系人域的四个<b>写</b>能力：改备注、改资料、替换标签、标记已读。
 *
 * <h2>为什么这批工具值得单独一个类</h2>
 * {@link ContactAssistantTools} 里的两个是只读的（免确认、可循环），这里的四个全部要落确认卡片。
 * 分成两个类不是为了好看：只读清单与确认路径是两条<b>权限语义不同</b>的链，
 * 混在一个类里，下一个加工具的人很容易照着隔壁的方法签名写一个"免确认的写工具"出来。
 *
 * <h2>四个都不进任何白名单</h2>
 * 判据是「最坏情况用户会失去什么」：
 *
 * <ul>
 *   <li>{@code update_remark} —— 覆盖旧备注，而旧备注里可能有用户自己写的备忘；</li>
 *   <li>{@code update_profile} —— 覆盖显示名与职务，且职务<b>取不到改前值</b>（候选里没有这一列）；</li>
 *   <li>{@code set_tags} —— <b>整体替换</b>，没列出来的标签会被删掉，这是四个里最容易造成静默损失的；</li>
 *   <li>{@code mark_read} —— 不可逆：读过之后再没有"标回未读"，且它的作用范围是这个人名下<b>全部会话</b>的消息。</li>
 * </ul>
 *
 * <p>四个都答不出「最坏情况它只多出一条用户能删掉的记录」（那是 {@code todo.create} 进 AUTO 档的理由），
 * 所以一律 CONFIRM。权威在 {@code AssistantActionPolicy}，不在下面的 {@code annotations}。
 *
 * <h2>动手之前先读一次，是为了回话里的名字</h2>
 * 四个动作都会在确认后回一句「已把<b>「老王」</b>的备注改为…」。工具执行时手上没有候选集
 * （那是编排层的上下文），所以这个名字只能自己取。取法是 {@code ContactService.getById}
 * ——它是联系人详情页自己的查询，归属与授权都在里面，不另写一份。
 *
 * <p>这次预读<b>不是</b>授权判定：真正的越权防线仍在
 * {@code ContactGroupService.requireContact}（{@code where created_by}）与
 * {@code ContactService.listAccessibleForContact}。预读只是为了说人话 ——
 * 而"回话里不吐内部标识"是本项目的一条约定，所以名字取不到时退回用户问的那个 ref，绝不编一个。
 *
 * <h2>全部经由 service，不碰 Mapper</h2>
 * 越权防线与参数校验都在 {@link ContactGroupService} 与 {@link ContactService} 里。
 * 在工具里重写一遍授权，等于让同一件事有两份实现，而它们会在注释里先分叉、再在行为上分叉。
 *
 * <h2>标签为什么是数组，且描述里必须说"替换"</h2>
 * {@code ContactGroupService.updateTags} 的语义是<b>先删后插</b>：调用它就等于声明
 * 「这个人的标签就是这些」。所以工具描述里写死了「未列出的现有标签会被移除，调用前先 contact.brief」——
 * 不写的话，模型会把它当成"加一个标签"，而用户会在确认卡片上看到一份<b>变短了的</b>标签清单，
 * 却不知道少了什么。
 */
@Configuration(proxyBeanMethods = false)
public class ContactWriteAssistantTools {

    public static final String TOOL_UPDATE_REMARK = "contact.update_remark";
    public static final String TOOL_UPDATE_PROFILE = "contact.update_profile";
    public static final String TOOL_SET_TAGS = "contact.set_tags";
    public static final String TOOL_MARK_READ = "contact.mark_read";

    /** 备注长度上限。列本身允许更长，这里收紧是给「进提示词／进确认卡片」留余量。 */
    static final int REMARK_MAX_CHARS = 300;

    /** 标签个数与单个标签长度的上限，与 {@code ContactGroupService.requireLength(name, 100)} 对齐。 */
    static final int TAG_MAX_COUNT = 30;
    static final int TAG_NAME_MAX_CHARS = 100;

    private final ContactGroupService contactGroupService;
    private final ContactService contactService;

    public ContactWriteAssistantTools(ContactGroupService contactGroupService,
                                      ContactService contactService) {
        this.contactGroupService = contactGroupService;
        this.contactService = contactService;
    }

    // ---------- 声明 ----------

    @Bean
    public ToolDefinition contactUpdateRemarkTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("contactRef", referenceTo(ContactCandidates.NAME,
                "联系人标识，只能取自候选联系人清单里的 contactRef（形如 CONTACT:<uuid>）"));
        properties.put("remark", stringSpec(
                "新的备注内容。传空字符串表示清除备注。这是覆盖而不是追加",
                "maxLength", REMARK_MAX_CHARS));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_UPDATE_REMARK)
                        .title("修改联系人备注")
                        .description("把一个联系人的备注改成给定内容（覆盖原备注；传空字符串表示清除）。"
                                + "这只改备注，不会改动他的显示名；想让联系人「显示成某个名字」，改的通常就是备注。"
                                + "contactRef 只能来自候选联系人清单，清单里找不到对应联系人时不要调用，"
                                + "改为向用户说明没找到。这是会改动已有数据的写操作，执行前需要用户确认。")
                        .inputSchema(objectSchema(properties, List.of("contactRef", "remark")))
                        .annotations(write())
                        .build(),
                this::updateRemark);
    }

    @Bean
    public ToolDefinition contactUpdateProfileTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("contactRef", referenceTo(ContactCandidates.NAME,
                "联系人标识，只能取自候选联系人清单里的 contactRef（形如 CONTACT:<uuid>）"));
        properties.put("displayName", stringSpec("新的显示名", "maxLength", 100));
        properties.put("roleTitle", stringSpec(
                "新的职务/头衔。传空字符串表示清除职务", "maxLength", 100));

        // 只给 contactRef 不构成一次修改 —— 与 todo.update 同一条理由：
        // 不把这条约束写进 schema，模型就看不到它，于是「改一下他的资料」会被它理解成一次合法调用，
        // 用户点确认才拿到失败。
        Map<String, Object> schema = objectSchema(properties, List.of("contactRef"));
        schema.put(ToolInputValidator.REQUIRES_AT_LEAST_ONE_OF, List.of("displayName", "roleTitle"));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_UPDATE_PROFILE)
                        .title("修改联系人资料")
                        .description("修改一个联系人的显示名或职务。显示名通常承载着从微信/邮件同步来的昵称，"
                                + "覆盖它会替换掉用户对这位联系人的原有称呼；用户说「改备注」时应当用 contact.update_remark，"
                                + "不要用本工具。只传需要改的字段，至少要传一个；要清除职务就传空字符串。"
                                + "contactRef 只能来自候选联系人清单。这是会改动已有数据的写操作，执行前需要用户确认。")
                        .inputSchema(schema)
                        .annotations(write())
                        .build(),
                this::updateProfile);
    }

    @Bean
    public ToolDefinition contactSetTagsTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("contactRef", referenceTo(ContactCandidates.NAME,
                "联系人标识，只能取自候选联系人清单里的 contactRef（形如 CONTACT:<uuid>）"));
        properties.put("tags", arraySpec(
                "这位联系人改动之后应有的完整标签清单。这是整体替换：这里没列出的现有标签都会被移除",
                TAG_MAX_COUNT, TAG_NAME_MAX_CHARS));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_SET_TAGS)
                        .title("替换联系人标签")
                        .description("整体替换一个联系人的标签集合 —— 注意是替换而不是追加："
                                + "没有列进 tags 的现有标签会被移除。因此调用前应当先用 contact.brief 读出他当前的标签，"
                                + "把要保留的一并列进 tags。传空数组表示清空全部标签。"
                                + "contactRef 只能来自候选联系人清单。这是会改动已有数据的写操作，执行前需要用户确认。")
                        .inputSchema(objectSchema(properties, List.of("contactRef", "tags")))
                        .annotations(write())
                        .build(),
                this::setTags);
    }

    @Bean
    public ToolDefinition contactMarkReadTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("contactRef", referenceTo(ContactCandidates.NAME,
                "联系人标识，只能取自候选联系人清单里的 contactRef（形如 CONTACT:<uuid>）"));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_MARK_READ)
                        .title("标记联系人的消息已读")
                        .description("把一个联系人名下全部会话的消息标记为已读。范围比「某个会话」大，"
                                + "且不可逆（系统里没有「标回未读」这个操作）。contactRef 只能来自候选联系人清单。"
                                + "执行前需要用户确认。")
                        .inputSchema(objectSchema(properties, List.of("contactRef")))
                        .annotations(write())
                        .build(),
                this::markRead);
    }

    // ---------- 执行 ----------

    private ToolResult updateRemark(UUID userId, Map<String, Object> arguments) {
        Target target = target(userId, arguments);
        // remark 允许为空串（= 清除）。不用 requiredText：它把空串当"没给"。
        Object raw = arguments.get("remark");
        String remark = raw instanceof String text ? text.strip() : "";
        return guarded(() -> {
            contactGroupService.updateRemark(target.id(), remark.isEmpty() ? null : remark, userId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("contactRef", target.ref());
            data.put("remark", remark.isEmpty() ? null : remark);
            return ToolResult.ok(
                    remark.isEmpty()
                            ? "已清除「" + target.name() + "」的备注"
                            : "已把「" + target.name() + "」的备注改为：" + remark,
                    data);
        });
    }

    private ToolResult updateProfile(UUID userId, Map<String, Object> arguments) {
        Target target = target(userId, arguments);
        String displayName = optionalNonBlank(arguments, "displayName");
        // roleTitle 走另一条规则：空串是"清除职务"的有效取值，不能与"没给"合并。
        Object rawRole = arguments.get("roleTitle");
        String roleTitle = rawRole instanceof String text ? text.strip() : null;
        return guarded(() -> {
            contactGroupService.updateProfile(target.id(), displayName, roleTitle, userId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("contactRef", target.ref());
            if (displayName != null) {
                data.put("displayName", displayName);
            }
            if (roleTitle != null) {
                data.put("roleTitle", roleTitle.isEmpty() ? null : roleTitle);
            }
            return ToolResult.ok("已更新「" + target.name() + "」的资料", data);
        });
    }

    private ToolResult setTags(UUID userId, Map<String, Object> arguments) {
        Target target = target(userId, arguments);
        List<ContactTagsRequest.ContactTagInput> inputs = tagInputs(arguments);
        return guarded(() -> {
            contactGroupService.updateTags(target.id(), inputs, userId);
            List<String> names = inputs.stream().map(ContactTagsRequest.ContactTagInput::name).toList();
            return ToolResult.ok(
                    names.isEmpty()
                            ? "已清空「" + target.name() + "」的全部标签"
                            : "已把「" + target.name() + "」的标签替换为：" + String.join("、", names),
                    Map.of("contactRef", target.ref(), "tags", names));
        });
    }

    private ToolResult markRead(UUID userId, Map<String, Object> arguments) {
        Target target = target(userId, arguments);
        return guarded(() -> {
            contactService.markAsRead(userId, target.id());
            return ToolResult.ok(
                    "已把与「" + target.name() + "」的全部消息标记为已读",
                    Map.of("contactRef", target.ref()));
        });
    }

    // ---------- 参数读取 ----------

    /**
     * 解析 {@code contactRef} 并预读联系人。
     *
     * <p>先解析形状再读库：形状不对是<b>模型的参数错</b>，与「这个人不在你的范围内」
     * 对用户的指引完全不同（一个该改参数、一个该重新挑人），所以两者分开报。
     */
    private Target target(UUID userId, Map<String, Object> arguments) {
        String reference = requiredText(arguments, "contactRef");
        UUID contactId = ContactCandidates.targetOf(reference);
        if (contactId == null) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "联系人标识格式不正确，只能取自候选联系人清单里的 contactRef");
        }
        ContactResponse contact;
        try {
            contact = contactService.getById(userId, contactId);
        } catch (IllegalArgumentException e) {
            throw new ToolExecutionException(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND,
                    ToolExecutionException.ACCESS_DENIED_MESSAGE, e);
        }
        return new Target(reference, contactId, displayNameOf(contact, reference));
    }

    /**
     * 回话里用的名字：显示名 → 备注 → 退回 ref。
     *
     * <p>退回而不是编一个 —— 与 {@code ContactCandidateProvider.toItem} 同一条口径：
     * 难看是可接受的，编出来的名字会被用户当成事实去核对。
     *
     * <p>刻意<b>不</b>把 {@link ContactResponse} 整个放进返回值：它带 {@code lastText}
     * （最后一条消息正文），一进 {@code data} 就等于把原文送进了模型上下文，
     * 撞 2026-09-22 定下的合规口径。
     */
    private static String displayNameOf(ContactResponse contact, String fallback) {
        for (String candidate : new String[]{contact.displayName(), contact.remark()}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.strip();
            }
        }
        return fallback;
    }

    /** 一次调用的目标：原始 ref（回话与 data 用）、解析后的 id（服务调用用）、权威名字。 */
    private record Target(String ref, UUID id, String name) {
    }

    private static List<ContactTagsRequest.ContactTagInput> tagInputs(Map<String, Object> arguments) {
        Object value = arguments.get("tags");
        if (!(value instanceof List<?> raw)) {
            throw new ToolExecutionException(ToolExecutionException.MISSING_ARGUMENT, "缺少必填参数：tags");
        }
        List<ContactTagsRequest.ContactTagInput> inputs = new ArrayList<>();
        for (Object element : raw) {
            if (element instanceof String name && !name.isBlank()) {
                // 颜色留空：助手没有可靠的配色来源，而编一个颜色只会让标签看起来像用户自己设的。
                inputs.add(new ContactTagsRequest.ContactTagInput(name.strip(), null));
            }
        }
        return inputs;
    }

    private static String requiredText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.strip();
        }
        throw new ToolExecutionException(ToolExecutionException.MISSING_ARGUMENT, "缺少必填参数：" + key);
    }

    /** 取一个可空字段；空白一律当作"没给"（模型很爱传空串表示"不改"）。 */
    private static String optionalNonBlank(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    // ---------- 结果与异常 ----------

    /**
     * 把 service 层异常收敛成带错误码的 {@link ToolExecutionException}。
     *
     * <p>措辞刻意不区分「不存在」与「不属于你」（同 {@code ConversationAssistantTools}）：
     * 对用户来说指引一样（重新挑一个人），而区分开就等于告诉另一个账号「这条 id 是存在的」。
     */
    private static ToolResult guarded(Supplier<ToolResult> action) {
        try {
            return action.get();
        } catch (ContactAccessException e) {
            throw new ToolExecutionException(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND,
                    ToolExecutionException.ACCESS_DENIED_MESSAGE, e);
        } catch (IllegalArgumentException e) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "参数不合法，请检查输入后重试", e);
        } catch (IllegalStateException e) {
            // 如 updateTags 在标签支持缺席时抛的那条：那是装配问题，不是用户输入问题。
            throw new ToolExecutionException(ToolExecutionException.INTERNAL, "执行失败，请稍后再试", e);
        }
    }

    // ---------- 声明构造 ----------

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        // 安全配置，不是风格选项：身份防线就靠这一条（见 ToolRegistry 的启动自检）。
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> stringSpec(String description, Object... extra) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "string");
        field.put("description", description);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            field.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return field;
    }

    /**
     * 字符串数组字段。{@code items.type=string} 是必须的：校验器只支持字符串元素，
     * 而"忘了写 items"会让声明在启动时被拒（见 {@code ToolInputValidator.checkArray}）。
     */
    private static Map<String, Object> arraySpec(String description, int maxItems, int itemMaxLength) {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        items.put("maxLength", itemMaxLength);

        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "array");
        field.put("description", description);
        field.put("maxItems", maxItems);
        field.put("items", items);
        return field;
    }

    private static Map<String, Object> referenceTo(String candidateSet, String description) {
        Map<String, Object> field = stringSpec(description);
        field.put(ToolInputValidator.CANDIDATE_SET, candidateSet);
        return field;
    }

    /**
     * 写动作注解。
     *
     * <p>{@code destructiveHint} 一律 {@code true}：这四个都会覆盖或清除已有数据
     * （备注被覆盖、标签被替换、未读状态被清除、职务被覆盖）。规范里未声明按 {@code true} 算，
     * 这里显式写出来是为了让「它是有损的」这件事在声明里可读 ——
     * 而"能不能免确认"由 {@code AssistantActionPolicy} 决定，不看这里。
     */
    private static McpSchema.ToolAnnotations write() {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(false)
                .destructiveHint(true)
                .idempotentHint(false)
                .openWorldHint(false)
                .build();
    }
}
