package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.dto.response.MessageAttachmentResponse;
import com.crmforlogistics.messagecenter.dto.response.MessageResponse;
import com.crmforlogistics.messagecenter.service.assistant.MessageCandidates;
import com.crmforlogistics.messagecenter.service.message.MessageQueryService;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消息域的唯一能力：{@code message.read} —— 「把那条消息调出来」。
 *
 * <h2>它是第一个故意把「原文」送进模型上下文的只读工具</h2>
 * 2026-09-22 定的口径是「只允许结构化字段与摘要进模型，<b>消息原文与通话转写一律不出边界</b>」，
 * 并且这条口径在代码里有三处落点（{@code ConversationCandidates} 不投影 {@code last_text}、
 * 助手的记忆上下文只走 {@code listStableContext}、{@code contact.timeline} 剥掉 {@code text}）。
 * 本类是一个反例，而且必须是<b>显式的</b>反例：2026-09-23 用户就此拍板「允许原文进上下文」。
 *
 * <p>这个决策改变的不是某一行代码，而是只读清单第 3 条判据的口径 ——
 * 原来「只读 = 不含原文」，现在「只读 = 不含<b>没必要的</b>原文」。
 * 因此这里没有把 {@code contact.timeline} 的剥离动作撤回：时间线一次回 20 条，
 * 把 20 段原文一起塞进一条 observation 既超过预算、也不是用户想看的；
 * 而 {@code message.read} 一次只取一条，且是用户点名要的那一条 —— 这才是「必要」。
 *
 * <h2>它为什么是一个新工具，而不是给 {@code contact.timeline} 加参数</h2>
 * 时间线的语义是「什么时候、通过哪个渠道、有没有联系过」，它回答的是「有没有」。
 * 把「取原文」并进去会让它在一次调用里同时承担两件事，而两者的返回体积差一个数量级
 * （一条时间线千把字符，单条消息的正文可以到几十 KB）—— 于是轻的那次也会被重的那个预算拖累。
 * 拆成两个工具之后，「先看有没有、再决定要不要看内容」变成模型自己的选择，
 * 而用户看到的是两次可解释的动作，不是一次莫名其妙的超长回答。
 *
 * <h2>它为什么只读却仍要进候选</h2>
 * 与本域之外的其余只读工具不同：{@code message.read} 的入参是一个<b>不可检索</b>的 id
 * （消息没有「按名字查」这回事）。也就是说这条路上没有「检索突破候选窗口」那一步，
 * 引用只能来自上一轮的结构化结果（{@code contact.timeline} 产出的 message 候选窗口）。
 * 于是「模型编造 id」的比对在这里是<b>唯一</b>一道程序化防线，
 * 而不是像 {@code conversation.search} 那样只是「下一轮引用得到」的便利
 * —— 少一道都不行，所以这个工具必须走候选。
 *
 * <h2>越权防线不在这一层</h2>
 * {@link MessageQueryService#getMessage(UUID, UUID)} 自带 owner 过滤
 * （{@code findByIdAndOwner}，企微历史消息再补一道 {@code requireAccessible}）。
 * 这里只负责把 {@code null} 翻译成「不在你能查看的范围内」，不重复也不放宽任何一条。
 */
@Configuration(proxyBeanMethods = false)
public class MessageAssistantTools {

    public static final String TOOL_READ = "message.read";

    /** 一个 {@code MESSAGE:<uuid>} 的长度上界（7 + 1 + 36），留余量。 */
    static final int MESSAGE_REF_MAX_CHARS = 64;

    private final MessageQueryService messages;

    public MessageAssistantTools(MessageQueryService messages) {
        this.messages = messages;
    }

    // ---------- 声明 ----------

    @Bean
    public ToolDefinition messageReadTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("messageRef", referenceTo(MessageCandidates.NAME,
                "消息标识，只能取自候选消息清单里的 messageRef（形如 MESSAGE:<uuid>）"
                        + "—— 那份清单来自 contact.timeline 的返回结果"));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_READ)
                        .title("读取一条消息的原文")
                        .description("按 messageRef 取出某一条消息的内容（正文原文、主题、收发方向、"
                                + "时间、渠道、附件清单）。适合「把那条消息调出来」「他原话是怎么说的」这类问题："
                                + "先用 contact.timeline 定位到某一条消息，再用它的 messageRef 调用这里。"
                                + "messageRef 只能来自候选消息清单 —— 清单是空的就说明还没调用过 contact.timeline，"
                                + "这时不要调用本工具，也不要用时间点自己编一个引用。"
                                + "这是只读操作。")
                        .inputSchema(objectSchema(properties, List.of("messageRef")))
                        .annotations(readOnly())
                        .build(),
                this::read);
    }

    // ---------- 执行 ----------

    private ToolResult read(UUID userId, Map<String, Object> arguments) {
        String reference = requiredText(arguments, "messageRef");
        UUID messageId = MessageCandidates.targetOf(reference);
        if (messageId == null) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "消息标识格式不正确，只能取自候选消息清单里的 messageRef");
        }

        MessageResponse message = messages.getMessage(messageId, userId);
        if (message == null) {
            // 措辞刻意不区分「不存在」与「不属于你」：区分开就等于告诉另一个账号
            // 「这条 id 是存在的」（与 ConversationAssistantTools.guarded 同口径）。
            throw new ToolExecutionException(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND,
                    ToolExecutionException.ACCESS_DENIED_MESSAGE);
        }

        return ToolResult.ok(describe(message), data(reference, message));
    }

    // ---------- 渲染 ----------

    /**
     * 结构化结果。<b>这就是「原文出边界」的那一处</b>：{@code messageText} 直接来自
     * {@code MessageResponse.bodyText}（服务端已把渠道模板还原成用户可见文本）。
     *
     * <p>{@code bodyHtml} 只在<b>没有纯文本时</b>才带：HTML 是渲染产物，
     * 里面是标签、内联样式与 base64 图片，而 observation 的预算是 4000 字符 ——
     * 两者同时带上会让一段真实内容被样式挤到截断线之外，而截断之后模型看到的是半段 HTML。
     * 没有纯文本时反过来：不带 HTML 就等于什么都没带。
     *
     * <p>字段名用 {@code messageText} 而不是 {@code text}：{@code text} 在本项目其余只读结果里
     * 已经等于「必须剥掉的正文」（见 {@code ContactTimelineAssistantTools.render} 的注释），
     * 同名不同义会让下一个接手的人以为这里漏了剥离。
     */
    private static Map<String, Object> data(String reference, MessageResponse message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageRef", reference);
        data.put("kind", message.kind());
        data.put("direction", message.direction());
        data.put("subject", message.subject());
        data.put("messageText", message.bodyText());
        if (isBlank(message.bodyText()) && !isBlank(message.bodyHtml())) {
            data.put("bodyHtml", message.bodyHtml());
        }
        data.put("channelType", message.channelType());
        // 时间一律转字符串：提示词渲染用的 ObjectMapper 不一定注册了 JavaTimeModule，
        // 放 Instant 会让整条 observation 序列化失败（同 ConversationCandidates.Item 的理由）。
        data.put("occurredAt", message.occurredAt() == null ? null : message.occurredAt().toString());
        data.put("status", message.status());

        List<Map<String, Object>> attachments = new ArrayList<>();
        for (MessageAttachmentResponse attachment : message.attachments()) {
            Map<String, Object> item = new LinkedHashMap<>();
            // 只搬运元数据，不带 id、也不带下载地址：附件的内容与位置都不是模型要用的东西，
            // 而一个可下载的地址进了提示词就等于把一次取件动作交给了模型。
            item.put("fileName", attachment.fileName());
            item.put("mimeType", attachment.mimeType());
            item.put("sizeBytes", attachment.sizeBytes());
            attachments.add(item);
        }
        data.put("attachments", attachments);
        return data;
    }

    /**
     * 给模型看的一句话：<b>只点名这条消息是哪一个</b>，正文全在 {@code data}。
     *
     * <p>刻意不在这里写正文、也不写摘要：两段会进<b>同一条</b> observation，
     * 正文在这里抄一遍等于让同一段文本占两份 4000 字符预算
     * （同 {@code AiTopicAssistantTools.describe} 的理由）。
     */
    private static String describe(MessageResponse message) {
        StringBuilder text = new StringBuilder("找到了这条消息：");
        text.append(message.occurredAt() == null ? "时间未知" : message.occurredAt().toString());
        text.append("，").append("inbound".equalsIgnoreCase(message.direction()) ? "收到" : "发出");
        if (!isBlank(message.subject())) {
            text.append("，主题「").append(message.subject().strip()).append("」");
        }
        if (!isBlank(message.kind())) {
            text.append("，类型 ").append(message.kind());
        }
        return text.append("；正文在 messageText 字段里。").toString();
    }

    // ---------- 参数读取 ----------

    private static String requiredText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.strip();
        }
        throw new ToolExecutionException(ToolExecutionException.MISSING_ARGUMENT, "缺少必填参数：" + key);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
        Map<String, Object> field = stringSpec(description, "maxLength", MESSAGE_REF_MAX_CHARS);
        field.put(ToolInputValidator.CANDIDATE_SET, candidateSet);
        return field;
    }

    /** 只读注解。{@code readOnlyHint=true} 是「进只读清单」的<b>声明</b>；权威在 {@code AssistantActionPolicy}。 */
    private static McpSchema.ToolAnnotations readOnly() {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(true)
                .destructiveHint(false)
                .idempotentHint(true)
                .openWorldHint(false)
                .build();
    }
}
