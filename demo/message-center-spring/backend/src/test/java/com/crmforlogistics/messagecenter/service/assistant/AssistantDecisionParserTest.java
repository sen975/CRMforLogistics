package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.service.aitopic.AiTopicService;
import com.crmforlogistics.messagecenter.service.assistant.mcp.AiTopicAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.MessageAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolInputValidator;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.crmforlogistics.messagecenter.service.message.MessageQueryService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 解析器的严格性：<b>模型输出只是建议</b>（设计文档 §7.5）。
 *
 * <p>三个重点：
 *
 * <ol>
 *   <li><b>{@code decision} 是唯一分支依据。</b>阶段 0.3 实测里模型会在
 *       {@code decision=ask} 的同时填 {@code tool} / {@code arguments}；把它当调用
 *       就会把一次澄清变成一次写操作（而写操作里包含删除）。这里用一条专门的用例钉死。</li>
 *   <li><b>重试边界是明确的。</b>只有「信封没写对」才可重试；进入 {@code call} 分支之后的
 *       一切失败都不重试 —— 否则一次被拒的诉求会获得第二次机会改写成某个被允许的工具。</li>
 *   <li><b>身份字段进不来。</b>{@code arguments} 里塞 {@code userId} 必须被拒；
 *       这是 {@code additionalProperties: false} 在进程内路径上仍生效的证据
 *       （SDK 的校验在进程内<b>不执行</b>，所以靠 {@link ToolInputValidator}）。</li>
 * </ol>
 */
class AssistantDecisionParserTest {

    private final ToolRegistry registry = AssistantFixtures.registryWithMockMapper();
    private final AssistantDecisionParser parser =
            new AssistantDecisionParser(registry, new ToolInputValidator(), AssistantFixtures.objectMapper());

    private final AssistantContext context = AssistantFixtures.context();

    /**
     * 「一组引用」需要一个声明了它的工具，而通用夹具里没有（它只装待办）。
     * 单独建一个只含 {@code contact.topics_merge} 的注册表，是为了让被测行为只有一个来源：
     * 数组型引用的逐元素比对，而不是"别的工具顺带也在"。
     */
    private final ToolRegistry mergeRegistry = new ToolRegistry(
            List.of(new AiTopicAssistantTools(mock(AiTopicService.class)).contactTopicsMergeTool()),
            new ToolInputValidator(), AssistantFixtures.objectMapper());

    private final AssistantDecisionParser mergeParser =
            new AssistantDecisionParser(mergeRegistry, new ToolInputValidator(), AssistantFixtures.objectMapper());

    // ---------- decision=ask：唯一分支依据 ----------

    @Test
    void askCarriesQuestionAndMissingFields() {
        AssistantDecisionParser.Outcome outcome = parser.parse("""
                {"decision":"ask","question":"这条待办安排在什么时间？","missing":["date"]}
                """, context);

        assertThat(outcome).isInstanceOfSatisfying(AssistantDecisionParser.Ask.class, ask -> {
            assertThat(ask.question()).isEqualTo("这条待办安排在什么时间？");
            assertThat(ask.missing()).containsExactly("date");
        });
    }

    /**
     * 0.3 实测原文：{@code {"decision":"ask",...,"tool":"todo.update","arguments":{}}}。
     * 若解析器「见到 tool 就当 call」，这条会变成一个改期动作 —— 而用户只是被问了个问题。
     */
    @Test
    void askWithNonEmptyToolIsStillAnAskNeverACall() {
        AssistantDecisionParser.Outcome outcome = parser.parse("""
                {"decision":"ask","question":"要改到哪天？","missing":["date"],
                 "tool":"todo.update","arguments":{"todoId":"11111111-1111-4111-8111-111111111111"}}
                """, context);

        assertThat(outcome).isInstanceOf(AssistantDecisionParser.Ask.class);
    }

    @Test
    void askMayHaveAnEmptyMissingArray() {
        // 实测 B 格：不是缺参数，而是指代有歧义待澄清。
        AssistantDecisionParser.Outcome outcome = parser.parse("""
                {"decision":"ask","question":"你指的是哪一条？","missing":[]}
                """, context);

        assertThat(outcome).isInstanceOfSatisfying(AssistantDecisionParser.Ask.class,
                ask -> assertThat(ask.missing()).isEmpty());
    }

    @Test
    void askFallsBackToReplyWhenQuestionIsMissing() {
        AssistantDecisionParser.Outcome outcome = parser.parse("""
                {"decision":"ask","reply":"请问是哪一天？","missing":["date"]}
                """, context);

        assertThat(outcome).isInstanceOfSatisfying(AssistantDecisionParser.Ask.class,
                ask -> assertThat(ask.question()).isEqualTo("请问是哪一天？"));
    }

    @Test
    void askWithoutAnyTextIsRetryable() {
        assertRetryable(parser.parse("""
                {"decision":"ask","missing":["date"]}
                """, context));
    }

    // ---------- decision=reply ----------

    @Test
    void replyReturnsTheText() {
        AssistantDecisionParser.Outcome outcome = parser.parse("""
                {"decision":"reply","reply":"我没找到这条待办。"}
                """, context);

        assertThat(outcome).isInstanceOfSatisfying(AssistantDecisionParser.Reply.class,
                reply -> assertThat(reply.reply()).isEqualTo("我没找到这条待办。"));
    }

    @Test
    void replyWithBlankTextIsRetryable() {
        assertRetryable(parser.parse("""
                {"decision":"reply","reply":"   "}
                """, context));
    }

    // ---------- 信封层失败：可重试 ----------

    @Test
    void plainTextIsRetryable() {
        assertRetryable(parser.parse("我不太确定你的意思，能再说一次吗？", context));
    }

    @Test
    void jsonArrayIsRetryable() {
        assertRetryable(parser.parse("[{\"decision\":\"reply\"}]", context));
    }

    @Test
    void missingDecisionIsRetryable() {
        assertRetryable(parser.parse("{\"reply\":\"好的\"}", context));
    }

    @Test
    void unknownDecisionIsRetryable() {
        assertRetryable(parser.parse("{\"decision\":\"execute\"}", context));
    }

    @Test
    void markdownFenceIsToleratedButContentIsStillValidated() {
        AssistantDecisionParser.Outcome outcome = parser.parse("""
                ```json
                {"decision":"reply","reply":"好的"}
                ```
                """, context);

        assertThat(outcome).isInstanceOf(AssistantDecisionParser.Reply.class);
    }

    @Test
    void surroundingProseIsTolerated() {
        AssistantDecisionParser.Outcome outcome = parser.parse("""
                好的，这是我给出的结果：{"decision":"reply","reply":"好的"} 请查收。
                """, context);

        assertThat(outcome).isInstanceOf(AssistantDecisionParser.Reply.class);
    }

    // ---------- decision=call：一旦进入就不可重试 ----------

    @Test
    void callWithoutToolIsRejectedAndNotRetryable() {
        assertRejectedWithoutRetry(parser.parse("{\"decision\":\"call\"}", context));
    }

    @Test
    void inventedToolIsRejectedAndNotRetryable() {
        // 「发明工具名」不可重试：重试等于给模型第二次机会把发微信改写成某个待办工具。
        assertRejectedWithoutRetry(parser.parse("""
                {"decision":"call","tool":"wechat.send","arguments":{}}
                """, context));
    }

    @Test
    void argumentsMustBeAnObject() {
        assertRejectedWithoutRetry(parser.parse("""
                {"decision":"call","tool":"todo.delete","arguments":"todoId=1"}
                """, context));
    }

    @Test
    void missingRequiredArgumentIsRejected() {
        assertRejectedWithoutRetry(parser.parse("""
                {"decision":"call","tool":"todo.create","arguments":{"title":"和张总确认报价"}}
                """, context));
    }

    @Test
    void overlongTitleIsRejected() {
        String title = "x".repeat(201);
        assertRejectedWithoutRetry(parser.parse("""
                {"decision":"call","tool":"todo.create","arguments":{"title":"%s","date":"2026-09-22"}}
                """.formatted(title), context));
    }

    /**
     * 身份防线的直接证据：模型往 {@code arguments} 里偷塞 {@code userId} 必须被拒。
     * 这条一旦失守，模型（或被注入的模型）就能指定身份，越权立刻成立。
     */
    @Test
    void identityFieldsSmuggledIntoArgumentsAreRejected() {
        for (String field : new String[]{"userId", "user_id", "tenantId", "ownerId"}) {
            AssistantDecisionParser.Outcome outcome = parser.parse("""
                    {"decision":"call","tool":"todo.create",
                     "arguments":{"title":"和张总确认报价","date":"2026-09-22","%s":"%s"}}
                    """.formatted(field, AssistantFixtures.OTHER_USER), context);

            assertThat(outcome)
                    .as("arguments 里的身份字段 " + field + " 必须被拒")
                    .isInstanceOfSatisfying(AssistantDecisionParser.Rejected.class,
                            rejected -> assertThat(rejected.retryable()).isFalse());
        }
    }

    @Test
    void fabricatedTodoIdIsRejected() {
        assertRejectedWithoutRetry(parser.parse("""
                {"decision":"call","tool":"todo.complete",
                 "arguments":{"todoId":"99999999-9999-4999-8999-999999999999","completed":true}}
                """, context));
    }

    @Test
    void todoIdOfAnotherOwnerIsRejectedBecauseItIsNotInTheCandidates() {
        // 别人的待办不在本用户候选清单里 —— 清单比对这一层就够了，SQL 的 where user_id 是第二道。
        assertRejectedWithoutRetry(parser.parse("""
                {"decision":"call","tool":"todo.delete",
                 "arguments":{"todoId":"77777777-7777-4777-8777-777777777777"}}
                """, context));
    }

    @Test
    void wrongArgumentTypeIsRejected() {
        assertRejectedWithoutRetry(parser.parse("""
                {"decision":"call","tool":"todo.complete",
                 "arguments":{"todoId":"11111111-1111-4111-8111-111111111111","completed":"true"}}
                """, context));
    }

    @Test
    void unparseableDateIsRejected() {
        // 实测 C 格：「下个月 32 号」。schema 里的 format 只是给模型看的提示，不是校验。
        assertRejectedWithoutRetry(parser.parse("""
                {"decision":"call","tool":"todo.update",
                 "arguments":{"todoId":"11111111-1111-4111-8111-111111111111","date":"2026-10-32"}}
                """, context));
    }

    @Test
    void timeMustBeZeroPadded() {
        assertRejectedWithoutRetry(parser.parse("""
                {"decision":"call","tool":"todo.create",
                 "arguments":{"title":"和张总确认报价","date":"2026-09-22","time":"9:30"}}
                """, context));
    }

    @Test
    void updateWithoutAnyFieldToChangeIsRejected() {
        assertRejectedWithoutRetry(parser.parse("""
                {"decision":"call","tool":"todo.update",
                 "arguments":{"todoId":"11111111-1111-4111-8111-111111111111"}}
                """, context));
    }

    @Test
    void callWithTodoIdOutsideAnEmptyCandidateListIsRejected() {
        AssistantDecisionParser.Outcome outcome = parser.parse("""
                {"decision":"call","tool":"todo.complete",
                 "arguments":{"todoId":"11111111-1111-4111-8111-111111111111","completed":true}}
                """, AssistantFixtures.emptyContext());

        assertThat(outcome).isInstanceOf(AssistantDecisionParser.Rejected.class);
    }

    // ---------- 正常路径 ----------

    @Test
    void createCallIsAccepted() {
        AssistantDecisionParser.Outcome outcome = parser.parse("""
                {"decision":"call","tool":"todo.create",
                 "arguments":{"title":"和张总确认报价","date":"2026-09-22","time":"15:00"}}
                """, context);

        assertThat(outcome).isInstanceOfSatisfying(AssistantDecisionParser.Call.class, call -> {
            assertThat(call.tool()).isEqualTo("todo.create");
            assertThat(call.arguments()).containsEntry("title", "和张总确认报价")
                    .containsEntry("date", "2026-09-22")
                    .containsEntry("time", "15:00");
        });
    }

    @Test
    void completeCallResolvesAgainstTheCandidateList() {
        AssistantDecisionParser.Outcome outcome = parser.parse("""
                {"decision":"call","tool":"todo.complete",
                 "arguments":{"todoId":"11111111-1111-4111-8111-111111111111","completed":true}}
                """, context);

        assertThat(outcome).isInstanceOfSatisfying(AssistantDecisionParser.Call.class,
                call -> assertThat(call.tool()).isEqualTo("todo.complete"));
    }

    @Test
    void callWithoutArgumentsKeyIsTreatedAsEmptyThenRejectedByRequired() {
        assertRejectedWithoutRetry(parser.parse("""
                {"decision":"call","tool":"todo.delete"}
                """, context));
    }

    // ---------- 两条校验路径：提问比对候选，确认不比对 ----------

    @Test
    void validateCallAppliesTheSameRulesAsParsing() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("todoId", AssistantFixtures.TODO_QUOTE);
        arguments.put("completed", true);

        assertThat(parser.validateCall("todo.complete", arguments, context))
                .isInstanceOf(AssistantDecisionParser.Call.class);
    }

    /**
     * 确认路径<b>不</b>比对候选清单 —— 候选清单是「最近能看到什么」的<b>窗口</b>
     * （会话 20 条、待办 70 条），而只读检索的价值正是<b>突破这个窗口</b>。
     * 拿窗口判存续，会把「先检索、再对检索结果动手」整条路判死。
     *
     * <p>第一段的对照断言同样是守卫：提问路径必须继续比对候选，否则「防编造 id」就没了。
     */
    @Test
    void validateConfirmedCallDoesNotConsultTheCandidateList() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("todoId", AssistantFixtures.TODO_QUOTE);
        arguments.put("completed", true);

        assertThat(parser.validateConfirmedCall("todo.complete", arguments))
                .as("同一份参数：提问路径在空候选下被拒，确认路径必须放行")
                .isInstanceOf(AssistantDecisionParser.Call.class);
        assertThat(parser.validateCall("todo.complete", arguments, AssistantFixtures.emptyContext()))
                .as("对照：提问路径仍然要比对候选（防模型编造 id）")
                .isInstanceOf(AssistantDecisionParser.Rejected.class);
    }

    /** 跳过候选比对<b>不等于</b>不校验：未知工具、schema 不符、日期不可解析照旧拦住。 */
    @Test
    void validateConfirmedCallStillRejectsStructuralProblems() {
        Map<String, Object> unknownTool = new LinkedHashMap<>();
        unknownTool.put("todoId", AssistantFixtures.TODO_QUOTE);
        assertRejectedWithoutRetry(parser.validateConfirmedCall("todo.nope", unknownTool));

        Map<String, Object> missingRequired = new LinkedHashMap<>();
        missingRequired.put("completed", true);
        assertRejectedWithoutRetry(parser.validateConfirmedCall("todo.complete", missingRequired));

        Map<String, Object> badDate = new LinkedHashMap<>();
        badDate.put("todoId", AssistantFixtures.TODO_QUOTE);
        badDate.put("date", "2026-10-32");
        assertRejectedWithoutRetry(parser.validateConfirmedCall("todo.update", badDate));
    }

    // ---------- 一组引用：数组型引用参数 ----------

    /**
     * 数组型引用必须<b>逐元素</b>比对候选。
     *
     * <p>这条守的是一个曾经真实存在的洞：绑定比对原来是
     * {@code if (!(value instanceof String reference)) continue} —— 数组值被整段跳过。
     * 它不会让任何东西报错：声明里有绑定、启动自检不拦、给声明写的测试照样绿，
     * 唯一的后果是「模型编造的 id 进不来」这道拦截<b>整条不存在</b>。
     * 所以这里的断言不是「能跑通」，而是「混进一个编造的 id 就必须整组被拒」。
     */
    @Test
    void aFabricatedReferenceInsideAnArrayIsRejected() {
        assertRejectedWithoutRetry(mergeParser.parse("""
                {"decision":"call","tool":"contact.topics_merge",
                 "arguments":{"topicRefs":["TOPIC:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                                           "TOPIC:99999999-9999-4999-8999-999999999999"]}}
                """, contextWithTopics()));
    }

    @Test
    void anArrayOfReferencesThatAllHitTheCandidatesIsAccepted() {
        AssistantDecisionParser.Outcome outcome = mergeParser.parse("""
                {"decision":"call","tool":"contact.topics_merge",
                 "arguments":{"topicRefs":["TOPIC:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                                           "TOPIC:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"]}}
                """, contextWithTopics());

        assertThat(outcome).isInstanceOfSatisfying(AssistantDecisionParser.Call.class,
                call -> assertThat(call.tool()).isEqualTo("contact.topics_merge"));
    }

    /**
     * 对照：同一个工具、同一份参数，候选组缺席时必须拒。
     *
     * <p>新加一条分支最容易犯的错是「顺手把它也放过去」——
     * 单值那路的 fail-closed 不能因为多了一个数组分支就松动。
     */
    @Test
    void anArrayReferenceWithoutTheCandidateSetIsStillRejected() {
        assertRejectedWithoutRetry(mergeParser.parse("""
                {"decision":"call","tool":"contact.topics_merge",
                 "arguments":{"topicRefs":["TOPIC:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                                           "TOPIC:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"]}}
                """, AssistantFixtures.context()));
    }

    /**
     * 带 topic 候选窗口的上下文。
     *
     * <p>topic 候选没有预置窗口（见 {@code TopicCandidates} 的类注释），所以只能像
     * {@code contact.topics_read} 那样现造一个 —— 这本身就是「先查再改」的形状。
     */
    private static AssistantContext contextWithTopics() {
        return new AssistantContext("Asia/Shanghai", AssistantFixtures.TODAY, "星期一",
                List.of(AssistantFixtures.conversationCandidates(), AssistantFixtures.contactCandidates(),
                        new TopicCandidates(TopicCandidates.LIMIT, List.of(
                                new TopicCandidates.Item("TOPIC:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "运价谈判", 3L),
                                new TopicCandidates.Item("TOPIC:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "运价与账期", 1L)))));
    }

    // ---------- 消息域：候选窗口只能来自上一轮的结果（C4） ----------

    /**
     * 引用窗口里的那一条 → 通过。
     *
     * <p>这条与 {@code aMessageReadBeforeAnyTimelineIsRejected…} 是一对：单看这一条
     * 说明不了任何事（任何比对写错的方式都会让它通过），必须靠那一条一起成立才有意义。
     */
    @Test
    void aMessageReferenceFromTheLastRoundIsAccepted() {
        AssistantDecisionParser.Outcome outcome = messageParser().parse("""
                {"decision":"call","tool":"message.read",
                 "arguments":{"messageRefs":["MESSAGE:99999999-9999-4999-8999-999999999999"]}}
                """, contextWithMessages());

        assertThat(outcome).isInstanceOfSatisfying(AssistantDecisionParser.Call.class,
                call -> assertThat(call.tool()).isEqualTo("message.read"));
    }

    /**
     * 没读过时间线就引用消息 → 必须拒。
     *
     * <p>{@code MessageCandidates} <b>没有预置窗口</b>（消息是十万级，预先算"最近 N 条"既贵又没意义），
     * 它只能由 {@code contact.timeline} 的结果产生。所以这一格是"C4 只读却仍要进候选"的落点：
     * 只读工具通常靠"检索突破窗口"，而 {@code message.read} 的入参是一个<b>不可检索</b>的 id ——
     * 候选比对在这里是唯一一道程序化防线，它必须真的跑。
     */
    @Test
    void aMessageReadBeforeAnyTimelineIsRejectedBecauseTheWindowDoesNotExistYet() {
        assertRejectedWithoutRetry(messageParser().parse("""
                {"decision":"call","tool":"message.read",
                 "arguments":{"messageRefs":["MESSAGE:99999999-9999-4999-8999-999999999999"]}}
                """, AssistantFixtures.context()));
    }

    /**
     * 队里混进一个不在窗口里的引用 → <b>整体</b>拒。
     *
     * <p>这是批量化最容易漏的那一格：只判「它是个数组」会让模型混一个编造的 id 进来，
     * 而防线看起来还在（绑定查得到、启动自检也过、给声明写的测试照样绿）。
     * 与 {@link #aMessageReferenceFromTheLastRoundIsAccepted} 配对读才有意义 ——
     * 一条通过、一条拒绝，才证明逐元素比对真的在跑（{@code AssistantDecisionParser.referencesOf}）。
     */
    @Test
    void oneUnknownReferenceInABatchRejectsTheWholeCall() {
        assertRejectedWithoutRetry(messageParser().parse("""
                {"decision":"call","tool":"message.read",
                 "arguments":{"messageRefs":["MESSAGE:99999999-9999-4999-8999-999999999999",
                                             "MESSAGE:88888888-8888-4888-8888-888888888888"]}}
                """, contextWithMessages()));
    }

    private static AssistantDecisionParser messageParser() {
        ToolRegistry registry = new ToolRegistry(
                List.of(new MessageAssistantTools(mock(MessageQueryService.class)).messageReadTool()),
                new ToolInputValidator(), AssistantFixtures.objectMapper());
        return new AssistantDecisionParser(registry, new ToolInputValidator(), AssistantFixtures.objectMapper());
    }

    /** 带上 message 候选窗口的上下文：模拟 {@code contact.timeline} 刚刚跑过一轮。 */
    private static AssistantContext contextWithMessages() {
        return new AssistantContext("Asia/Shanghai", AssistantFixtures.TODAY, "星期一",
                List.of(AssistantFixtures.conversationCandidates(), AssistantFixtures.contactCandidates(),
                        new MessageCandidates(MessageCandidates.LIMIT, List.of(
                                new MessageCandidates.Item("MESSAGE:99999999-9999-4999-8999-999999999999",
                                        "inbound", "delivered", "2026-09-20T10:00:00Z")))));
    }

    // ---------- 断言工具 ----------

    private static void assertRetryable(AssistantDecisionParser.Outcome outcome) {
        assertThat(outcome).isInstanceOfSatisfying(AssistantDecisionParser.Rejected.class,
                rejected -> assertThat(rejected.retryable())
                        .as("信封层失败才允许重试一次：" + rejected.reason()).isTrue());
    }

    private static void assertRejectedWithoutRetry(AssistantDecisionParser.Outcome outcome) {
        assertThat(outcome).isInstanceOfSatisfying(AssistantDecisionParser.Rejected.class,
                rejected -> assertThat(rejected.retryable())
                        .as("进入 call 分支后一律不重试：" + rejected.reason()).isFalse());
    }
}
