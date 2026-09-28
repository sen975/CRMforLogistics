package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnAssistantEnabled;
import com.crmforlogistics.messagecenter.entity.AssistantPendingActionEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.mapper.AssistantPendingActionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.service.assistant.mcp.AiTopicAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ContactMemoryAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ContactWriteAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ConversationAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.MessageSendAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.TodoAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolDefinition;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolResult;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.crmforlogistics.messagecenter.service.channel.OutboundMessageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 待确认动作：落库、确认、取消、过期。
 *
 * <h2>授权为什么必须落库</h2>
 * 这个对象携带的是<b>执行授权</b>。交给前端持有有两个具体问题：一是可被篡改
 * （改掉 {@code arguments.todoId} 就能让用户点的那次确认落到另一条待办上）；
 * 二是「已确认 / 已取消 / 已过期」没有可信的单一真源，双击确认会在两边各执行一次。
 * 落库之后，这些事情都有据可查。
 *
 * <h2>确认的顺序：先抢占，再校验，最后执行</h2>
 * <ol>
 *   <li>{@code markDecided(..., "CONFIRMED")} 用
 *       {@code where status = 'PENDING'} 原子抢占。影响 0 行说明已经被人处理过，
 *       直接 409 —— 这是「双击确认只执行一次」的实现方式，不靠前端去重。</li>
 *   <li><b>重新校验</b>（{@link AssistantDecisionParser#validateConfirmedCall}）。从生成确认卡片到
 *       用户点确认之间，这份参数可能已经不成立（工具契约变了、日期不再可解析），
 *       所以确认前必须再走一遍结构校验。</li>
 *   <li>执行，并把结论写进审计。</li>
 * </ol>
 *
 * <h2>确认为什么不比对候选清单（这里曾经写错过一次）</h2>
 * 最初这一步是重建上下文、用 {@code validateCall} 比对<b>本轮候选清单</b>，本意是
 * 「引用了一个已经消失的对象必须失败」。但候选清单是「最近能看到什么」的<b>窗口</b>
 * （会话 20 条、待办 70 条），既不是「对象是否存在」，也不是「我有没有权限」。
 * 而只读检索的全部价值恰恰是<b>突破这个窗口</b>：模型先 {@code conversation.search}
 * 拿到窗口外的 ref，再对它下写动作 —— 于是卡片正常显示、用户一点确认必然拿到
 * {@code ASSISTANT_STALE_REFERENCE}，「先检索、再对检索结果动手」端到端不可用。
 *
 * <p>现在只校验结构（{@link AssistantDecisionParser#validateConfirmedCall}），
 * 「对象还在不在、我还有没有权限」交给执行路径：{@code TodoItemService.require/delete/update}
 * 带 {@code where user_id}，{@code ConversationPreferenceService.authorize} 查
 * {@code findAccessibleById(userId)} —— 那本来就有，且是唯一权威。
 *
 * <p><b>单测为什么没抓到</b>：夹具的候选集小、目标都在窗口内，且确认用的就是同一份上下文，
 * 于是「窗口内的目标能确认」被当成了「确认能工作」。守卫现在放在两个地方：
 * 解析器层证明确认路径不看候选清单，循环测试层跨过「确认」这一步走完整条链。
 *
 * <p><b>抢占在校验之前的代价</b>：若校验失败，这条动作已被标记 CONFIRMED 而实际没执行。
 * 这个取舍是有意的 —— 反过来（先校验后抢占）在双击时会校验两次都通过、执行两次。
 * 相比之下「一次失败的确认消耗掉了这次授权」是可接受的：用户再问一次即可，
 * 而重复执行一个删除或改期是不可接受的。
 *
 * <h2>过期是惰性的</h2>
 * 不建后台清理任务，确认/取消时拿 {@code expires_at} 与注入的 {@code Clock} 比对，
 * 就地置 EXPIRED 并返回 410。理由与既有 `wecom_contact_events` 同口径：
 * 本期不引入第二套后台机制。用注入的 Clock 而不是数据库 {@code now()}，
 * 是为了让「过期」这件事可被单测精确构造。
 */
@Component
@ConditionalOnAssistantEnabled
public class AssistantPendingActionService {

    private static final Logger log = LoggerFactory.getLogger(AssistantPendingActionService.class);

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_CONFIRMED = "CONFIRMED";
    static final String STATUS_CANCELLED = "CANCELLED";
    static final String STATUS_EXPIRED = "EXPIRED";

    /**
     * 卡片正文的软上限。
     *
     * <p>列本身已是 {@code text}（V90），所以这个上限服务的不再是「列装不下」，而是
     * 「别把一段无界文本搬进 UI」—— 兜底分支（{@code default -> toolName + writeJson(arguments)}）
     * 会把整个参数对象序列化进去，而参数由模型生成，长度不受我们控制。
     *
     * <p>取 4000：一封正常长度的邮件正文放得下，同时远小于任何会拖垮渲染或让人读不完的量级。
     * 它不是列宽，改大改小不涉及迁移。
     */
    private static final int SUMMARY_MAX = 4000;

    /**
     * 卡片上联系人名的显示上限。
     *
     * <p>与 {@code SUMMARY_MAX} 的取值配套：有了它，「发送类卡片在最坏情况下也塞得进
     * {@link #SUMMARY_MAX}」这件事只依赖本类的常量与两个列宽
     * （{@code contacts.display_name varchar(100)} / {@code contact_identities.identity_value varchar(255)}，见 V1），
     * 而不再依赖用户给自己的联系人起了多长的名字。
     *
     * <p>取 100 是刻意的「不改语义的收紧」：列宽本身就是 100，所以这里不会真的截断任何
     * 已经存进库里的名字 —— 它只在名字来源将来变宽时才生效。
     */
    static final int CARD_NAME_MAX = 100;

    private final AssistantPendingActionMapper mapper;
    private final AssistantDecisionParser parser;
    private final ToolRegistry registry;
    private final AssistantAuditService audit;
    private final AssistantConfig config;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    /**
     * 只用于一件事：把「发送类」卡片上的<b>实际收件地址</b>填出来。
     *
     * <p>卡片是用户复核的唯一依据，而对外发送的收件地址是他最需要核对的那一项
     * （发错人收不回来）。地址不能从 {@code arguments} 里拿 —— 那里根本没有这个字段
     * （见 {@code MessageSendAssistantTools} 的「为什么没有 to 参数」），只能就地解析一次。
     *
     * <p>解析用的是与执行时<b>同一个</b> {@link OutboundMessageService#resolve}，
     * 所以卡片上显示的地址与实际发往的地址不是两段各自算出来的字符串。
     */
    private final OutboundMessageService outbound;
    /**
     * 只用于一件事：把 {@code contact.update_profile} 卡片上的<b>改前真值</b>（显示名/职务）查出来。
     *
     * <p>2026-09-23 的事故：模型把「改备注」错当成 update_profile 覆盖了渠道同步来的昵称，
     * 而旧卡片的「改前」一栏是「当前未知」—— 用户点确认时看不到自己即将覆盖掉什么。
     * 候选条目的 {@code name} 在显示名为空时会退回备注，拿它当「改前显示名」会给出<b>错的</b>值，
     * 所以这里直接查库（{@link ContactMapper#findByIdAndOwner}，归属判据与重算入口一致）。
     * 查不到（不是你名下的联系人 / 已删除 / ref 解析失败）就留 null —— 未知好过错值，见 profileChanges。
     */
    private final ContactMapper contacts;

    public AssistantPendingActionService(AssistantPendingActionMapper mapper,
                                         AssistantDecisionParser parser,
                                         ToolRegistry registry,
                                         AssistantAuditService audit,
                                         AssistantConfig config,
                                         ObjectMapper objectMapper,
                                         Clock clock,
                                         OutboundMessageService outbound,
                                         ContactMapper contacts) {
        this.mapper = mapper;
        this.parser = parser;
        this.registry = registry;
        this.audit = audit;
        this.config = config;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.outbound = outbound;
        this.contacts = contacts;
    }

    /**
     * 落一条待确认动作，返回给前端渲染确认卡片。
     *
     * <p>写库失败<b>抛出</b>（不像审计那样吞掉）：这一行是执行授权本身，
     * 落不下去就必须让这一轮失败，而不是回一句「请确认」却没有任何可确认的东西 ——
     * 用户点了确认会得到 404，而他并不知道发生了什么。
     */
    public AssistantTurnResult.Proposal create(UUID userId, UUID conversationId, ToolDefinition definition,
                                               Map<String, Object> arguments, AssistantContext context) {
        // userId 只是为了让「发送类」卡片能解析出收件地址（见 outbound 字段的注释）；
        // 其余分支用不到它，卡片本身上面不带身份。
        Card card = card(userId, definition.name(), arguments, context);

        AssistantPendingActionEntity entity = new AssistantPendingActionEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setConversationId(conversationId);
        entity.setToolName(definition.name());
        entity.setArgumentsJson(writeJson(arguments));
        entity.setSummary(card.summary());
        // 「变更前后」与 arguments 分别落库：前者是**当时给用户看的那一面**，
        // 用户按下确认时同意的是他看到的这份文字，事后要能复原 —— 包括「卡片当时有没有告诉他改前是什么」。
        entity.setChangesJson(writeChangesJson(card.changes()));
        entity.setStatus(STATUS_PENDING);
        entity.setExpiresAt(Instant.now(clock).plus(Duration.ofSeconds(config.pendingTtlSeconds())));
        mapper.insert(entity);

        return new AssistantTurnResult.Proposal(entity.getId(), definition.name(),
                card.summary(), card.changes(), arguments);
    }

    /** 确认并执行。 */
    public AssistantTurnResult confirm(UUID userId, UUID pendingActionId) {
        AssistantPendingActionEntity pending = mapper.findById(pendingActionId, userId);
        if (pending == null) {
            // 404 而不是 403：不泄露「这条 id 存在但不属于你」。
            throw new AssistantException(AssistantException.PENDING_NOT_FOUND, "这条待确认的操作不存在或已失效");
        }
        if (!STATUS_PENDING.equals(pending.getStatus())) {
            throw new AssistantException(AssistantException.PENDING_ALREADY_DECIDED, "这条操作已经处理过了");
        }
        if (isExpired(pending)) {
            expire(userId, pending);
            throw new AssistantException(AssistantException.PENDING_EXPIRED,
                    "这条操作已经超过有效期，请重新对我说一次");
        }
        if (mapper.markDecided(pendingActionId, userId, STATUS_CONFIRMED) != 1) {
            // 抢占失败：并在同一瞬间另一次确认把它拿走了。
            throw new AssistantException(AssistantException.PENDING_ALREADY_DECIDED, "这条操作已经处理过了");
        }

        Map<String, Object> arguments = readArguments(pending);
        // 只看结构，**不比对候选清单** —— 目标「还在不在、我有没有权限」由执行路径判定。
        // 理由见类注释「确认为什么不比对候选清单」。
        AssistantDecisionParser.Outcome validated =
                parser.validateConfirmedCall(pending.getToolName(), arguments);

        if (validated instanceof AssistantDecisionParser.Rejected rejected) {
            // 这份存下来的参数已经不成立（部署后工具契约变了、日期不再可解析）。**不执行**，
            // 并把原因记进审计。「目标已消失」不走这里：那是执行路径的事，会得到动作自己的错误码。
            log.info("assistant pending action {} confirmed but its arguments no longer hold: {}",
                    pendingActionId, rejected.reason());
            audit.record(new AssistantAuditService.Entry(userId, pending.getConversationId(), null,
                    "call", pending.getToolName(), arguments, STATUS_CONFIRMED, "REJECTED",
                    STALE_REFERENCE, null, null));
            return AssistantTurnResult.error(STALE_REFERENCE,
                    "这条操作现在已经不能执行了，请重新对我说一次");
        }

        AssistantDecisionParser.Call call = (AssistantDecisionParser.Call) validated;
        ToolResult result = registry.invoke(call.tool(), userId, call.arguments());

        audit.record(new AssistantAuditService.Entry(userId, pending.getConversationId(), null,
                "call", call.tool(), call.arguments(), STATUS_CONFIRMED,
                result.isError() ? "FAILED" : "EXECUTED", result.code(), null, null));

        return result.isError()
                ? AssistantTurnResult.error(result.code(), result.message())
                : AssistantTurnResult.executed(result.message());
    }

    /** 取消。已取消过的再取消一次视为成功（双击取消不该报错），但不重复写审计。 */
    public AssistantTurnResult cancel(UUID userId, UUID pendingActionId) {
        AssistantPendingActionEntity pending = mapper.findById(pendingActionId, userId);
        if (pending == null) {
            throw new AssistantException(AssistantException.PENDING_NOT_FOUND, "这条待确认的操作不存在或已失效");
        }
        if (STATUS_CANCELLED.equals(pending.getStatus())) {
            return AssistantTurnResult.answer("这条操作已经取消过了，未做任何改动");
        }
        if (!STATUS_PENDING.equals(pending.getStatus())) {
            throw new AssistantException(AssistantException.PENDING_ALREADY_DECIDED, "这条操作已经处理过了");
        }
        if (isExpired(pending)) {
            expire(userId, pending);
            throw new AssistantException(AssistantException.PENDING_EXPIRED,
                    "这条操作已经超过有效期，未做任何改动");
        }
        if (mapper.markDecided(pendingActionId, userId, STATUS_CANCELLED) != 1) {
            throw new AssistantException(AssistantException.PENDING_ALREADY_DECIDED, "这条操作已经处理过了");
        }
        audit.record(new AssistantAuditService.Entry(userId, pending.getConversationId(), null,
                "call", pending.getToolName(), readArguments(pending), STATUS_CANCELLED, "CANCELLED",
                null, null, null));
        return AssistantTurnResult.answer("已取消，未做任何改动");
    }

    // ---------- 摘要 ----------

    /**
     * 确认卡片的载荷：一句话摘要 + **结构化**的「变更前后」。
     *
     * <p>为什么要有结构化那一份：摘要是一句给人读的话，它无法被机器核对，也无法在
     * 「改前是什么」这件事上保持形状。改动类动作的卡片必须能同时显示两栏，
     * 否则用户只能判断「模型想改日期」，无法判断「它以为当前是哪天」——
     * 而后者才是他真正要核对的东西。
     */
    record Card(String summary, List<AssistantTurnResult.Proposal.Change> changes) {
        Card {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
    }

    /**
     * 卡片正文与变更说明。
     *
     * <p>摘要<b>必须含标题与日期</b> —— 这是歧义消解的唯一手段。改动类动作还要把「改成了什么」
     * 写进去：只显示「修改待办：「和张总确认报价」」，用户无法判断模型是不是把日期改成了
     * 他想要的那天，那张卡片就退化成了一个走过场的按钮。
     *
     * <p>长度截断放在这里统一做，而不是写进 text 列：列已是 {@code text}（V90），
     * 这个上限服务的是「别把一段无界文本搬进 UI」，不是「列装不下」。
     *
     * <p><b>为什么需要 {@code userId}</b>：只有「发送类」那两个分支用得着它 ——
     * 它们要显示<b>实际收件地址</b>，而地址既不在 {@code arguments}（那里根本没有这个字段），
     * 也不能靠模型给。其余分支忽略这个参数。为了让「发送类卡片一定显示得全」这条保证可算，
     * {@code MessageSendAssistantTools} 的正文上限是按这里的 {@link #SUMMARY_MAX} 反推的。
     */
    Card card(UUID userId, String toolName, Map<String, Object> arguments, AssistantContext context) {
        // 分支键一律走工具类上的常量，不写字符串字面量：写错的字面量不会报错，
        // 只会静默掉进 default 分支，把一个给人看的卡片变成一段 JSON —— 而卡片正是复核唯一的依据。
        Card card = switch (toolName) {
            case TodoAssistantTools.TOOL_CREATE -> new Card(
                    "新建待办「" + argument(arguments, "title") + "」 "
                            + renderWhen(argument(arguments, "date"), argument(arguments, "time")),
                    // 新建没有「改前」。硬造一条 before=null 的变更行只会给卡片添一行噪声。
                    List.of());
            case TodoAssistantTools.TOOL_COMPLETE -> new Card(
                    (isTrue(arguments.get("completed")) ? "标记完成：" : "标回未完成：")
                            + describeTarget(arguments, context),
                    List.of(new AssistantTurnResult.Proposal.Change("completed", "状态",
                            // 候选清单里不含「当前是否已完成」，所以这一栏拿不到证据。
                            // 留 null（前端显示「当前未知」）而不是推一个「未完成」出来 —— 猜的「改前」会被当成事实读。
                            null, isTrue(arguments.get("completed")) ? "已完成" : "未完成")));
            case TodoAssistantTools.TOOL_DELETE -> new Card("删除待办：" + describeTarget(arguments, context), List.of());
            case TodoAssistantTools.TOOL_UPDATE -> new Card(
                    "修改待办：" + describeTarget(arguments, context) + " → " + renderChanges(arguments),
                    updateChanges(arguments, context));
            case ConversationAssistantTools.TOOL_PIN -> new Card(
                    (isTrue(arguments.get("pinned")) ? "置顶会话：" : "取消置顶：")
                            + describeConversation(arguments, context),
                    pinChanges(arguments, context));
            // ---------- 联系人域 ----------
            case ContactWriteAssistantTools.TOOL_UPDATE_REMARK -> new Card(
                    "修改备注：" + describeContact(arguments, context) + " → " + renderRemark(arguments),
                    List.of(new AssistantTurnResult.Proposal.Change("remark", "备注",
                            contactRemark(arguments, context), remarkAfter(arguments))));
            case ContactWriteAssistantTools.TOOL_UPDATE_PROFILE -> new Card(
                    "修改资料：" + describeContact(arguments, context) + " → " + renderProfileChanges(arguments),
                    profileChanges(arguments, userId));
            case ContactWriteAssistantTools.TOOL_SET_TAGS -> new Card(
                    "替换标签：" + describeContact(arguments, context) + " → " + renderTags(arguments),
                    // 「改前」拿不到：候选里没有标签（那是 contact.brief 的字段）。
                    // 留 null 而不是把新标签当旧标签显示 —— 后者会让卡片看起来像「标签没变」。
                    List.of(new AssistantTurnResult.Proposal.Change("tags", "标签", null, renderTags(arguments))));
            case ContactWriteAssistantTools.TOOL_MARK_READ -> new Card(
                    "标记已读：与" + describeContact(arguments, context) + "的全部消息", List.of());
            // ---------- AI 话题域 ----------
            case AiTopicAssistantTools.TOOL_TOPICS_RETRY -> new Card(
                    "重新生成 AI 话题：" + describeContact(arguments, context), List.of());
            case AiTopicAssistantTools.TOOL_TOPIC_UPDATE -> new Card(
                    "修改话题标题：" + describeTopic(arguments, context)
                            + " → 「" + argument(arguments, "title") + "」",
                    List.of(new AssistantTurnResult.Proposal.Change("title", "标题",
                            topicTitle(arguments, context), blankToNull(argument(arguments, "title")))));
            // 合并的"改后"是一个未知值 —— 新标题由 ai-topic 的 LLM 网关生成，调用方与这里都不知道。
            // 所以刻意<b>不</b>造一行 before→after：那张卡片的价值全在"用户能核对"，
            // 而凭空写一个「改后」会让人拿一个编出来的标题去核对。来源逐条列在摘要里。
            case AiTopicAssistantTools.TOOL_TOPICS_MERGE -> new Card(
                    "合并 AI 话题：把 " + describeTopics(arguments, context)
                            + " 合并成一条（新标题与摘要由系统重新归纳，原来这几条会消失、不可撤销）",
                    List.of());
            // ---------- AI 记忆域 ----------
            //
            // 这张卡片只有一个作用：让用户在按下确认前知道「这会重新生成画像与标签」。
            // 后半句「由后台重算」不是客套 —— 重算受处理窗口约束，点了确认之后画像<b>不会立刻变</b>，
            // 不写出来，用户会以为没生效并反复点。
            case ContactMemoryAssistantTools.TOOL_REFRESH_MEMORY -> new Card(
                    "重新生成 AI 画像与标签：" + describeContact(arguments, context)
                            + "（由后台重算，提交后不会立刻生效）",
                    List.of());
            // ---------- 对外发送（不可撤回） ----------
            //
            // 这两张卡片的规格与其余卡片不同：其他地方用户复核的是「改哪个对象、改成什么」，
            // 这里复核的是**全文**。所以正文一字不改地放进来（不摘要、不省略），
            // 收件地址也必须是解析出来的实际值，而不是「发给周明」这种需要用户自己脑补的说法。
            //
            // changes 一律留空：这两个动作没有「改前」，而硬造一条 before=null 的行会被渲染成
            // 「当前未知 → 地址」，让一张本该是「发什么给谁」的卡片读起来像一次修改
            // （同 todo.create 不留变更行的理由）。
            case MessageSendAssistantTools.TOOL_SEND_EMAIL -> new Card(
                    "发送邮件给" + describeOutboundTarget(userId, arguments, context,
                            OutboundMessageService.CHANNEL_EMAIL)
                            + "，主题「" + renderSubject(arguments) + "」，正文：\n"
                            + argument(arguments, "body"),
                    List.of());
            case MessageSendAssistantTools.TOOL_SEND_CHATAPP -> new Card(
                    "给" + describeOutboundTarget(userId, arguments, context,
                            OutboundMessageService.CHANNEL_CHATAPP)
                            + "发一条 WhatsApp 消息：\n" + argument(arguments, "text"),
                    List.of());
            default -> new Card(toolName + " " + writeJson(arguments), List.of());
        };
        // 卡片是给人核对用的 ⇒ 裸截断（截断标记对人是噪音）；代理对仍要防：
        // 摘要里可能带着客户发的 emoji 或生僻字。
        String summary = card.summary();
        String truncated = Texts.truncate(summary, SUMMARY_MAX);
        return summary.equals(truncated) ? card : new Card(truncated, card.changes());
    }

    /** 只要摘要文本。等价于 {@code card(userId, ...).summary()}；「摘要怎么写」本身值得单独钉住。 */
    String summarise(UUID userId, String toolName, Map<String, Object> arguments, AssistantContext context) {
        return card(userId, toolName, arguments, context).summary();
    }

    // ---------- 对外发送的卡片素材 ----------

    /**
     * 发送卡片的目标描述：<b>名字（来自候选清单）+ 实际收件地址（服务端解析）</b>。
     *
     * <h2>为什么地址必须显示，而不是只写联系人名字</h2>
     * 「发给老王」对用户来说不是一条可核对的信息：老王可能有两个邮箱，系统里恒定的只有
     * 「按主地址取第一条」这条规则（见 {@code OutboundMessageService}）。卡片如果只写名字，
     * 那次确认实际是在对「系统认为的老王地址」点头 —— 而用户根本没看到它。
     * 发送是<b>不可撤回</b>的，这一项的复核价值最高。
     *
     * <h2>解析不到地址时必须说出来</h2>
     * 这个名字在系统里可能一个可用地址都没有（档案没填、身份被删）。此时<b>不能</b>装作正常
     * 显示一张「发送邮件给「周明」」的卡片：用户会点确认，然后拿到一个失败。
     * 把「现在发不出去」写在卡片上，他就能改去补档案或者换个人。
     *
     * <h2>为什么名字要限长</h2>
     * 卡片总长有上限（{@link #SUMMARY_MAX}），而地址与正文才是复核对象。名字是标签，
     * 与它们抢预算时让位给后者 —— 否则一个超长名字会把正文挤掉，而正文正是这封信本身。
     */
    private String describeOutboundTarget(UUID userId, Map<String, Object> arguments,
                                          AssistantContext context, String channelType) {
        String name = Texts.truncate(contactName(arguments, context), CARD_NAME_MAX);
        String label = OutboundMessageService.channelLabel(channelType);
        OutboundMessageService.Recipient recipient =
                outbound.resolve(userId, ContactCandidates.targetOf(argument(arguments, "contactRef")), channelType);
        if (recipient == null) {
            return name + "（注意：系统里找不到他可用于发送的" + label + "地址，现在发不出去）";
        }
        return name + "（" + label + " " + recipient.address() + "）";
    }

    /**
     * 目标联系人的名字，做成「」包起来的形状（与 {@link #describeContact} 一致）。
     *
     * <p>刻意不复用 {@link #describeContact}：那个还会带上备注，而备注列可以很长
     * （参见 V1 的 {@code contacts.remark}），会把预算从正文那里抢走。发送卡片上辨认「是哪个老王」
     * 靠的是下文里的地址，不是备注。
     */
    private static String contactName(Map<String, Object> arguments, AssistantContext context) {
        ContactCandidates.Item item = contactItem(arguments, context);
        return item == null ? "联系人 " + argument(arguments, "contactRef") : "「" + item.name() + "」";
    }

    /** 主题的渲染。没给主题是常态（邮件主题可空），必须写出来而不是留一个空括号。 */
    private static String renderSubject(Map<String, Object> arguments) {
        String subject = blankToNull(argument(arguments, "subject"));
        return subject == null ? "（无主题）" : subject;
    }

    /**
     * {@code todo.update} 的「改前 → 改后」。每个会被改动的字段一条。
     *
     * <p>{@code before} 全部取自本轮的候选清单 —— 那是**服务端自己看到的当前值**，
     * 而不是模型转述的。候选里没有的字段（如备注）留 null，理由见
     * {@link AssistantTurnResult.Proposal.Change}。
     */
    private static List<AssistantTurnResult.Proposal.Change> updateChanges(Map<String, Object> arguments,
                                                                          AssistantContext context) {
        AssistantContext.CandidateTodo candidate = candidateTodo(arguments, context);
        List<AssistantTurnResult.Proposal.Change> changes = new java.util.ArrayList<>();
        if (arguments.containsKey("title")) {
            changes.add(new AssistantTurnResult.Proposal.Change("title", "内容",
                    candidate == null ? null : candidate.title(), argument(arguments, "title")));
        }
        if (arguments.containsKey("date") || arguments.containsKey("time")) {
            // 日期与时间合成一条：对用户来说它们是同一件事（「什么时候」），
            // 拆成两条会让卡片出现「日期改动 09-23 → 空」这种读不懂的行。
            String before = candidate == null ? null : renderWhen(candidate.date(), candidate.time());
            changes.add(new AssistantTurnResult.Proposal.Change("when", "时间", blankToNull(before),
                    renderWhen(argument(arguments, "date"), argument(arguments, "time"))));
        }
        if (arguments.containsKey("note")) {
            // 候选清单不含备注 → 「改前」无从得知，留 null。
            changes.add(new AssistantTurnResult.Proposal.Change("note", "备注", null,
                    argument(arguments, "note")));
        }
        return changes;
    }

    /**
     * {@code conversation.pin} 的「改前 → 改后」。
     *
     * <p>候选条目自带 {@code pinned}，所以这一栏的「改前」是**有证据的**（与备注那类相反）。
     * 目标不在候选里时留 null —— 那种情况下卡片上连名称都退回了 id，再编一个置顶状态
     * 只会让用户以为模型看的和他是同一条会话。
     */
    private static List<AssistantTurnResult.Proposal.Change> pinChanges(Map<String, Object> arguments,
                                                                       AssistantContext context) {
        boolean pinned = isTrue(arguments.get("pinned"));
        ConversationCandidates.Item item = conversationItem(arguments, context);
        String before = item == null ? null : (item.pinned() ? "已置顶" : "未置顶");
        return List.of(new AssistantTurnResult.Proposal.Change("pinned", "置顶",
                before, pinned ? "已置顶" : "未置顶"));
    }

    // ---------- 联系人域与话题域的卡片素材 ----------

    /**
     * 目标联系人：名字取自本轮候选清单，取不到就退回 ref。
     *
     * <p>退回而不是编一个，理由同别处：卡片是用户核对「模型指的是不是我想的那个人」的唯一依据，
     * 一个编出来的名字会让复核失效。联系人通常都在候选里（解析器已强制 ref 命中候选），
     * 退回分支只在候选集缺席（如某些单测夹具）时走到。
     */
    private static String describeContact(Map<String, Object> arguments, AssistantContext context) {
        ContactCandidates.Item item = contactItem(arguments, context);
        if (item == null) {
            return "联系人 " + argument(arguments, "contactRef");
        }
        String remark = blankToNull(item.remark());
        // 备注一起显示不是冗余：同名的联系人很常见，而卡片上辨认「是哪个老王」靠的就是备注。
        return remark == null ? "「" + item.name() + "」" : "「" + item.name() + "」（备注 " + remark + "）";
    }

    private static ContactCandidates.Item contactItem(Map<String, Object> arguments, AssistantContext context) {
        CandidateSet set = context == null ? null : context.candidateSet(ContactCandidates.NAME);
        return set instanceof ContactCandidates contacts
                ? contacts.find(argument(arguments, "contactRef"))
                : null;
    }

    /** 备注的「改前」。候选条目带 remark，所以这一栏是**有证据的**（与职务、标签那两栏相反）。 */
    private static String contactRemark(Map<String, Object> arguments, AssistantContext context) {
        ContactCandidates.Item item = contactItem(arguments, context);
        return item == null ? null : blankToNull(item.remark());
    }

    private static String renderRemark(Map<String, Object> arguments) {
        String remark = blankToNull(argument(arguments, "remark"));
        // 清除是「改成了空」，与「没给这个参数」不是一回事，卡片上必须写出来，
        // 否则用户在卡片上只看到一个空箭头，无法判断点是"清掉"还是"模型漏传了"。
        return remark == null ? "（清除备注）" : "「" + remark + "」";
    }

    private static String remarkAfter(Map<String, Object> arguments) {
        return blankToNull(argument(arguments, "remark")) == null ? "（清除）" : argument(arguments, "remark");
    }

    /**
     * {@code contact.update_profile} 的「改前 → 改后」。
     *
     * <p>「改前」从库里取真值（{@link ContactMapper#findByIdAndOwner}，归属判据与重算入口一致）。
     * 2026-09-23 之前这一栏一律留 null，理由是「候选条目的 name 会退回备注，当不得显示名」；
     * 现在有了更好的答案 —— 不依赖候选，直接问库。查不到（目标不是调用方名下的联系人、
     * 已删除、ref 解析失败）仍然留 null：<b>未知好过错值</b>，错的「改前」会被用户当成事实去核对。
     */
    private List<AssistantTurnResult.Proposal.Change> profileChanges(Map<String, Object> arguments,
                                                                     UUID userId) {
        ContactEntity current = currentContact(arguments, userId);
        List<AssistantTurnResult.Proposal.Change> changes = new java.util.ArrayList<>();
        if (arguments.containsKey("displayName")) {
            changes.add(new AssistantTurnResult.Proposal.Change("displayName", "显示名",
                    current == null ? null : blankToNull(current.getDisplayName()),
                    argument(arguments, "displayName")));
        }
        if (arguments.containsKey("roleTitle")) {
            String role = argument(arguments, "roleTitle");
            changes.add(new AssistantTurnResult.Proposal.Change("roleTitle", "职务",
                    current == null ? null : blankToNull(current.getRoleTitle()),
                    role.isBlank() ? "（清除）" : role));
        }
        return changes;
    }

    /** 目标联系人的当前行。ref 解析失败、不是调用方名下、已删除，都归并为「查不到」。 */
    private ContactEntity currentContact(Map<String, Object> arguments, UUID userId) {
        UUID contactId = ContactCandidates.targetOf(argument(arguments, "contactRef"));
        if (contactId == null) {
            return null;
        }
        return contacts.findByIdAndOwner(contactId, userId).orElse(null);
    }

    private static String renderProfileChanges(Map<String, Object> arguments) {
        List<String> parts = new java.util.ArrayList<>();
        if (arguments.containsKey("displayName")) {
            parts.add("显示名改为「" + argument(arguments, "displayName") + "」");
        }
        if (arguments.containsKey("roleTitle")) {
            String role = argument(arguments, "roleTitle");
            parts.add(role.isBlank() ? "清除职务" : "职务改为「" + role + "」");
        }
        return parts.isEmpty() ? "（没有要改的字段）" : String.join("、", parts);
    }

    /**
     * 标签清单的渲染。
     *
     * <p>空数组要显式写成「清空全部标签」而不是渲染成空串：这个工具是<b>整体替换</b>，
     * 而「传了空数组」与「模型漏传了 tags」在卡片上看不出区别的话，
     * 用户会对着一片空白点下确认，然后丢掉这个人的全部标签。
     */
    private static String renderTags(Map<String, Object> arguments) {
        Object raw = arguments.get("tags");
        if (!(raw instanceof List<?> tags)) {
            return "（没有给出标签）";
        }
        List<String> names = new java.util.ArrayList<>();
        for (Object tag : tags) {
            if (tag instanceof String name && !name.isBlank()) {
                names.add(name.strip());
            }
        }
        return names.isEmpty() ? "（清空全部标签）" : String.join("、", names);
    }

    private static TopicCandidates.Item topicItem(Map<String, Object> arguments, AssistantContext context) {
        return topicItemByRef(argument(arguments, "topicRef"), context);
    }

    /**
     * 按 ref 在 topic 候选窗口里找标题。
     *
     * <p>拆出按 ref 的入口是因为「合并」那张卡片要逐条查一组话题（参数是 {@code topicRefs}），
     * 而候选窗口是它们标题的<b>唯一证据来源</b>。
     */
    private static TopicCandidates.Item topicItemByRef(String reference, AssistantContext context) {
        CandidateSet set = context == null ? null : context.candidateSet(TopicCandidates.NAME);
        return set instanceof TopicCandidates topics ? topics.find(reference) : null;
    }

    /**
     * 一组话题的「合并来源」描述。
     *
     * <p>标题取不到就退回 ref：与 {@link #describeTopic} 同一条理由 ——
     * 候选是「这条 ref 是什么」的唯一证据，凭空补一个标题比显示 id 更糟。
     * 每条都列出来也不是啰嗦：这是<b>不可撤销</b>动作，用户要点确认时最需要看清的就是"哪几条会消失"。
     */
    private static String describeTopics(Map<String, Object> arguments, AssistantContext context) {
        Object raw = arguments.get("topicRefs");
        if (!(raw instanceof List<?> references) || references.isEmpty()) {
            return "（没有给出话题）";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (Object reference : references) {
            String value = String.valueOf(reference);
            TopicCandidates.Item item = topicItemByRef(value, context);
            parts.add(item == null ? value : "「" + item.title() + "」");
        }
        return String.join("、", parts);
    }

    private static String describeTopic(Map<String, Object> arguments, AssistantContext context) {
        TopicCandidates.Item item = topicItem(arguments, context);
        return item == null ? "话题 " + argument(arguments, "topicRef") : "「" + item.title() + "」";
    }

    /** 话题标题的「改前」。来自 topic 候选条目，所以是有证据的。 */
    private static String topicTitle(Map<String, Object> arguments, AssistantContext context) {
        TopicCandidates.Item item = topicItem(arguments, context);
        return item == null ? null : item.title();
    }

    /** 目标待办：标题 + 日期（+ 时间）。标题取自本轮候选清单，取不到就退回 id 以免摘要谎报。 */
    private static String describeTarget(Map<String, Object> arguments, AssistantContext context) {
        String todoId = argument(arguments, "todoId");
        AssistantContext.CandidateTodo candidate = candidateTodo(arguments, context);
        if (candidate == null) {
            return "待办 " + todoId;
        }
        return "「" + candidate.title() + "」 " + renderWhen(candidate.date(), candidate.time());
    }

    /**
     * 目标会话：名称 + 类型（+ 渠道）。名称同样取自本轮候选，取不到就退回候选 id 以免摘要谎报。
     *
     * <p>渠道要显示出来不是装饰：同一个联系人可能同时有微信与企业微信两条会话，
     * 只写名字的卡片会让用户无法判断模型指的是哪一条 —— 而这类写动作的价值全在「人复核」上。
     */
    private static String describeConversation(Map<String, Object> arguments, AssistantContext context) {
        ConversationCandidates.Item item = conversationItem(arguments, context);
        if (item == null) {
            return "会话 " + argument(arguments, "conversationRef");
        }
        String channels = item.channels().isEmpty() ? "" : "，渠道 " + String.join("/", item.channels());
        return "「" + item.name() + "」（" + ConversationCandidates.typeLabel(item.type())
                + channels + "）";
    }

    /** 摘要与变更说明共用的目标解析：拿不到就返回 {@code null}，由调用方决定怎么退。 */
    private static AssistantContext.CandidateTodo candidateTodo(Map<String, Object> arguments,
                                                                AssistantContext context) {
        return context == null ? null : context.candidate(argument(arguments, "todoId"));
    }

    private static ConversationCandidates.Item conversationItem(Map<String, Object> arguments,
                                                               AssistantContext context) {
        CandidateSet set = context == null ? null : context.candidateSet(ConversationCandidates.NAME);
        return set instanceof ConversationCandidates conversations
                ? conversations.find(argument(arguments, "conversationRef"))
                : null;
    }

    /** 空串与 null 在卡片上是两回事：前者会被渲染成一个空格，后者才是「不知道」。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String renderChanges(Map<String, Object> arguments) {
        List<String> changes = new java.util.ArrayList<>();
        if (arguments.containsKey("title")) changes.add("内容改为「" + argument(arguments, "title") + "」");
        if (arguments.containsKey("date") || arguments.containsKey("time")) {
            changes.add("时间改为 " + renderWhen(argument(arguments, "date"), argument(arguments, "time")));
        }
        if (arguments.containsKey("note")) changes.add("备注改为「" + argument(arguments, "note") + "」");
        return changes.isEmpty() ? "（没有要改的字段）" : String.join("、", changes);
    }

    /**
     * 「日期 时间」的渲染。
     *
     * <p>空串与 null 一视同仁地当作「没给」：{@link #argument} 取不到值时给的是**空串**，
     * 于是 {@code "2026-09-24" + " " + ""} 会得到带尾随空格的日期。这在摘要里肉眼看不出来，
     * 一旦结构化进卡片「→」的右侧，就是一个可见的脏值。
     */
    private static String renderWhen(String date, String time) {
        String day = blankToNull(date);
        String at = blankToNull(time);
        if (day == null) return at == null ? "" : at;
        return at == null ? day : day + " " + at;
    }

    private static String argument(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value);
    }

    // ---------- 内部 ----------

    private boolean isExpired(AssistantPendingActionEntity pending) {
        return pending.getExpiresAt() != null && pending.getExpiresAt().isBefore(Instant.now(clock));
    }

    private void expire(UUID userId, AssistantPendingActionEntity pending) {
        if (mapper.markDecided(pending.getId(), userId, STATUS_EXPIRED) == 1) {
            audit.record(new AssistantAuditService.Entry(userId, pending.getConversationId(), null,
                    "call", pending.getToolName(), readArguments(pending), null, "EXPIRED",
                    AssistantException.PENDING_EXPIRED, null, null));
        }
    }

    private Map<String, Object> readArguments(AssistantPendingActionEntity pending) {
        String json = pending.getArgumentsJson();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() { });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            // 存储的 JSON 坏了是服务端问题，不是用户输入问题：抛出去变 500，而不是假装参数是空的 ——
            // 空参数会让「一个字段都没给」这类校验错误盖住真正的故障。
            throw new IllegalStateException("待确认动作 " + pending.getId() + " 的参数无法解析", e);
        }
    }

    private String writeJson(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(arguments));
        } catch (Exception e) {
            throw new IllegalStateException("待确认动作的参数无法序列化", e);
        }
    }

    /**
     * 变更说明序列化。**没有变更就写 SQL NULL**（返回 {@code null}），不写 {@code '[]'}：
     * 「这个动作没有『改前』可言」（新建、删除）与「有这个概念但这次为空」在排障时
     * 问的是不同的问题 —— 与审计那边对空参数的取舍同一口径。
     *
     * <p>失败**抛出**（与 {@link #writeJson} 一致）：这份内容会出现在用户按下确认时看到的那张卡片上，
     * 「卡片少显示了变更」比「这一轮直接失败」严重得多。
     */
    private String writeChangesJson(List<AssistantTurnResult.Proposal.Change> changes) {
        if (changes == null || changes.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(changes);
        } catch (Exception e) {
            throw new IllegalStateException("待确认动作的变更说明无法序列化", e);
        }
    }

    /**
     * 确认时发现这份参数已经不成立（部署后工具契约变了、日期不可解析）。
     *
     * <p><b>名字里的「引用」现在只指参数本身</b>：目标对象不存在<b>不</b>再走这个码 ——
     * 那由执行路径发现，返回动作自己的错误码（如 {@code TODO_NOT_FOUND}），
     * 对用户的指引更准（「待办不在了」而不是笼统的「引用失效」）。
     * 常量名与字符串值都保持不变：前端按 {@code kind} 渲染、只透传 message，改值没有收益，
     * 而改名会牵动一个对不上就静默失效的字符串。
     *
     * <p>这是 {@code kind=ERROR} 结果里的 {@code errorCode}，与 {@link AssistantException} 的
     * 那些码不同族：那些会变成 HTTP 状态码，这个是在 200 的响应体里告诉前端「这次确认没落地」。
     * 放在这里而不是塞进 {@code AssistantException}，是为了不让「异常码」与「结果码」混成一个集合 ——
     * 一旦混起来，早晚会有人为结果码去写一个 HTTP 映射。
     */
    public static final String STALE_REFERENCE = "ASSISTANT_STALE_REFERENCE";
}
