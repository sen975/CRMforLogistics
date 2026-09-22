package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolInputValidator;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
