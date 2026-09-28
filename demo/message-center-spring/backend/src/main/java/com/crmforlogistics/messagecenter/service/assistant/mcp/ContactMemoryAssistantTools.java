package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.service.assistant.ContactCandidates;
import com.crmforlogistics.messagecenter.service.contactmemory.ContactMemoryRecomputeService;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AI 记忆域：目前只有一个动作 —— {@code contact.refresh_memory}（重算画像与标签）。
 *
 * <h2>它补的是哪一类缺口</h2>
 * 在这之前，联系人记忆（{@code contact_memory_*} / {@code contact_ai_labels}）对助手来说是
 * <b>只读而且只读一半</b>的：{@code contact.brief} 能读出标签名与画像，而<b>触发</b>这件事
 * 在系统里根本没有手动入口 —— 唯一触发源是「入站消息落库」
 * （{@code ContactMemoryTriggerService.markInboundPersisted}，其 SQL 要求存在一条真实的
 * inbound {@code messages} 行）。于是「帮我重算他的 AI 标签」这句话在助手这边没有对应动作，
 * 在页面上也没有按钮。这个工具就是那个入口。
 *
 * <h2>它<b>不</b>承诺「立刻生效」</h2>
 * 重算是后台任务：调用方提交的是一个「请尽快处理」的标记，真正跑模型的是
 * {@code ContactMemoryWorker}，而它受 {@code ContactMemoryScheduler} 的处理窗口约束。
 * 所以工具说明里写死了这件事，并把四种结果分成四句不同的话 ——
 * 其中 {@code NOTHING_NEW}（没有新内容，什么都不会变）与 {@code SUBMITTED} 的区别尤其重要：
 * 说反了，用户会以为「点了没反应」是坏了。
 *
 * <h2>为什么要确认</h2>
 * 它不改用户填的字段，但会<b>让模型改写画像与标签</b>（画像新增版本、标签置信度变化）。
 * 按 {@code AssistantActionPolicy} 的判据（「最坏情况用户会失去什么」）它不该免确认，
 * 也没有理由进只读清单 —— 它是会花模型调用、且改写已生成内容的一步。
 */
@Configuration(proxyBeanMethods = false)
public class ContactMemoryAssistantTools {

    public static final String TOOL_REFRESH_MEMORY = "contact.refresh_memory";

    private final ContactMemoryRecomputeService memory;

    public ContactMemoryAssistantTools(ContactMemoryRecomputeService memory) {
        this.memory = memory;
    }

    @Bean
    public ToolDefinition contactRefreshMemoryTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("contactRef", referenceTo(ContactCandidates.NAME,
                "联系人标识，只能取自候选联系人清单里的 contactRef（形如 CONTACT:<uuid>）"));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_REFRESH_MEMORY)
                        .title("重新生成联系人的 AI 画像与标签")
                        .description("触发一次该联系人的 AI 画像与标签重算（增量）：系统会读取他尚未纳入画像的"
                                + "往来内容，交给模型重新归纳，已有标签与画像可能因此被改写，"
                                + "所以这是一次会改动数据的操作，执行前需要用户确认。"
                                + "只有这位联系人的画像归属人（录入者）才重算得了。"
                                + "它是后台任务，受处理窗口约束 —— <b>提交成功不代表已经更新</b>，"
                                + "不要对用户说「画像已经更新好了」，只说已提交、让他稍后再问一次。"
                                + "没有新的往来内容时不会产生任何变化，工具会明确告诉你这一点。"
                                + "contactRef 只能来自候选联系人清单；清单里找不到对应联系人时不要调用，"
                                + "改为向用户说明没找到。")
                        .inputSchema(objectSchema(properties, List.of("contactRef")))
                        .annotations(write())
                        .build(),
                this::refreshMemory);
    }

    // ---------- 执行 ----------

    private ToolResult refreshMemory(UUID userId, Map<String, Object> arguments) {
        ContactRef target = contactRef(arguments);
        Optional<ContactMemoryRecomputeService.Outcome> outcome = memory.requestRecompute(userId, target.id());
        if (outcome.isEmpty()) {
            // 归属对不上（记忆以 contacts.created_by 为归属人）。这里是**拒绝**而不是「正常但为空」：
            // 与 contact.brief 的 memoryVisible=false 不同，那一次是「答不了」，这一次是「做不了」——
            // 让模型照实说「重算不了」，比给它一个看起来成功的回执要好。
            throw new ToolExecutionException(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND,
                    ToolExecutionException.ACCESS_DENIED_MESSAGE);
        }

        ContactMemoryRecomputeService.Outcome result = outcome.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contactRef", target.ref());
        data.put("status", result.name());

        String message = switch (result) {
            // 措辞刻意不写「已更新」：提交与生效之间隔着后台 worker 与处理窗口。
            case SUBMITTED -> "已提交重算，稍后可以再问我一次他的画像和标签";
            case ALREADY_PENDING -> "他已经排在待重算的队列里了，不需要再提交一次";
            case IN_PROGRESS -> "正在重算中，稍后再问我一次";
            case NOTHING_NEW -> "没有新的往来内容，这次重算不会产生任何变化（没有做任何改动）";
        };
        return ToolResult.ok(message, data);
    }

    // ---------- 参数 ----------

    private static ContactRef contactRef(Map<String, Object> arguments) {
        String reference = requiredText(arguments, "contactRef");
        UUID contactId = ContactCandidates.targetOf(reference);
        if (contactId == null) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "联系人标识格式不正确，只能取自候选联系人清单里的 contactRef");
        }
        return new ContactRef(reference, contactId);
    }

    /** 一次调用的联系人目标：原始 ref（回话与 data 用）与解析后的 id（服务调用用）。 */
    private record ContactRef(String ref, UUID id) {
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
     * <p>{@code destructiveHint=true}：它会让模型改写已有的画像与标签。规范里未声明即按
     * {@code true} 解释，这里显式写出来是为了让「它是有损的」在声明里可读 ——
     * 而「能不能免确认」由 {@code AssistantActionPolicy} 决定，不看这里。
     *
     * <p>{@code openWorldHint=false}：它只动本系统自己的数据，不对外发东西。
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
