package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.dto.response.ContactResponse;
import com.crmforlogistics.messagecenter.service.assistant.ContactCandidates;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import com.crmforlogistics.messagecenter.service.channel.OutboundException;
import com.crmforlogistics.messagecenter.service.channel.OutboundMessageService;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消息域的<b>对外发送</b>能力：{@code message.send_email} 与 {@code message.send_chatapp}。
 *
 * <h2>这是助手第一次能造成不可撤回的后果</h2>
 * 此前 19 个工具的副作用都落在本系统内：改待办、改备注、置顶会话 —— 全都可逆，最坏也能手工改回来。
 * 发出去的邮件与 WhatsApp 消息<b>收不回来</b>。所以这批工具的规格不是「多两个工具」，
 * 而是给「不可撤回的外向副作用」定一条规格：
 *
 * <ol>
 *   <li><b>必须确认</b>：两个工具都不进任何白名单，{@code AssistantActionPolicy} 一律判 CONFIRM。
 *       权威在策略类，不在下面的 {@code annotations}（注解只是给模型看的提示）。</li>
 *   <li><b>确认卡片必须显示完整收件人与完整正文</b>。这是「用户到底同意了什么的」唯一载体 ——
 *       只显示「确认要发邮件给老王吗？」的卡片等于没确认。见
 *       {@code AssistantPendingActionService.card} 里对应的两个分支。</li>
 *   <li><b>收件地址不是参数</b>（见下）。</li>
 *   <li><b>发出去了要能查</b>：两个渠道都落库（邮件落 {@code messages} + {@code email_submissions}，
 *       chatapp 落 {@code messages} + 出站任务），所以审计之外还有一条可核对的痕迹。</li>
 * </ol>
 *
 * <h2>为什么没有 {@code to} 参数 —— 这是本类最重要的一个设计</h2>
 * 「防模型编造收件人」有强弱两种做法：
 *
 * <ul>
 *   <li><b>弱</b>：让模型给地址，再校验它像不像地址、是否命中候选。<b>但这仍然允许它给出一个
 *       格式完全合法、却属于别人的地址</b> —— 而发出去就收不回来了。</li>
 *   <li><b>强</b>：<b>根本不给这个参数</b>。模型只能说「发给哪个联系人」，地址由
 *       {@link OutboundMessageService} 按该联系人的档案解析。</li>
 * </ul>
 *
 * <p>这里选了后者。它把「编造」从一个<b>需要被检出</b>的错误变成<b>无法被表达</b>的行为 ——
 * 一个不存在的参数不可能被填错。代价是助手不能给「不在通讯录里的人」发信，而那正是我们想要的边界。
 *
 * <h2>参数长度上限是确认规格的一部分，不是随手取的数</h2>
 * 卡片正文的软上限是 {@code AssistantPendingActionService.SUMMARY_MAX}（4000）。正文超过它就会
 * 被<b>裸截断</b>，而一张只显示半封信的确认卡片比没有卡片更危险：用户会以为自己看全了。
 * 所以 {@link #EMAIL_BODY_MAX_CHARS} / {@link #CHATAPP_TEXT_MAX_CHARS} 是按
 * 「姓名 100（{@code contacts.display_name varchar(100)}）+ 地址 255
 * （{@code contact_identities.identity_value varchar(255)}，见 V1）+ 主题 300 + 固定文案」
 * 反推出来的，保证最坏情况也塞得进 4000。这条算术有测试钉住
 * （{@code AssistantPendingActionServiceTest}），改了上面任何一个数字它都会红。
 *
 * <h2>越权防线不在这一层</h2>
 * 归属在 {@link ContactService#getById} 与 {@code ContactIdentityMapper.findByContactIdAndOwner} 的
 * {@code where} 里。这里的预读联系人<b>不是为了授权</b>（授权在 service 里，且失败会自己报错），
 * 而是为了回话里说人话 —— 与 {@code ContactWriteAssistantTools} 同一条理由：
 * 名字取不到就退回用户问的那个 ref，绝不编一个。
 */
@Configuration(proxyBeanMethods = false)
public class MessageSendAssistantTools {

    public static final String TOOL_SEND_EMAIL = "message.send_email";
    public static final String TOOL_SEND_CHATAPP = "message.send_chatapp";

    /** 邮件主题上限。上限本身不紧，取值是为了让确认卡片塞得下（见类注释的算术）。 */
    public static final int SUBJECT_MAX_CHARS = 300;

    /** 邮件正文上限。可读性之外它还是「卡片一定显示得全」的保证，别单方面调大。 */
    public static final int EMAIL_BODY_MAX_CHARS = 3000;

    /** WhatsApp 单条文本上限。同 {@link #EMAIL_BODY_MAX_CHARS}，是确认规格的一部分。 */
    public static final int CHATAPP_TEXT_MAX_CHARS = 2000;

    /**
     * 两个工具共用的一句边界声明，写进 description。
     *
     * <p>它约束的是模型的<b>调用时机</b>：发信不可撤回，所以「用户说了什么」必须包含一个
     * 明确的发送意图。不写这条，模型很容易在用户说「帮我写一封回信」时直接把信发了 ——
     * 而那句需求的意思是「写给我看」。
     */
    private static final String WHEN_TO_CALL =
            "只有当用户明确要求发送（如「发给他」「回一封」）时才调用；"
                    + "用户只是在起草、讨论或让你改写内容时，把内容写出来给他看并问清楚，不要调用本工具。"
                    + "这是对外发送，发出后无法撤回，执行前需要用户在确认卡片上确认。";

    private final OutboundMessageService outbound;
    private final ContactService contacts;

    public MessageSendAssistantTools(OutboundMessageService outbound, ContactService contacts) {
        this.outbound = outbound;
        this.contacts = contacts;
    }

    // ---------- 声明 ----------

    @Bean
    public ToolDefinition messageSendEmailTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("contactRef", referenceTo(ContactCandidates.NAME,
                "收件人，只能取自候选联系人清单里的 contactRef（形如 CONTACT:<uuid>）"));
        properties.put("subject", stringSpec("邮件主题", "maxLength", SUBJECT_MAX_CHARS));
        properties.put("body", stringSpec("邮件正文（纯文本完整内容，不要写 Markdown 标记）",
                "maxLength", EMAIL_BODY_MAX_CHARS));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_SEND_EMAIL)
                        .title("给联系人发一封邮件")
                        .description("给一个联系人发邮件。收件地址由系统按该联系人的档案解析 —— "
                                + "本工具没有「收件地址」参数，你也不需要、无法提供地址；"
                                + "只要人不在候选联系人清单里，就发不了。"
                                + "先调用 contact.timeline 或 message.read 了解来龙去脉，再写回信。"
                                + WHEN_TO_CALL)
                        .inputSchema(objectSchema(properties, List.of("contactRef", "body")))
                        .annotations(write())
                        .build(),
                this::sendEmail);
    }

    @Bean
    public ToolDefinition messageSendChatAppTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("contactRef", referenceTo(ContactCandidates.NAME,
                "收件人，只能取自候选联系人清单里的 contactRef（形如 CONTACT:<uuid>）"));
        properties.put("text", stringSpec("消息正文（纯文本；WhatsApp 不支持 Markdown 渲染）",
                "maxLength", CHATAPP_TEXT_MAX_CHARS));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_SEND_CHATAPP)
                        .title("通过 WhatsApp 给联系人发一条消息")
                        .description("通过 WhatsApp 渠道给一个联系人发一条文本消息。"
                                + "收件号码由系统按该联系人的档案解析 —— 本工具没有「收件号码」参数。"
                                + "消息发出后进入出站队列、由渠道异步投递，因此提交成功后只能说「已提交」，"
                                + "不能说「已送达」。只支持纯文本：不支持模板消息、图片、文件。"
                                + WHEN_TO_CALL)
                        .inputSchema(objectSchema(properties, List.of("contactRef", "text")))
                        .annotations(write())
                        .build(),
                this::sendChatApp);
    }

    // ---------- 执行 ----------

    private ToolResult sendEmail(UUID userId, Map<String, Object> arguments) {
        Target target = target(userId, arguments);
        String subject = optionalText(arguments, "subject");
        String body = requiredText(arguments, "body");
        try {
            OutboundMessageService.EmailReceipt result = outbound.sendEmail(userId, target.id(), subject, body);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("contactRef", target.ref());
            data.put("to", result.to());
            data.put("from", result.from());
            data.put("subject", result.subject());
            data.put("messageId", result.messageId());
            data.put("status", result.status());
            return ToolResult.ok(describeDelivered(target.name(), result.to(), result.subject(), "邮件"), data);
        } catch (OutboundException e) {
            throw translate(e, "邮件", "邮箱地址", "邮件");
        }
    }

    private ToolResult sendChatApp(UUID userId, Map<String, Object> arguments) {
        Target target = target(userId, arguments);
        String text = requiredText(arguments, "text");
        try {
            OutboundMessageService.ChatAppReceipt accepted =
                    outbound.sendChatApp(userId, target.id(), text);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("contactRef", target.ref());
            data.put("messageId", accepted.messageId().toString());
            // status 原样带出来（当前是 pending）。它进 data 而不是被折叠进一句话，
            // 是因为「已排队」与「已投递」的区别必须能被下一轮引用到 —— 而 EXECUTED 这个词
            // 容易被读成后者（见 AssistantTurnResult 对 kind=EXECUTED 的说明）。
            data.put("status", accepted.status());
            return ToolResult.ok(
                    // 措辞刻意停在「已提交」：accept 只是把消息入队。写成「已发送给周明」
                    // 会让用户以为对方看到了，而投递失败时他不会再去看状态。
                    "已把给「" + target.name() + "」的 WhatsApp 消息提交发送（当前状态 "
                            + accepted.status() + "，投递结果要等渠道回执，可在会话里跟进）",
                    data);
        } catch (OutboundException e) {
            throw translate(e, "WhatsApp 消息", "手机号", "WhatsApp 渠道");
        }
    }

    // ---------- 参数与目标 ----------

    /**
     * 解析 {@code contactRef} 并预读联系人。
     *
     * <p>形状不对与「这个人不在你的范围内」分开报：前者是模型的参数错（该改参数），
     * 后者该重新挑人。与 {@code ContactWriteAssistantTools.target} 同一处理。
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
            contact = contacts.getById(userId, contactId);
        } catch (IllegalArgumentException e) {
            throw new ToolExecutionException(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND,
                    ToolExecutionException.ACCESS_DENIED_MESSAGE, e);
        }
        return new Target(reference, contactId, displayNameOf(contact, reference));
    }

    /** 一次调用的目标：原始 ref（data 用）、解析后的 id（service 用）、权威名字（回话用）。 */
    private record Target(String ref, UUID id, String name) {
    }

    // ---------- 渲染 ----------

    /** 成功回话。<b>必须带实际收件地址</b>：那是用户唯一能核对「发给谁了」的东西。 */
    private static String describeDelivered(String name, String address, String subject, String kind) {
        StringBuilder text = new StringBuilder("已把")
                .append(kind).append("发给「").append(name).append("」<").append(address).append(">");
        if (subject != null && !subject.isBlank()) {
            text.append("，主题「").append(subject.strip()).append("」");
        }
        return text.append("。").toString();
    }

    /**
     * 回话里用的名字：显示名 → 备注 → 退回 ref。
     *
     * <p>退回而不是编一个 —— 与 {@code ContactWriteAssistantTools.displayNameOf} 同一条口径。
     */
    private static String displayNameOf(ContactResponse contact, String fallback) {
        for (String candidate : new String[]{contact.displayName(), contact.remark()}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.strip();
            }
        }
        return fallback;
    }

    // ---------- 失败翻译 ----------

    /**
     * 服务层词表 → 工具错误码。
     *
     * <p>这一步存在的意义是<b>给模型可执行的下一步</b>，而不是复述底层发生了什么：
     *
     * <ul>
     *   <li>没有地址 → {@code INVALID_ARGUMENT} + 明说「换个人也没用，是他档案里没有」。
     *       报成 {@code INTERNAL} 会让模型换个收件人重试，而问题不在收件人。</li>
     *   <li>渠道不可用 → {@code UNAVAILABLE}：与「你没权限」区分开（前者是配置，后者是授权），
     *       同一理由见 {@code WeComAssistantTools}。</li>
     *   <li>结果未知 → 明写「不要重发」。这是唯一一个重试会造成<b>第二次真实投递</b>的失败。</li>
     * </ul>
     */
    private static ToolExecutionException translate(OutboundException e, String what,
                                                    String addressNoun, String channelNoun) {
        return switch (e.code()) {
            case "EMAIL_RECIPIENT_NOT_FOUND" -> new ToolExecutionException(
                    "EMAIL_RECIPIENT_NOT_FOUND",
                    "这次要发的邮箱地址不合法：系统只支持单一邮箱地址，不支持收件人列表或邮件组", e);
            case OutboundException.RECIPIENT_MISSING -> new ToolExecutionException(
                    ToolExecutionException.INVALID_ARGUMENT,
                    "这个联系人的档案里没有可用的" + addressNoun + "，换个人也没用 —— "
                            + "要发给他得先在通讯录里补上" + addressNoun, e);
            case OutboundException.CHANNEL_UNAVAILABLE -> new ToolExecutionException(
                    ToolExecutionException.UNAVAILABLE,
                    channelNoun + "当前不可用（账号没配好或不在你名下），这条" + what + "发不出去", e);
            case OutboundException.OUTCOME_UNKNOWN -> new ToolExecutionException(
                    ToolExecutionException.SEND_OUTCOME_UNKNOWN,
                    what + "可能已经发出去了，但系统没能记下投递结果。请不要重发，"
                            + "先到已发送里确认是否已发出", e);
            default -> new ToolExecutionException(ToolExecutionException.INTERNAL,
                    what + "发送失败，请稍后再试", e);
        };
    }

    // ---------- 参数读取 ----------

    private static String requiredText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.strip();
        }
        throw new ToolExecutionException(ToolExecutionException.MISSING_ARGUMENT, "缺少必填参数：" + key);
    }

    /** 可空字段；空白一律当作「没给」（模型很爱传空串表示「不填」）。 */
    private static String optionalText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof String text && !text.isBlank() ? text.strip() : "";
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

    private static Map<String, Object> referenceTo(String candidateSet, String description) {
        Map<String, Object> field = stringSpec(description);
        field.put(ToolInputValidator.CANDIDATE_SET, candidateSet);
        return field;
    }

    /**
     * 写动作注解。
     *
     * <p>{@code destructiveHint} 的含义是「这个动作可能造成不可逆的变更」，对外发送是它的典型情形
     * （发出去的邮件删不掉）。{@code idempotentHint=false} 也不能写反：重发一次就是真的多一封信。
     */
    private static McpSchema.ToolAnnotations write() {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(false)
                .destructiveHint(true)
                .idempotentHint(false)
                .openWorldHint(true)
                .build();
    }
}
