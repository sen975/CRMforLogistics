package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.dto.response.ContactResponse;
import com.crmforlogistics.messagecenter.dto.response.TimelineResponse;
import com.crmforlogistics.messagecenter.service.assistant.ContactCandidates;
import com.crmforlogistics.messagecenter.service.assistant.MessageCandidates;
import com.crmforlogistics.messagecenter.service.callrecord.CallRecordException;
import com.crmforlogistics.messagecenter.service.callrecord.ContactTimelineService;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 联系人跨渠道往来时间线（{@code contact.timeline}）—— 「他最近怎么了」的那一半答案。
 *
 * <h2>为什么它单独一个类，而不是并进 {@code ContactAssistantTools}</h2>
 * 它跟 {@code contact.search} / {@code contact.brief} 同为只读，但数据来源完全不同：
 * 前两个走联系人表与记忆库，这个走 {@link ContactTimelineService}（消息与通话的<b>合并时序</b>）。
 * 更要紧的是它比那两个多一道手工程序：<b>必须在工具层剥掉消息正文</b>（见下）。
 * 把这段"剥"的逻辑和它的理由放在一个信息量只有它自己的类里，比塞进一个
 * 已经有 270 行、讲着另外两个工具分工的类更容易被读到。
 *
 * <h2>剥掉 {@code text}：这不是实现细节，是口径 b 的落点</h2>
 * {@code ContactTimelineService.toResponse} 会把 {@code message.bodyText} 原样放进
 * payload 的 {@code text} 字段 —— 那是<b>客户原话</b>。而 2026-09-22 定下的口径是：
 *
 * <blockquote>
 *   只允许结构化字段与摘要进模型，<b>消息原文与通话转写一律不出边界</b>。
 * </blockquote>
 *
 * <p>这条口径在代码里已经有两处落点（{@code ConversationCandidates} 刻意不投影
 * {@code last_text}；助手的记忆上下文只走 {@code listStableContext}）。这里是第三处。
 *
 * <p><b>为什么是在工具里剥，而不是让编排层"记得别取"</b>：这个类只调
 * {@code ContactTimelineService}，而那个方法<b>一定会</b>返回 {@code text} ——
 * 也就是说，"不取"这个动作在这里不存在，只能"取到之后丢掉"。丢掉的动作与理由必须写在一起，
 * 否则下一个复用这条时间线的人（比如将来做跨渠道总结）会拿到一份带正文的响应，
 * 而没有任何东西提示他那是不该出边界的。
 *
 * <h2>描述里必须说"不含正文"，否则模型会编</h2>
 * 这个工具的价值是「什么时候、通过哪个渠道、什么方向、多久、转写好了没」。
 * 模型拿到一串时间点却没有任何内容时，最危险的失败不是答"我不知道"，
 * 而是顺着用户名里的猜测补一句「他上周说想下单」——因此 tool description 里
 * 显式写明「不含消息正文与通话转写，不要推测双方说过什么」。
 *
 * <h2>它为什么也是 {@code message.read} 的入口（2026-09-23）</h2>
 * 这个工具原来刻意不带 {@code sortId}：那时没有任何工具消费消息 id，带上它只是把一份
 * 用不上的内部标识送出去。原注释里留了一句前提 ——「将来真要'对某一条动手'，
 * 第一件事是给它一组候选 + 一个引用参数」。
 *
 * <p>{@code message.read}（C4）落地后那个前提成立了，因此这里补上了那两件事，
 * 顺序与本项目其余引用参数完全一致：<b>时间线上的每条消息带一个 {@code messageRef}，
 * 同时这些 ref 构成本轮的 {@code message} 候选窗口</b>。于是「先看时间线、再把某一条的原文调出来」
 * 变成一个两轮内可完成、且第二轮仍受候选比对约束的动作 —— 模型编不出 ref，
 * 因为它要引用的那一组候选只能由这一轮的时间线结果产生。
 *
 * <p><b>通话为什么仍然不给 ref</b>：通话域的可读能力（{@code call.read}，E4）还没做，
 * 给它一个没有消费者的 ref 只会让模型以为可以引用、然后在提问路径被拒。
 * 「有消费方了才给 id」这条约束在这里是逐条记录判的，不是整个工具判的。
 */
@Configuration(proxyBeanMethods = false)
public class ContactTimelineAssistantTools {

    public static final String TOOL_TIMELINE = "contact.timeline";

    /**
     * 一次读回的往来条数上限。
     *
     * <p>不暴露成参数：这是"最近一次往来大概长什么样"的概览，不是分页接口。
     * 让模型自己填 limit 只会让它填 100，把一段无界历史搬进提示词。
     */
    static final int LIMIT = 20;

    private final ContactTimelineService timelineService;
    private final ContactService contactService;

    public ContactTimelineAssistantTools(ContactTimelineService timelineService,
                                         ContactService contactService) {
        this.timelineService = timelineService;
        this.contactService = contactService;
    }

    // ---------- 声明 ----------

    @Bean
    public ToolDefinition contactTimelineTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("contactRef", referenceTo(ContactCandidates.NAME,
                "联系人标识，只能取自候选联系人清单里的 contactRef（形如 CONTACT:<uuid>）"));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_TIMELINE)
                        .title("查询联系人往来时间线")
                        .description("按时间倒序列出一个联系人最近的往来记录：每条是什么类型（消息／通话）、"
                                + "发生在什么时候、方向（收/发）、消息状态，通话还带时长与转写状态；"
                                + "每条消息还带一个 messageRef（可以拿去调 message.read）。"
                                + "适合「我上次跟他联系是什么时候」「最近有没有通过电话」「那条消息发出去了吗」这类问题，"
                                + "也适合先用它定位到某一条消息、再用 message.read 取那一条的原文。"
                                + "注意：这个工具本身不返回消息正文，也不返回通话转写 —— "
                                + "不要根据这条结果推测双方说过什么。用户若想看某一条消息的内容，"
                                + "就用结果里的 messageRef 调用 message.read，不要凭时间点自己编一段。"
                                + "这是只读操作；结果会替换候选消息清单。"
                                + "contactRef 只能来自候选联系人清单，清单里找不到时不要调用。")
                        .inputSchema(objectSchema(properties, List.of("contactRef")))
                        .annotations(readOnly())
                        .build(),
                this::timeline);
    }

    // ---------- 执行 ----------

    private ToolResult timeline(UUID userId, Map<String, Object> arguments) {
        String reference = requiredText(arguments, "contactRef");
        UUID contactId = ContactCandidates.targetOf(reference);
        if (contactId == null) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "联系人标识格式不正确，只能取自候选联系人清单里的 contactRef");
        }

        String name = fallbackName(userId, contactId, reference);
        TimelineResponse response;
        try {
            response = timelineService.timeline(userId, contactId, null, LIMIT);
        } catch (CallRecordException e) {
            if (!"CONTACT_NOT_FOUND".equals(e.code())) {
                throw new ToolExecutionException(ToolExecutionException.INTERNAL, "执行失败，请稍后再试", e);
            }
            throw new ToolExecutionException(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND,
                    ToolExecutionException.ACCESS_DENIED_MESSAGE, e);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        List<MessageCandidates.Item> messageCandidates = new ArrayList<>();
        int messageCount = 0;
        int callCount = 0;
        for (TimelineResponse.TimelineItem item : response.items()) {
            boolean isCall = "callRecord".equals(item.type());
            // ref 只算一次，渲染与候选共用同一个值：若两处各算一遍，早晚会出现
            // 「结果里有 messageRef、候选里没有」这种下一轮必然被拒的形状，而它只在多轮里才暴露。
            String messageRef = isCall ? null : messageRefOf(item);
            Map<String, Object> rendered = render(item, messageRef);
            if (isCall) {
                callCount++;
            } else {
                messageCount++;
                if (messageRef != null) {
                    messageCandidates.add(messageCandidate(item, messageRef));
                }
            }
            items.add(rendered);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contactRef", reference);
        data.put("messageCount", messageCount);
        data.put("callCount", callCount);
        data.put("items", items);

        if (items.isEmpty()) {
            return ToolResult.ok("「" + name + "」还没有可显示的往来记录", data);
        }
        String description = describe(name, messageCount, callCount, response);
        if (messageCandidates.isEmpty()) {
            // 一条消息都没有（或都没有 id）→ 不替换候选窗口。
            // 与 conversation.search 的空结果同一条理由：这次没产出候选，不该把别处刚发现的那一批抹掉。
            return ToolResult.ok(description, data);
        }
        // 结果替换候选窗口：这是「先看时间线、再把某一条的原文调出来」这条两轮链路的落点。
        return ToolResult.discovered(description, data,
                new MessageCandidates(MessageCandidates.LIMIT, messageCandidates));
    }

    /**
     * 单条往来的白名单投影。
     *
     * <p><b>这里就是剥正文的那一步</b>：{@code payload.text} 一律不搬运。
     * 其余字段照原样带上 —— 它们是结构化元数据（方向、状态、时长、转写状态），
     * 不含用户或客户写的自由文本。
     */
    private static Map<String, Object> render(TimelineResponse.TimelineItem item, String messageRef) {
        Map<String, Object> raw = item.payload() == null ? Map.of() : item.payload();
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", item.type());
        // Instant.toString() 而不是把 Instant 放进去：提示词渲染用的 ObjectMapper
        // 不一定注册了 JavaTimeModule，放 Instant 会让整条 observation 序列化失败，
        // 而那种失败的表现是"这一轮什么都没有"，查起来极难（同 ConversationCandidates.Item 的理由）。
        rendered.put("occurredAt", item.occurredAt() == null ? null : item.occurredAt().toString());
        if ("callRecord".equals(item.type())) {
            // 通话刻意不带 ref：call.read（E4）还不存在，给它一个没有消费方的 id
            // 只会让模型以为可以引用、然后在提问路径被拒（详见类注释）。
            copy(raw, rendered, "direction");
            copy(raw, rendered, "durationSeconds");
            copy(raw, rendered, "state");
            copy(raw, rendered, "attempts");
        } else {
            copy(raw, rendered, "direction");
            copy(raw, rendered, "status");
            // 消息 id 在这里第一次出现在工具结果里。它不是「顺手带上的内部标识」：
            // messageRef 是 message.read 唯一的入参来源，且同时进了候选窗口
            // （见 messageCandidate），所以下一轮引用它会通过候选比对。
            if (messageRef != null) {
                rendered.put("messageRef", messageRef);
            }
        }
        return rendered;
    }

    /**
     * 时间线条目 → {@code MESSAGE:<uuid>} 引用；拿不到合法 id 时返回 {@code null}。
     *
     * <p>{@code sortId} 是消息 id 的字符串形式，服务层在 id 为空时会给空串（见
     * {@code ContactTimelineService}）。所以这里必须按「解析失败」处理而不是直接拼：
     * 一个 {@code MESSAGE:} 这样的空引用进了结果，模型照抄之后会在提问路径被拒，
     * 而用户看到的是「它老说找不到那条消息」。
     */
    private static String messageRefOf(TimelineResponse.TimelineItem item) {
        if (item.sortId() == null || item.sortId().isBlank()) {
            return null;
        }
        try {
            return MessageCandidates.idOf(UUID.fromString(item.sortId()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 与 {@link #messageRefOf} 同源：ref 外面的那层字符串就是这里生成的，不存在第二个拼法。 */
    private static MessageCandidates.Item messageCandidate(TimelineResponse.TimelineItem item, String messageRef) {
        Map<String, Object> raw = item.payload() == null ? Map.of() : item.payload();
        return new MessageCandidates.Item(
                messageRef,
                stringOrNull(raw.get("direction")),
                stringOrNull(raw.get("status")),
                item.occurredAt() == null ? null : item.occurredAt().toString());
    }

    private static String stringOrNull(Object value) {
        return value instanceof String text ? text : null;
    }

    /** 白名单式搬运：只搬下面点名要的字段，绝不整个 payload 透传（那会把 text 带出去）。 */
    private static void copy(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) {
            to.put(key, from.get(key));
        }
    }

    private static String describe(String name, int messageCount, int callCount, TimelineResponse response) {
        List<String> parts = new ArrayList<>();
        if (messageCount > 0) {
            parts.add(messageCount + " 条消息");
        }
        if (callCount > 0) {
            parts.add(callCount + " 通电话");
        }
        StringBuilder text = new StringBuilder("「").append(name).append("」最近 ")
                .append(parts.isEmpty() ? "没有往来" : String.join("、", parts));
        // 截断必须说出来：不说的话模型会把"只给了 20 条"当成"一共只有 20 条"。
        if (response.itemCount() > response.items().size()) {
            text.append("，共 ").append(response.itemCount()).append(" 条历史往来，只显示最近 ")
                    .append(response.items().size()).append(" 条");
        }
        return text.append("。这里只有时间和渠道，没有消息正文与通话内容").toString();
    }

    // ---------- 参数读取 ----------

    /**
     * 回话里用的名字：显示名 → 备注 → 退回 ref。
     *
     * <p>取值失败不抛：名字取不到不该让这次只读检索失败 —— 时间线本身已经拿到了。
     * 退回 ref 而不是编一个（同 {@code ContactGroupService} 域的口径）。
     */
    private String fallbackName(UUID userId, UUID contactId, String reference) {
        try {
            ContactResponse contact = contactService.getById(userId, contactId);
            for (String candidate : new String[]{contact.displayName(), contact.remark()}) {
                if (candidate != null && !candidate.isBlank()) {
                    return candidate.strip();
                }
            }
        } catch (RuntimeException e) {
            // 刻意吞掉：这只是为了把回话说得像人话，失败不该把一次成功的检索变成错误。
            // 真正的授权判定在时间线查询自己那一步（它按 ownerId 过滤）。
        }
        return reference;
    }

    private static String requiredText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.strip();
        }
        throw new ToolExecutionException(ToolExecutionException.MISSING_ARGUMENT, "缺少必填参数：" + key);
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

    private static Map<String, Object> stringSpec(String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "string");
        field.put("description", description);
        return field;
    }

    private static Map<String, Object> referenceTo(String candidateSet, String description) {
        Map<String, Object> field = stringSpec(description);
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
