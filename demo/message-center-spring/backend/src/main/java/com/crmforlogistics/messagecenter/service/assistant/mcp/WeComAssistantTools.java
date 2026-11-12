package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.service.assistant.ConversationCandidates;
import com.crmforlogistics.messagecenter.service.wecom.WeComSelfPushService;
import com.crmforlogistics.messagecenter.service.wecom.WeComSummaryReadService;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 企业微信域的助手能力：{@code wecom.summary_read}（「那个群昨天聊了啥」）与
 * {@code wecom.push_self}（「把这条推给我」）。
 *
 * <h2>{@code wecom.push_self}（2026-09-28 加）是这个助手唯一一个免确认的对外动作</h2>
 * 此前所有「能造成外部后果」的写工具一律过确认卡片（{@code message.send_email} 是典型）。
 * 这一个刻意<b>不进</b>确认队列，直接执行，理由三条：
 *
 * <ol>
 *   <li><b>收件人不是参数</b>：它由调用方身份解析（{@code sendToBoundUser(userId, …)} 内部走
 *       {@code WeComUserBindingService.requireByUserId}）。模型能编的只有内容，
 *       而一个编不出来的收件人不可能被编错 —— 与 {@code message.send_email} 刻意不给地址参数同源。</li>
 *   <li><b>最坏后果是「自己多收一条消息」</b>：这不是「不可撤回」（推出去的消息删不掉），
 *       但它落在<b>自己</b>的企微里，可核对、可忽略、可顺手删，不构成对外风险。
 *       确认卡片要保护的是「用户不知道系统会替他发给别人」这一类事，这里不成立。</li>
 *   <li><b>它的典型用途是「把刚生成的东西带走」</b>：会议报告、待办清单 ——
 *       用户说完「推送过去」之后要的就是它立刻到手机上。再让他点一次确认是把动作拆成两步，
 *       而第二步没有新增任何判断依据（卡片上显示的内容他刚在对话里看过）。</li>
 * </ol>
 *
 * <p>代价必须写清楚，免得后来人以为这是疏漏：<b>模型可以在用户没说「推送」的时候调用它</b>。
 * 兜底的是描述里的时机约束（同 {@code MessageSendAssistantTools.WHEN_TO_CALL}）与
 * {@code AssistantActionPolicy} 的档位声明 —— 档位在那边，
 * 这里的 {@code annotations} 只是给模型看的提示。
 *
 * <h2>它读的不是原文，是 AI 摘要</h2>
 * 本项目的企微链路里已经有一条完整的「消息 → AI 摘要」通道（{@code WeComMessageSummaryWorker}
 * 把群消息交给企微侧生成摘要，落 {@code wecom_message_summary_jobs}）。这条通道的存在
 * 让企微成为<b>唯一一个「内容总结」已经现成的域</b> —— 不用新做模型调用，
 * 只是把已有的摘要读出来。
 *
 * <p>也正因为它回的是<b>摘要</b>而不是原文，这个工具与 {@code message.read}（C4）不是重复的：
 * 一个回答「他们聊了什么」，一个回答「他原话是怎么说的」。用户问前者时读 20 条摘要，
 * 比读 20 段原文又便宜又准。
 *
 * <h2>它比同域其余候选工具多一道前置条件</h2>
 * 其余只读工具底下都是一条已经带 {@code where user_id} 的服务方法。这个不一样：
 * 现有的摘要读链路（{@code WeComMessageSummaryController} → {@code mapper.search}）
 * <b>全链路没有用户过滤</b>，它按部署常量取 {@code installationId}。所以这个工具先要有
 * {@link WeComSummaryReadService} 那条按 owner 过滤的读路径 —— 见该类的类注释，
 * 那里解释了为什么这不能靠「加个声明」解决。
 *
 * <h2>引用参数为什么绑会话候选而不是自己造一组</h2>
 * 「那个群」在用户嘴里是群名，在协议里必须是一个 id。项目里已经有一处把「企业与群」
 * 统一表达成候选的地方（{@code ConversationCandidates} 的 {@code WECOM_GROUP:<uuid>}），
 * 而 {@code conversation.search} 已经在产出它、{@code conversation.pin} 已经在消费它。
 * 再建一组「企微群候选」会得到一个形状完全相同、只是少了几种类型的东西，
 * 而两组引用之间的错配（拿群 ref 去读消息、拿联系人 ref 去读群摘要）在提问路径就断了。
 *
 * <p>代价是这个工具要自己拒掉 {@code CONTACT:} 类型的那一半候选 —— 见 {@link #read}。
 */
@Configuration(proxyBeanMethods = false)
public class WeComAssistantTools {

    public static final String TOOL_SUMMARY_READ = "wecom.summary_read";
    public static final String TOOL_PUSH_SELF = "wecom.push_self";

    /**
     * 默认回看天数。
     *
     * <p>7 而不是 1：「那个群最近聊了啥」比「昨天」更常出现，而 7 天窗口在摘要粒度下
     * 通常也就十几条，正好落在一次读回的上限内。用户明确说「昨天」时模型会自己传 1。
     */
    static final int DEFAULT_DAYS = 7;

    /** 一个 {@code WECOM_GROUP:<uuid>} 的长度上界（12 + 1 + 36），留余量。 */
    static final int GROUP_REF_MAX_CHARS = 64;

    /**
     * 功能不可用时的同一句话，两条路径共用。
     *
     * <p>「模块没启用 ⇒ 服务不存在」与「模块启用了但摘要仓储没装配（未配 suite-id）」
     * 对用户是同一件事：都要管理员去开配置。给两种措辞只会让模型在回话里复述一个
     * 它无从分辨的差别（同 {@code WeComSummaryReadService} 的注释）。
     */
    static final String UNAVAILABLE_MESSAGE = "企业微信聊天摘要功能当前没有启用，需要管理员开启后再试";

    /**
     * 摘要读取服务<b>可能根本不存在</b>。
     *
     * <p>它是企微模块 Bean（{@code @ConditionalOnWeComEnabled} 于
     * {@code WeComSummaryReadService}）—— 关掉企微开关时这个类不会被装配。
     * 所以这里注入 {@link ObjectProvider} 而不是直接注入。
     *
     * <p>反过来说，<b>本工具自己必须始终存在</b>：一是 {@code tools/list} 的形状要稳定，
     * 二是 {@code AssistantActionPolicy} 的只读清单里就写着 {@code wecom.summary_read}，
     * 工具缺席会让启动自检「清单里有、注册表里没有」当场失败。缺席只在调用时翻译成
     * {@link ToolExecutionException#UNAVAILABLE}。
     */
    private final ObjectProvider<WeComSummaryReadService> summaries;

    /**
     * 推送门面，可能不存在（企微没启用 / 没配 suite-id / 没有绑定）。
     *
     * <p>它住在 {@code service.wecom} 而不是 {@code channel.wecom}，是<b>架构门禁</b>要求的：
     * 本类所在包不在 {@code ArchitectureBoundaryTest.CHANNEL_AWARE_SERVICE_PACKAGES} 里，
     * 直接依赖 {@code WeComSendService} 会让构建变红。门禁注释禁止「为了绕过门禁而加白名单」，
     * 所以正确做法是那一层门面，而不是往白名单里添一行 —— 见 {@code WeComSelfPushService} 的类注释。
     */
    private final ObjectProvider<WeComSelfPushService> selfPush;

    public WeComAssistantTools(ObjectProvider<WeComSummaryReadService> summaries,
                               ObjectProvider<WeComSelfPushService> selfPush) {
        this.summaries = summaries;
        this.selfPush = selfPush;
    }

    // ---------- 声明 ----------

    @Bean
    public ToolDefinition wecomSummaryReadTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("groupRef", referenceTo(ConversationCandidates.NAME,
                "企业微信群标识，只能取自候选会话清单里 type 为 WECOM_GROUP 的 conversationRef"
                        + "（形如 WECOM_GROUP:<uuid>）；联系人类型的 conversationRef 不能用在这里"));
        properties.put("days", integerSpec(
                "回看最近多少天，1 到 " + WeComSummaryReadService.MAX_DAYS
                        + " 之间；不传表示 " + DEFAULT_DAYS + " 天。用户说「昨天」时传 1",
                WeComSummaryReadService.MIN_DAYS, WeComSummaryReadService.MAX_DAYS));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_SUMMARY_READ)
                        .title("查询企业微信群的聊天摘要")
                        .description("读出某个企业微信群在最近一段时间内已经生成好的 AI 聊天摘要，"
                                + "按时间倒序，每条含时间和摘要内容。适合「那个群昨天聊了啥」"
                                + "「客户群里最近有没有提价格」这类问题。"
                                + "注意：这里返回的是系统<b>已经生成完成</b>的摘要，不是消息原文 —— "
                                + "如果某段时间没有摘要，如实说明（可能还在生成，也可能那个时间段没有内容），"
                                + "不要据此推测群里说过什么。这是只读操作。"
                                + "groupRef 只能来自候选会话清单里的企业微信群，找不到时不要调用。")
                        .inputSchema(objectSchema(properties, List.of("groupRef")))
                        .annotations(readOnly())
                        .build(),
                this::read);
    }

    @Bean
    public ToolDefinition wecomPushSelfTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("text", stringSpec(
                "要推送的正文（纯文本），最多 " + WeComSelfPushService.MAX_TEXT_CHARS
                        + " 个字符。超过就先压缩成摘要 —— 企业微信单条文本消息放不下长文档",
                "maxLength", WeComSelfPushService.MAX_TEXT_CHARS));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_PUSH_SELF)
                        .title("把一条消息推送到我的企业微信")
                        .description("把一段文本推送到<b>和你当前账号绑定的那个企业微信</b>，"
                                + "你会立刻在企微里收到。适合「把刚才那份报告/清单推给我」这类要求。"
                                + "本工具没有「推给谁」参数：收件人固定是你自己，"
                                + "你无法、也不需要指定别人。"
                                + "内容由你组织后传入，所以请把要带走的东西写完整、可以直接用；"
                                + "它是纯文本，不要写 Markdown 标记。"
                                + "**只有当用户明确要求推送（如「推给我」「发到企微」「发到我微信」）时才调用**；"
                                + "用户只是让你生成内容时，不要顺手推送。这是对外发送，发出后撤不回。")
                        .inputSchema(objectSchema(properties, List.of("text")))
                        .annotations(pushAllowed())
                        .build(),
                this::pushSelf);
    }

    // ---------- 执行 ----------

    /**
     * 推送给调用方自己。
     *
     * <p>四类失败分别翻译：<b>内容问题</b>（长度）与<b>功能不可用</b>必须分开 ——
     * 前者模型自己能改，后者换什么内容都没用；<b>结果未知</b>必须单独一档，
     * 因为它是唯一一个「重试 = 第二条真实消息」的失败。
     */
    private ToolResult pushSelf(UUID userId, Map<String, Object> arguments) {
        String text = requiredText(arguments, "text");

        WeComSelfPushService service = selfPush.getIfAvailable();
        if (service == null) {
            throw new ToolExecutionException(ToolExecutionException.UNAVAILABLE,
                    WeComSelfPushService.UNAVAILABLE_MESSAGE);
        }

        try {
            WeComSelfPushService.PushOutcome outcome = service.push(userId, text);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "sent");
            if (outcome.messageId() != null && !outcome.messageId().isBlank()) {
                data.put("messageId", outcome.messageId());
            }
            // 措辞只说「已推送」。企微那边此刻确实是收到了（同步接口回了 errcode=0），
            // 所以这里不像 WhatsApp 那样只能说「已提交」——两者机制不同，措辞也不该统一。
            return ToolResult.ok("已把这条消息推送到你的企业微信", data);
        } catch (IllegalArgumentException e) {
            // 门面在本地拦下的长度问题，message 已经是给用户看的话。
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ToolExecutionException(ToolExecutionException.UNAVAILABLE, e.getMessage(), e);
        } catch (WeComSelfPushService.OutcomeUnknown e) {
            throw new ToolExecutionException(ToolExecutionException.SEND_OUTCOME_UNKNOWN, e.getMessage(), e);
        } catch (WeComSelfPushService.PushRejected e) {
            // 上游明确拒绝（errcode ≠ 0）：确定没送达，所以可以重试，但重试前该先看看哪里不对。
            throw new ToolExecutionException(ToolExecutionException.INTERNAL, e.getMessage(), e);
        }
    }

    private ToolResult read(UUID userId, Map<String, Object> arguments) {
        String reference = requiredText(arguments, "groupRef");
        String type = ConversationCandidates.typeOf(reference);
        UUID sourceConversationId = ConversationCandidates.targetOf(reference);
        if (type == null || sourceConversationId == null) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "会话标识格式不正确，只能取自候选会话清单里的 conversationRef");
        }
        if (!ConversationCandidates.TYPE_WECOM_GROUP.equals(type)) {
            // 候选清单里一半是联系人、一半是企微群，两者形状一样，所以"拿错了"这件事
            // 只能在这里判。措辞要说清「该用哪一种」，否则模型会反复重试同一个错引用。
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "这里只能读取企业微信群的摘要，不能读取联系人的会话；"
                            + "请改用一个 type 为 WECOM_GROUP 的会话");
        }
        int days = optionalInteger(arguments, "days", DEFAULT_DAYS);

        // 「服务在不在」排在参数与归属判断<b>之后</b>、调用之前：放最前面会让「功能没启用」
        // 盖住「引用格式不对」，而后者是模型自己能修的那一类，盖住就等于让它卡在一个
        // 它其实能绕开的错误上（这也是 ToolRegistry 让 503 压过 400 时踩过的同一个取舍）。
        WeComSummaryReadService service = summaries.getIfAvailable();
        if (service == null) {
            throw new ToolExecutionException(ToolExecutionException.UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }

        WeComSummaryReadService.SummaryWindow window;
        try {
            window = service.read(userId, sourceConversationId, days);
        } catch (IllegalStateException e) {
            // 功能没装配与「你没权限」是两件事，必须分开讲（同 WeComSummaryReadService 的注释）。
            throw new ToolExecutionException(ToolExecutionException.UNAVAILABLE, UNAVAILABLE_MESSAGE, e);
        } catch (IllegalArgumentException e) {
            throw new ToolExecutionException(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND,
                    ToolExecutionException.ACCESS_DENIED_MESSAGE, e);
        }

        String name = displayName(window.groupName(), reference);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groupRef", reference);
        data.put("groupName", name);
        data.put("days", days);
        data.put("count", window.items().size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (WeComSummaryReadService.Summary summary : window.items()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("occurredAt", summary.occurredAt());
            item.put("summary", summary.summary());
            items.add(item);
        }
        data.put("items", items);
        data.put("truncated", window.truncated());

        return ToolResult.ok(describe(name, days, window), data);
    }

    // ---------- 渲染 ----------

    /**
     * 给模型看的一句话。
     *
     * <p>刻意<b>不</b>在这里抄摘要内容：摘要明细在 {@code data.items} 里，两段会进同一条
     * observation，抄一遍等于让同一段文本占两份 4000 字符预算（同
     * {@code AiTopicAssistantTools.describe} 的理由）。这里只负责说清「有几条、是哪一段时间的」。
     *
     * <p>三条分支的分母是「这个窗口里有没有任务」，不是「有没有摘要」：只有把
     * 「还在生成」与「确实没有内容」分开，模型才不会在空结果上补一句编造的解释。
     */
    private static String describe(String name, int days, WeComSummaryReadService.SummaryWindow window) {
        if (window.items().isEmpty()) {
            return window.hasAnyJob()
                    ? "「" + name + "」最近 " + days + " 天还没有生成完成的聊天摘要，可能还在生成中，稍后再问我一次"
                    : "「" + name + "」最近 " + days + " 天没有找到可总结的聊天内容";
        }
        StringBuilder text = new StringBuilder("「").append(name).append("」最近 ").append(days)
                .append(" 天有 ").append(window.items().size()).append(" 条聊天摘要（按时间倒序，内容见 items）");
        if (window.truncated()) {
            // 截断必须说出来：不说的话模型会把"只给了 20 条"当成"一共只有 20 条"。
            text.append("。这个时间窗内还有更早的摘要，只显示了最近的 ")
                    .append(window.items().size()).append(" 条，想看更早的请缩小天数范围");
        }
        return text.toString();
    }

    /**
     * 回话里用的群名：授权判定带回来的权威显示名 → 退回 ref。
     *
     * <p>退回 ref 而不是编一个（同 {@code ContactTimelineAssistantTools.fallbackName} 与
     * {@code ContactGroupService} 域的口径）：显示名可能为空（群没同步到名字），
     * 那时说「WECOM_GROUP:…」虽然难读，但至少是真的。
     */
    private static String displayName(String groupName, String reference) {
        return groupName == null || groupName.isBlank() ? reference : groupName.strip();
    }

    // ---------- 参数读取 ----------

    private static String requiredText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.strip();
        }
        throw new ToolExecutionException(ToolExecutionException.MISSING_ARGUMENT, "缺少必填参数：" + key);
    }

    /**
     * 可选整数参数。类型与上下界由 {@link ToolInputValidator} 在更前面判
     * （{@code integer} + {@code minimum} / {@code maximum}），所以这里只处理「没给」。
     */
    private static int optionalInteger(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
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
     * 整数参数。
     *
     * <p>{@code minimum} / {@code maximum} 必须与校验器同时支持才写得上 —— 它们
     * <b>曾经是静默失效的</b>（{@link ToolInputValidator} 只认类型与长度），
     * 也就是说一个写着 {@code maximum: 30} 的 schema 当时等于没有约束。
     * 现在两侧一致：声明即生效，越界会被拒在提问/调用路径，不会送到服务层。
     */
    private static Map<String, Object> integerSpec(String description, int minimum, int maximum) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "integer");
        field.put("description", description);
        field.put("minimum", minimum);
        field.put("maximum", maximum);
        return field;
    }

    private static Map<String, Object> referenceTo(String candidateSet, String description) {
        Map<String, Object> field = stringSpec(description, "maxLength", GROUP_REF_MAX_CHARS);
        field.put(ToolInputValidator.CANDIDATE_SET, candidateSet);
        return field;
    }

    /**
     * 免确认写动作的注解。
     *
     * <p>三个 hint 都不是随手写的，{@code AssistantActionPolicy} 会按它们判档：
     *
     * <ul>
     *   <li>{@code readOnlyHint=false} —— 它确实改了外部状态（企微里多一条消息）。</li>
     *   <li>{@code destructiveHint=false} —— 这一条是<b>进免确认档的必要条件</b>：
     *       策略类对未声明的注解按保守值（=破坏性）解释，漏写这一行会静默退回确认档，
     *       而不是报错。它的语义是「不会造成不可逆的变更」，推给自己一条消息符合。</li>
     *   <li>{@code idempotentHint=false} —— 重发一次就是真的多一条，不能写成 true。</li>
     * </ul>
     */
    private static McpSchema.ToolAnnotations pushAllowed() {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(false)
                .destructiveHint(false)
                .idempotentHint(false)
                .openWorldHint(true)
                .build();
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
