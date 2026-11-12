package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnAssistantEnabled;
import com.crmforlogistics.messagecenter.entity.AssistantConversationMessageEntity;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolDefinition;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一轮对话的编排：**选定历史** → 上下文 → 模型 → 解析 → 策略 → 执行或待确认 → 审计；只读动作可以循环。
 *
 * <h2>职责边界</h2>
 * 这个类<b>不</b>解析模型输出（交给 {@link AssistantDecisionParser}）、
 * <b>不</b>判断能不能直接执行（交给 {@link AssistantActionPolicy}）、
 * <b>不</b>实现工具（交给注册表）、
 * <b>不</b>解析线上请求的形状（{@code history} 的小写 role 字符串在控制器就归一成
 * {@link AssistantMessage} 了）。它只负责把这几步按正确顺序串起来，
 * 并在每一步之间做那个「谁来决定」的裁决。
 *
 * <h2>一条不可动摇的规则：模型永远不能指定身份</h2>
 * {@code userId} 由调用方（控制器，经 {@code SecurityUtil}）解析后传入，一路作为参数往下递，
 * <b>从不</b>来自请求体或模型输出。工具 schema 里也没有任何身份字段，
 * 因此模型连「表达一个身份」的入口都没有。
 *
 * <h2>模型看到哪一份历史，由这里决定（不是由调用方）</h2>
 * 会话消息本来就落在库里（{@code assistant_conversation_messages}），所以服务端的记录才是真相，
 * 调用方带来的历史只是**声明**：刷新后前端 items 为空、旧版本前端只带 8 条、有人改了前端 ——
 * 服务端都无从察觉，只会看到一个比真实更短的对话，而模型照样基于它自信作答。
 *
 * <p>这条规则（有会话号且库里有记录 ⇒ 以库为准）此前装在控制器里。控制器一层的规则
 * <b>没有类型与测试的保护</b>：第二个入口（语音端点、定时触发、内部调用）只要忘了先读库，
 * 就会<b>静默</b>退回「调用方说了算」，而所有既有用例照样绿。收进这里之后，
 * {@link #respond} 成了「跑一轮」的唯一入口，这条规则无处可跳 —— 调用方没有表达
 * 「请用我给的这份」的方式，它在库里没有记录时才生效。
 *
 * <h2>裁剪与选源必须同处发生，顺序不可颠倒</h2>
 * 先定下「哪一份历史」，再谈「留几条」（{@link AssistantRequestGuard}）。
 * 颠倒过来就会报出一个错的数字：告诉用户「丢了 3 条」，说的却是那份<b>没被采用</b>的历史。
 *
 * <h2>循环只对只读动作开放（这是本轮改造的安全闸门）</h2>
 * <pre>
 *   每轮：解析 → policy.decide
 *     ├─ READ    → 执行、把结果作为 observation 回灌、**再问一轮**（受 max-read-turns 限制）
 *     ├─ AUTO    → 执行 → 本轮结束
 *     ├─ CONFIRM → 落待确认 → 本轮结束
 *     └─ 其余（ask / reply / rejected）→ 本轮结束
 * </pre>
 *
 * <p>四条推论，每一条都对应一个具体的攻击面（见实施文档 §4）：
 *
 * <ol>
 *   <li><b>每轮都重新过 policy</b>，绝不复用上一轮的判定。第一轮「帮我看看张总的近况」是合法的只读，
 *       第二轮「顺便把他的备注改掉」是写 —— 若第二轮不再判，这就是绕过确认。</li>
 *   <li><b>写动作一旦出现即终止本轮循环</b>：不存在「执行完再继续」的自动续跑，
 *       因此一轮请求里至多一个写动作。</li>
 *   <li><b>每轮各自写一行审计</b>，因此多轮轨迹在库里可复原（一行一个 turn）。</li>
 *   <li><b>只读工具失败即终止并发诚实错误</b>（见下）。</li>
 * </ol>
 *
 * <h2>只读工具的失败为什么不回灌给模型</h2>
 * 原始设计是「失败也作为 observation 回灌，由模型向用户说明」。实现时改成了<b>直接终止</b>，
 * 理由是一条无法用提示词兜住的路径：回灌之后，模型<b>可以</b>无视那个错误、换条路继续，
 * 甚至直接编一段「我查到了…」——那时用户在界面上看到的是一个看起来正常的回答，
 * 而检索从未成功。要让它诚实，唯一的办法是不给它说错话的机会。
 *
 * <p>代价写清楚：模型失去了「换一个检索词再试一次」的能力，用户需要重新提问。
 * 这个代价是可接受的 —— 只读工具失败在本系统里意味着服务端故障或参数错误，两种都不是
 * 「换个说法就能好」的情况。
 *
 * <h2>重试只有一次，且只针对「信封没写对」</h2>
 * 输出不是一个 JSON 对象、或 {@code decision} 缺失/非法时，把校验错误回灌给模型再问一次；
 * 其余失败一律当场作废。理由见 {@link AssistantDecisionParser} 的类注释 ——
 * 进入 {@code call} 分支之后的失败不重试，是因为重试会给一个被拒的诉求第二次机会去
 * 改写成某个被允许的工具。
 *
 * <p>引入循环之后追加一条：<b>这次重试只在第一轮</b>（{@code round == 0}）。只读轮之后模型
 * 手里已经有 observation，此时再坏了信封，再问一次只会白白多一轮成本 —— 而每一轮都是一次
 * 新的决策机会，不该为了「把信封写对」而无偿赠送。
 */
@Component
@ConditionalOnAssistantEnabled
public class AssistantConversationService {

    private static final Logger log = LoggerFactory.getLogger(AssistantConversationService.class);

    /**
     * 只读轮次用尽时的结果码。
     *
     * <p>与 {@code ASSISTANT_STALE_REFERENCE} 同族：它是 200 响应体里的 {@code errorCode}，
     * 不是 HTTP 状态码。刻意不新增 {@code Kind}：前端按 {@code kind} 分支渲染，加一个终态
     * 就要同步改前端，而「未完成」与「失败」对用户是同一件事 —— 都需要他换个说法再来一次。
     */
    public static final String READ_TURNS_EXHAUSTED = "ASSISTANT_READ_TURNS_EXHAUSTED";

    private final AssistantContextBuilder contextBuilder;
    private final AssistantPromptBuilder promptBuilder;
    private final AssistantModelClient modelClient;
    private final AssistantDecisionParser parser;
    private final AssistantActionPolicy policy;
    private final AssistantPendingActionService pendingActions;
    private final AssistantAuditService audit;
    private final AssistantConversationLogService conversationLog;
    private final AssistantRequestGuard guard;
    private final ToolRegistry registry;
    private final AssistantConfig config;
    private final AssistantConversationContextService conversationContext;
    private final AssistantContactCandidateWindowStore contactWindows;
    private final TemplateMediaProvider mediaProvider;
    private final AssistantConversationLifecycleService lifecycle;

    @Autowired
    public AssistantConversationService(AssistantContextBuilder contextBuilder,
                                        AssistantPromptBuilder promptBuilder,
                                        AssistantModelClient modelClient,
                                        AssistantDecisionParser parser,
                                        AssistantActionPolicy policy,
                                        AssistantPendingActionService pendingActions,
                                        AssistantAuditService audit,
                                        AssistantConversationLogService conversationLog,
                                        AssistantRequestGuard guard,
                                        ToolRegistry registry,
                                        AssistantConfig config,
                                        AssistantConversationContextService conversationContext,
                                        AssistantContactCandidateWindowStore contactWindows,
                                        TemplateMediaProvider mediaProvider,
                                        AssistantConversationLifecycleService lifecycle) {
        this.contextBuilder = contextBuilder;
        this.promptBuilder = promptBuilder;
        this.modelClient = modelClient;
        this.parser = parser;
        this.policy = policy;
        this.pendingActions = pendingActions;
        this.audit = audit;
        this.conversationLog = conversationLog;
        this.guard = guard;
        this.registry = registry;
        this.config = config;
        this.conversationContext = conversationContext;
        this.contactWindows = contactWindows;
        this.mediaProvider = mediaProvider;
        this.lifecycle = lifecycle;
    }

    /** Constructor for context-compaction tests without a persistent contact window. */
    public AssistantConversationService(AssistantContextBuilder contextBuilder,
                                        AssistantPromptBuilder promptBuilder,
                                        AssistantModelClient modelClient,
                                        AssistantDecisionParser parser,
                                        AssistantActionPolicy policy,
                                        AssistantPendingActionService pendingActions,
                                        AssistantAuditService audit,
                                        AssistantConversationLogService conversationLog,
                                        AssistantRequestGuard guard,
                                        ToolRegistry registry,
                                        AssistantConfig config,
                                        AssistantConversationContextService conversationContext) {
        this(contextBuilder, promptBuilder, modelClient, parser, policy, pendingActions, audit,
                conversationLog, guard, registry, config, conversationContext, null, null, null);
    }

    /** Constructor retained for isolated orchestration tests that exercise the pre-compaction path. */
    public AssistantConversationService(AssistantContextBuilder contextBuilder,
                                        AssistantPromptBuilder promptBuilder,
                                        AssistantModelClient modelClient,
                                        AssistantDecisionParser parser,
                                        AssistantActionPolicy policy,
                                        AssistantPendingActionService pendingActions,
                                        AssistantAuditService audit,
                                        AssistantConversationLogService conversationLog,
                                        AssistantRequestGuard guard,
                                        ToolRegistry registry,
                                        AssistantConfig config) {
        this(contextBuilder, promptBuilder, modelClient, parser, policy, pendingActions, audit,
                conversationLog, guard, registry, config, null, null, null, null);
    }

    /** Compatibility constructor for isolated tests that provide media candidates but no lifecycle. */
    public AssistantConversationService(AssistantContextBuilder contextBuilder,
                                        AssistantPromptBuilder promptBuilder,
                                        AssistantModelClient modelClient,
                                        AssistantDecisionParser parser,
                                        AssistantActionPolicy policy,
                                        AssistantPendingActionService pendingActions,
                                        AssistantAuditService audit,
                                        AssistantConversationLogService conversationLog,
                                        AssistantRequestGuard guard,
                                        ToolRegistry registry,
                                        AssistantConfig config,
                                        AssistantConversationContextService conversationContext,
                                        AssistantContactCandidateWindowStore contactWindows,
                                        TemplateMediaProvider mediaProvider) {
        this(contextBuilder, promptBuilder, modelClient, parser, policy, pendingActions, audit,
                conversationLog, guard, registry, config, conversationContext, contactWindows,
                mediaProvider, null);
    }

    /** 不带旁路通知的一轮。内部调用与既有测试用它，行为与引入 {@link AssistantTurnSink} 之前一致。 */
    public AssistantTurnResult respond(UUID userId, UUID conversationId,
                                       List<AssistantMessage> providedHistory, String text) {
        return respond(userId, conversationId, providedHistory, text, List.of(), AssistantTurnSink.NONE);
    }

    /**
     * 跑一轮。这是「一轮对话」的**唯一入口** —— 选源、裁剪、装配上下文、决策、执行、
     * 审计、会话落地全在这里收口，调用方只交它手上有的东西（身份、会话号、调用方声称的历史、原话）。
     *
     * <p>{@code providedHistory} 不是权威输入：它只在一处被采用 —— 服务端对该会话没有任何记录时。
     * 见 {@link #authoritativeHistory}。
     *
     * <p>{@code sink} 只上报过程（见 {@link AssistantTurnSink}），**不改变结果**：返回值仍是唯一
     * 权威的那一份，展示层必须用它覆盖片段。
     */
    public AssistantTurnResult respond(UUID userId, UUID conversationId,
                                       List<AssistantMessage> providedHistory, String text,
                                       AssistantTurnSink sink) {
        return respond(userId, conversationId, providedHistory, text, List.of(), sink);
    }

    /**
     * 跑一轮，并带上用户这一轮发来的素材。
     *
     * <p>{@code attachmentAssetIds} 是**用户指定**的素材 id，不是检索结果：它会先被
     * {@link TemplateMediaProvider} 复核归属与状态，再作为第七组候选交给模型
     * （见 {@link TemplateMediaCandidates}）。不在候选里的 id，模型引用不了 ——
     * 工具的 {@code x-candidateSet} 声明会把它们拦在调用之前。
     *
     * <p>一个附件不可用**不会**让这一轮失败：它被丢弃并记一条 warn，那句话照常回答 ——
     * 用户说的是话，图是附加物，因为附加物坏掉就整轮不答等于连那句话一起丢了。
     */
    public AssistantTurnResult respond(UUID userId, UUID conversationId,
                                       List<AssistantMessage> providedHistory, String text,
                                       List<UUID> attachmentAssetIds, AssistantTurnSink sink) {
        if (userId == null) {
            // 与 InProcessToolAdapter 同一原则：取不到身份是「拒绝」，不是「降级成匿名」。
            throw new SecurityException("Not authenticated");
        }
        if (lifecycle != null) {
            lifecycle.requireActive(userId, conversationId);
        }
        List<AssistantConversationMessageEntity> persistedRows = conversationContext == null || conversationId == null
                // The log layer applies its hard page cap; semantic selection belongs to the token-budget owner.
                ? List.of() : conversationLog.recentRowsForPrompt(userId, conversationId, Integer.MAX_VALUE);
        List<AssistantMessage> sourceHistory = conversationContext == null
                ? authoritativeHistory(userId, conversationId, providedHistory)
                : persistedRows.isEmpty()
                ? providedHistory == null ? List.of() : providedHistory
                : persistedRows.stream().map(row -> new AssistantMessage(
                        AssistantMessage.Role.fromWire(row.getRole()), row.getText())).toList();
        AssistantRequestGuard.NormalisedRequest normalised = guard.normalise(sourceHistory, text);
        List<AssistantConversationMessageEntity> effectiveRows = persistedRows.isEmpty() || normalised.history().isEmpty()
                ? List.of()
                : persistedRows.subList(Math.max(0, persistedRows.size() - normalised.history().size()), persistedRows.size());
        TurnExecution execution = runTurn(userId, conversationId, effectiveRows,
                normalised.history(), normalised.text(), attachmentAssetIds, sink);
        // 裁剪是 guard 做的，但 guard 不认识响应体；把「丢了」这件事带上响应是这里的活 ——
        // 只有这里同时握着「选中的那份历史」与「裁剪结果」，也才说得清那个数字算的是谁。
        AssistantTurnResult decorated = execution.result().withTrimmedHistory(normalised.droppedHistoryMessages());
        if (execution.context() != null) {
            decorated = decorated.withTrimmedHistory(execution.context().droppedMessages())
                    .withHistoryCompaction(execution.context().summarizedMessages());
        }
        return decorated;
    }

    private List<AssistantMessage> authoritativeHistory(UUID userId, UUID conversationId,
                                                        List<AssistantMessage> providedHistory) {
        if (conversationId != null) {
            List<AssistantMessage> fromServer =
                    conversationLog.recentForPrompt(userId, conversationId, config.maxHistoryTurns());
            if (!fromServer.isEmpty()) return fromServer;
        }
        return providedHistory == null ? List.of() : providedHistory;
    }

    /** 已经定下历史与原话之后的那一轮编排。收在私有方法里，是为了让选源/裁剪只在外层发生一次。 */
    private TurnExecution runTurn(UUID userId, UUID conversationId,
                                        List<AssistantConversationMessageEntity> persistedRows,
                                        List<AssistantMessage> history, String text,
                                        List<UUID> attachmentAssetIds, AssistantTurnSink sink) {
        AssistantContext context = contextBuilder.build();
        if (contactWindows != null) {
            ContactCandidates restored = contactWindows.restore(userId, conversationId);
            if (restored != null) context = context.withCandidateSet(restored);
        }
        /*
         * 附件候选**每轮重建**，刻意不做任何记忆。
         *
         * 「这一轮带了哪张图」是请求体上的事实，不是会话状态 —— 前端每轮都重发同一批 id，
         * 直到用户自己点掉。所以这里不像 contactWindows 那样从服务端恢复：
         * 恢复反而会造出「用户已经移除了附件、服务端还记得」这种对不上的状态。
         */
        if (mediaProvider != null && attachmentAssetIds != null && !attachmentAssetIds.isEmpty()) {
            context = context.withCandidateSet(mediaProvider.candidatesFor(userId, attachmentAssetIds));
        }
        /*
         * 用户这一轮原话里写下的图片地址（第八组候选）。
         *
         * 与附件那段同一套理由：它是**用户这条消息里的事实**，不是会话状态 ——
         * 所以每轮从原话重新抽一遍，不做任何记忆。抽不到时这一组压根不进 context，
         * 于是「没有这组候选」与「这组候选是空的」在解析器那里指向同一个结果：引用不了任何地址
         * （见 AssistantContext#candidateSet：「未知集合不给放行」）。
         *
         * 为什么地址要收紧成候选、而不是给工具一个自由的 imageUrl 参数：
         * 上传由服务端主动去下载那个地址，模型若能给任意地址，它就能指向任意位置
         * （见 TemplateMediaLinkCandidates 的类注释）。
         */
        if (mediaProvider != null) {
            TemplateMediaLinkCandidates links = mediaProvider.linksIn(text);
            // 显式处理 null，与 accountTarget 里那条候选兜底同一个理由：
            // provider 的契约是「永远给一个集合（可能是空的）」，而这里要读它的 items()。
            // 真出现 null 时，正确的后果是「这一轮没有链接候选、那句话照常回答」，
            // 而不是让一次候选故障以 NPE 的形式变成整轮 500。
            if (links != null && !links.items().isEmpty()) {
                context = context.withCandidateSet(links);
            }
        }
        AssistantConversationContextService.PreparedContext prepared;
        try {
            prepared = conversationContext == null
                    ? new AssistantConversationContextService.PreparedContext(null, history, 0, 0)
                    : conversationContext.prepare(userId, conversationId, persistedRows, history, text, context);
        } catch (AssistantConversationContextService.ContextBudgetExceeded exceeded) {
            return new TurnExecution(finish(userId, conversationId, text, AssistantTurnResult.error(
                    "ASSISTANT_CONTEXT_BUDGET_EXCEEDED", "当前上下文超过模型预算，请缩短本轮请求或开启新会话")), null);
        }
        List<Map<String, String>> messages = promptBuilder.buildMessages(
                context, prepared.summary(), prepared.recentHistory(), text);
        // 一轮至多一次。放在循环外是因为重试与只读轮都不是「新一轮开始了」，重复上报只会让界面闪。
        sink.thinking();

        // readTurns 只数「真的执行了」的只读轮；round 数「向模型问了第几次」，两者分开是因为
        // 重试与拒绝都不消耗只读额度，却确实多问了一次模型。
        int readTurns = 0;
        int round = 0;
        while (true) {
            if (conversationContext != null && !conversationContext.withinBudget(messages)) {
                return new TurnExecution(finish(userId, conversationId, text, AssistantTurnResult.error(
                        "ASSISTANT_CONTEXT_BUDGET_EXCEEDED", "本轮检索结果超过模型上下文预算，请缩小问题后重试")), prepared);
            }
            long roundStartedNanos = System.nanoTime();
            // 一次模型调用配一个提取器：它记着扫描游标，跨轮复用会把上一轮的位置带到下一轮。
            AssistantReplyDeltaExtractor firstPass = new AssistantReplyDeltaExtractor();
            AssistantModelClient.ModelReply reply = modelClient.complete(messages, null,
                    raw -> emit(sink, firstPass, raw));
            AssistantDecisionParser.Outcome outcome = parser.parse(reply.content(), context);

            if (round == 0
                    && outcome instanceof AssistantDecisionParser.Rejected rejected
                    && rejected.retryable()) {
                // 唯一一次重试：回灌校验错误。仍失败就到此为止，不再尝试第二次。
                //
                // 重试前必须 reset：输出被截断时信封没有闭合，解析器判「输出不是一个 JSON 对象」，
                // 可提取器只认 decision=reply、不看信封完不完整 —— 那时片段可能已经推出去了。
                // 不 reset，用户会看到答案自己改写自己。换新提取器同理：游标属于上一份输出。
                sink.reset();
                messages = promptBuilder.withCorrection(messages, rejected.reason());
                AssistantReplyDeltaExtractor retryPass = new AssistantReplyDeltaExtractor();
                reply = modelClient.complete(messages, null,
                        raw -> emit(sink, retryPass, raw));
                outcome = parser.parse(reply.content(), context);
            }
            int latencyMs = elapsedMillis(roundStartedNanos);

            if (!(outcome instanceof AssistantDecisionParser.Call call)) {
                // ask / reply / rejected：都是终态，按原有逻辑处理并结束。
                return new TurnExecution(finish(userId, conversationId, text,
                        dispatchTerminal(userId, conversationId, text, reply, outcome, latencyMs, round)), prepared);
            }

            ToolDefinition definition = registry.find(call.tool()).orElseThrow();
            AssistantActionPolicy.Decision decision = policy.decide(definition);

            if (decision == AssistantActionPolicy.Decision.READ) {
                if (readTurns >= config.maxReadTurns()) {
                    return new TurnExecution(finish(userId, conversationId, text, exhausted(userId, conversationId, text,
                            call, reply, latencyMs, round)), prepared);
                }
                sink.reading(call.tool());
                ToolResult toolResult = registry.invoke(call.tool(), userId, call.arguments());
                // 只读轮也写审计：一行一个 turn，这样「模型看过哪些东西才做出这个决定」在库里能复原。
                // outcome 沿用 EXECUTED / FAILED —— 只读与写由 policy 列区分，不必再扩 outcome 词表。
                audit.record(new AssistantAuditService.Entry(userId, conversationId, text, "call", call.tool(),
                        call.arguments(), "READ", toolResult.isError() ? "FAILED" : "EXECUTED",
                        toolResult.code(), reply.model(), latencyMs, round));
                if (toolResult.isError()) {
                    log.info("assistant read round failed: tool={} code={}", call.tool(), toolResult.code());
                    // 失败即终止，不回灌 —— 理由见类注释「只读工具的失败为什么不回灌给模型」。
                    return new TurnExecution(finish(userId, conversationId, text, AssistantTurnResult.error(
                            toolResult.code(), "我没能查到需要的信息：" + toolResult.message())), prepared);
                }
                log.info("assistant read round ok: tool={}", call.tool());
                if (toolResult.candidates() != null) {
                    // 只读结果替换候选集：这是「只读检索突破候选集边界」的落点，下一轮才引用得到。
                    // 素材那一组是唯一例外（合并而非替换），理由见 mergeMediaCandidates。
                    context = context.withCandidateSet(mergeMediaCandidates(context, toolResult.candidates()));
                    if (contactWindows != null && toolResult.candidates() instanceof ContactCandidates found) {
                        contactWindows.remember(userId, conversationId, found);
                    }
                }
                messages = promptBuilder.withReadResult(messages, reply.content(), call.tool(), toolResult);
                readTurns++;
                round++;
                continue;
            }

            // 写动作（AUTO / CONFIRM）：执行或落待确认，然后本轮结束 —— 循环不得再转。
            return new TurnExecution(finish(userId, conversationId, text, dispatchWrite(userId, conversationId, text, call,
                    definition, decision, context, reply, latencyMs, round)), prepared);
        }
    }

    private record TurnExecution(AssistantTurnResult result,
                                 AssistantConversationContextService.PreparedContext context) {}

    /**
     * 工具回灌的候选如何进 context：素材那组合并，其余原样替换。
     *
     * <h2>为什么只有素材这一组要例外</h2>
     * 素材候选在同一轮里有<b>两个互不相干的来源</b>：用户贴的那张图（请求体里的附件）、
     * 以及工具刚从库里翻出来的那一批（{@code chatapp.template_media_list}）。
     * 「替换」语义会让前一张在模型手上突然失效，而它不会得到「引用已失效」，
     * 只会得到「这个 id 不在候选里」—— 于是把一句它自己也无法解释的话转述给用户。
     * 合并保住的正是这条链。
     *
     * <h2>为什么不去改那个通用方法</h2>
     * {@link AssistantContext#withCandidateSet} 的「替换」对其余各组是<b>对的</b>：
     * 追加会让候选随轮次无限增长，把「有界」这条前提悄悄破坏掉（那个方法的注释写的就是这件事）。
     * 所以例外只做在本域、只做在素材这一组，而且例外本身也有上界
     * （合并后的容量是两条来源之和，见 {@link TemplateMediaCandidates#mergedWith}）。
     */
    private static CandidateSet mergeMediaCandidates(AssistantContext context, CandidateSet incoming) {
        if (!TemplateMediaCandidates.NAME.equals(incoming.name())) {
            return incoming;
        }
        CandidateSet existing = context.candidateSet(TemplateMediaCandidates.NAME);
        return existing instanceof TemplateMediaCandidates current && incoming instanceof TemplateMediaCandidates fresh
                ? current.mergedWith(fresh)
                : incoming;
    }

    /**
     * 只读额度用尽。<b>不再问模型</b>，直接给一个诚实的「未完成」终态。
     *
     * <p>为什么不「再问一轮让它自己收尾」：那正是本轮改造要避免的东西 —— 模型在被强制收尾时
     * 的最优策略不是承认没查到，而是把手里的东西凑成一个像样的答案。宁可说「我没能完成」。
     */
    private AssistantTurnResult exhausted(UUID userId, UUID conversationId, String text,
                                          AssistantDecisionParser.Call call,
                                          AssistantModelClient.ModelReply reply, int latencyMs, int round) {
        audit.record(new AssistantAuditService.Entry(userId, conversationId, text, "call", call.tool(),
                call.arguments(), "READ", "REJECTED", READ_TURNS_EXHAUSTED, reply.model(), latencyMs, round));
        // 2026-09-28：这句话是「额度」说法的另一条传播途径 —— 它会被写进会话历史、
        // 下一次请求原样回放给模型，而模型照抄自己的措辞。所以这里刻意：
        //   ①把「这次」的主语写清楚是「这一条消息」，并显式给出「下一条重新计满」；
        //   ②不出现「额度」二字（那是内部机制的名字，与提示词硬规则 12 同一口径）。
        String message = config.maxReadTurns() <= 0
                ? "只读检索当前是关闭的，所以这次我没能完成。你可以直接告诉我想要的结果。"
                : "我没能在限定的 " + config.maxReadTurns() + " 步只读检索内查清楚，所以这次没能完成。"
                        + "你可以把问题缩小一些再发一条消息（每一条消息的检索步骤都是重新计满的），"
                        + "或者直接告诉我你想要的结果。";
        return AssistantTurnResult.error(READ_TURNS_EXHAUSTED, message);
    }

    /** ask / reply / rejected 三个终态。 */
    private AssistantTurnResult dispatchTerminal(UUID userId, UUID conversationId, String text,
                                                 AssistantModelClient.ModelReply reply,
                                                 AssistantDecisionParser.Outcome outcome, int latencyMs,
                                                 int round) {
        if (outcome instanceof AssistantDecisionParser.Rejected rejected) {
            // 整轮作废：**不执行任何动作**。审计里 decision 记 invalid，把模型到底错在哪留下来。
            log.info("assistant turn rejected: reason={}", rejected.reason());
            audit.record(new AssistantAuditService.Entry(userId, conversationId, text, "invalid", null, null,
                    "REJECTED", "INVALID", AssistantException.REQUEST_INVALID, reply.model(), latencyMs, round));
            return AssistantTurnResult.error(AssistantException.REQUEST_INVALID,
                    "我没能理解这次请求（" + rejected.reason() + "），请换个说法再说一次");
        }

        if (outcome instanceof AssistantDecisionParser.Ask ask) {
            audit.record(new AssistantAuditService.Entry(userId, conversationId, text, "ask", null, null,
                    null, "ANSWERED", null, reply.model(), latencyMs, round));
            return AssistantTurnResult.question(ask.question(), ask.missing());
        }

        AssistantDecisionParser.Reply answered = (AssistantDecisionParser.Reply) outcome;
        audit.record(new AssistantAuditService.Entry(userId, conversationId, text, "reply", null, null,
                null, "ANSWERED", null, reply.model(), latencyMs, round));
        return AssistantTurnResult.answer(answered.reply());
    }

    /** 写动作：AUTO 直接执行，CONFIRM 落待确认。判定已由调用方做过，这里不重判（一处判定点）。 */
    private AssistantTurnResult dispatchWrite(UUID userId, UUID conversationId, String text,
                                              AssistantDecisionParser.Call call, ToolDefinition definition,
                                              AssistantActionPolicy.Decision decision, AssistantContext context,
                                              AssistantModelClient.ModelReply reply, int latencyMs, int round) {
        if (decision == AssistantActionPolicy.Decision.CONFIRM) {
            // 落待确认。**这一轮不执行任何东西** —— kind=CONFIRMATION_REQUIRED 的含义就是「还没做」。
            AssistantTurnResult.Proposal proposal =
                    pendingActions.create(userId, conversationId, definition, call.arguments(), context);
            // 审计里记「策略要求确认、结果未定」：只靠待确认表无法回答「用户当时说了什么导致这张卡片出现」，
            // 而原话在一张只有 tool_name 的表里是找不到的。
            audit.record(new AssistantAuditService.Entry(userId, conversationId, text, "call", call.tool(),
                    call.arguments(), "CONFIRM", "PENDING", null, reply.model(), latencyMs, round));
            return AssistantTurnResult.confirmationRequired(proposal);
        }

        ToolResult result = registry.invoke(call.tool(), userId, call.arguments());
        audit.record(new AssistantAuditService.Entry(userId, conversationId, text, "call", call.tool(),
                call.arguments(), "AUTO", result.isError() ? "FAILED" : "EXECUTED",
                result.code(), reply.model(), latencyMs, round));
        return result.isError()
                ? AssistantTurnResult.error(result.code(), result.message())
                : AssistantTurnResult.executed(result.message());
    }

    /**
     * 会话落地刻意收口在这里，放在各个返回路径<b>之外</b>：它记的是「这一轮说了什么」，
     * 与「这一轮决定了什么」（审计的职责）是两件事。收口之后，无论从哪条路径返回，
     * 会话记录都恰好写一次 —— 循环里每轮各写一次会是错的（同一句话会被记多遍）。
     */
    private AssistantTurnResult finish(UUID userId, UUID conversationId, String text,
                                       AssistantTurnResult result) {
        conversationLog.appendUser(userId, conversationId, text);
        conversationLog.appendAssistant(userId, conversationId, result.kind(), result.message());
        if (lifecycle != null) {
            lifecycle.touch(userId, conversationId);
        }
        return result;
    }

    /** 把一块原始输出翻译成「可以给用户看的新片段」推出去。空片段不推 —— 展示层不必处理空事件。 */
    private static void emit(AssistantTurnSink sink, AssistantReplyDeltaExtractor extractor, String rawOutput) {
        String revealed = extractor.accept(rawOutput);
        if (!revealed.isEmpty()) {
            sink.answerDelta(revealed);
        }
    }

    private static int elapsedMillis(long startedNanos) {
        long millis = (System.nanoTime() - startedNanos) / 1_000_000L;
        return (int) Math.min(millis, Integer.MAX_VALUE);
    }
}
