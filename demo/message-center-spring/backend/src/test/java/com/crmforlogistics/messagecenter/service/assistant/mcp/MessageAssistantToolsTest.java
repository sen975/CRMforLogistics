package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.dto.response.MessageAttachmentResponse;
import com.crmforlogistics.messagecenter.dto.response.MessageResponse;
import com.crmforlogistics.messagecenter.service.assistant.AssistantPromptBuilder;
import com.crmforlogistics.messagecenter.service.assistant.MessageCandidates;
import com.crmforlogistics.messagecenter.service.message.MessageQueryService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code message.read}：四件事，前两件是口径变更的落点。
 *
 * <h1>1. 它<b>必须</b>回原文</h1>
 * 2026-09-23 用户拍板「允许原文进上下文」。所以这个工具如果只回元数据（时间、方向、状态），
 * 它是一个<b>功能上不成立</b>的工具 —— 用户问「他原话怎么说的」，它答不出任何内容。
 * 这正是这条用例要钉的点：正文必须出现在 {@code data} 里，且必须是服务端解析后的文本
 * （{@code bodyText}，渠道模板已经还原成人看得懂的样子）。
 *
 * <h1>2. 越权只能是「参数不合法」，不能是「系统故障」</h1>
 * {@code MessageQueryService.getMessage(id, userId)} 拿不到时返回 {@code null}
 * （owner 过滤在它自己的 SQL 里）。不转译的话这个 {@code null} 会变成一个空结果或者 NPE，
 * 前者让模型以为"这条消息是空的"，后者报成 INTERNAL
 * —— 两种都会让用户以为系统坏了，而真实原因是"这条不是你能看的"。
 *
 * <h1>3. HTML 只在没有纯文本时才带上</h1>
 * 正文与 HTML 同时带会让样式与标签把真实内容挤出 4000 字符的 observation 预算，
 * 而截断之后模型看到的是半段 HTML。这条规则容易被后来的人当成冗余删掉，所以写死在这里。
 *
 * <h1>4. 2026-09-28：一次调用读一队（{@code messageRefs}）</h1>
 * 单条一条地读会吃掉与条数相同的只读轮数（额度只有 3 轮），模型于是在第 2 轮就自己
 * 下结论「额度用完了」—— 那是假的。所以这里要钉住三件事：一次调用真的读回一队、
 * 队里任何一个引用不合法就<b>整体拒</b>（不做部分成功）、超体积预算的条<b>明写</b>在
 * {@code omittedRefs} 里而不是静默消失；同时<b>单条读取的保真度不能变</b>
 * （一条超长正文仍要逐字带回，不因预算被截）。
 */
class MessageAssistantToolsTest {

    private static final UUID USER = AssistantFixtures.USER;
    private static final UUID MESSAGE_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final String REF = MessageCandidates.idOf(MESSAGE_ID);

    private final MessageQueryService messages = mock(MessageQueryService.class);
    private final MessageAssistantTools tools = new MessageAssistantTools(messages);
    private final ToolRegistry registry = new ToolRegistry(
            List.of(tools.messageReadTool()),
            new ToolInputValidator(), AssistantFixtures.objectMapper());

    // ---------- 原文必须出得来（口径 1） ----------

    @Test
    void theMessageTextIsReturnedBecauseThatIsTheWholePointOfTheTool() {
        when(messages.getMessage(MESSAGE_ID, USER)).thenReturn(message(
                "这批货能不能便宜点，我们长期合作", "<p>这批货能不能便宜点，我们长期合作</p>"));

        ToolResult result = registry.invoke(MessageAssistantTools.TOOL_READ, USER, refs(REF));

        assertThat(result.isError()).isFalse();
        assertThat(itemOf(result, 0)).containsEntry("messageText", "这批货能不能便宜点，我们长期合作");
        assertThat(itemOf(result, 0)).containsEntry("direction", "inbound");
        assertThat(itemOf(result, 0)).containsEntry("kind", "text");
        assertThat(itemOf(result, 0)).containsEntry("messageRef", REF);
        assertThat(result.message())
                .as("一句话里只说这条消息是哪一个；正文进 message 就等于让同一段文本占两份预算")
                .doesNotContain("这批货能不能便宜点");
    }

    @Test
    void theLookupIsOwnerScopedBecauseTheToolPassesTheCallerIdentityThrough() {
        when(messages.getMessage(MESSAGE_ID, USER)).thenReturn(message("你好", null));

        registry.invoke(MessageAssistantTools.TOOL_READ, USER, refs(REF));

        verify(messages).getMessage(MESSAGE_ID, USER);
    }

    @Test
    void htmlIsOnlyCarriedWhenThereIsNoPlainText() {
        when(messages.getMessage(MESSAGE_ID, USER))
                .thenReturn(message("正文在这", "<p style=\"color:#333\">正文在这</p>"));
        ToolResult withText = registry.invoke(MessageAssistantTools.TOOL_READ, USER, refs(REF));

        assertThat(itemOf(withText, 0))
                .as("有纯文本时不带 HTML：否则样式会把真实内容挤出 observation 预算")
                .doesNotContainKey("bodyHtml");

        when(messages.getMessage(MESSAGE_ID, USER)).thenReturn(message(null, "<p>只有 HTML 的邮件</p>"));
        ToolResult htmlOnly = registry.invoke(MessageAssistantTools.TOOL_READ, USER, refs(REF));

        assertThat(itemOf(htmlOnly, 0))
                .as("反过来，没有纯文本时不带 HTML 就等于什么都没带")
                .containsEntry("bodyHtml", "<p>只有 HTML 的邮件</p>");
    }

    @Test
    void attachmentsAreCarriedAsMetadataOnly() {
        when(messages.getMessage(MESSAGE_ID, USER)).thenReturn(new MessageResponse(
                MESSAGE_ID, "inbound", "text", null, "见附件", null, "email",
                "李工", "我", Instant.parse("2026-09-20T10:00:00Z"), "delivered", 3,
                List.of(new MessageAttachmentResponse(UUID.randomUUID(), "file", "application/pdf",
                        "报价单.pdf", 2048L))));

        ToolResult result = registry.invoke(MessageAssistantTools.TOOL_READ, USER, refs(REF));

        assertThat(attachmentsOf(itemOf(result, 0)).get(0).keySet())
                .as("只搬元数据：附件 id 与下载位置都不该进提示词")
                .containsExactlyInAnyOrder("fileName", "mimeType", "sizeBytes");
    }

    @Test
    void explicitMessageReadNeverAddsRawSenderOrRecipientFields() {
        when(messages.getMessage(MESSAGE_ID, USER)).thenReturn(new MessageResponse(
                MESSAGE_ID, "inbound", "text", null, "用户点名读取的正文", null, "email",
                "sender@example.test", "recipient@example.test", Instant.parse("2026-09-20T10:00:00Z"),
                "delivered", 3, List.of()));

        ToolResult result = registry.invoke(MessageAssistantTools.TOOL_READ, USER, refs(REF));

        assertThat(result.data()).containsOnlyKeys("messages");
        assertThat(itemOf(result, 0)).containsOnlyKeys("messageRef", "kind", "direction", "subject", "messageText",
                "channelType", "occurredAt", "status", "attachments");
        assertThat(result.data().toString()).doesNotContain("sender@example.test", "recipient@example.test", "userId");
        assertThat(result.message()).doesNotContain("sender@example.test", "recipient@example.test");
    }

    // ---------- 越权翻译成参数层错误（口径 2） ----------

    @Test
    void anOutOfScopeMessageUsesNonEnumeratingAccessFailure() {
        when(messages.getMessage(any(), any())).thenReturn(null);

        ToolResult result = registry.invoke(MessageAssistantTools.TOOL_READ, USER, refs(REF));

        assertThat(result.isError()).isTrue();
        assertThat(result.code())
                .as("报成 INTERNAL 会让用户以为系统坏了并反复重试")
                .isEqualTo(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND);
        assertThat(result.message()).isEqualTo(ToolExecutionException.ACCESS_DENIED_MESSAGE)
                .doesNotContain(MESSAGE_ID.toString());
    }

    @Test
    void aReferenceFromAnotherDomainIsRejectedWithoutTouchingTheService() {
        ToolResult result = registry.invoke(MessageAssistantTools.TOOL_READ, USER,
                refs(AssistantFixtures.CONVERSATION_ZHOU));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
    }

    // ---------- 批量：一次读一队（口径 4） ----------

    @Test
    void aBatchOfReferencesIsReadInOneCallAndReturnedInRequestOrder() {
        UUID second = UUID.fromString("88888888-8888-4888-8888-888888888881");
        when(messages.getMessage(MESSAGE_ID, USER)).thenReturn(message("第一条", null));
        when(messages.getMessage(second, USER)).thenReturn(message(second, "第二条", null));

        ToolResult result = registry.invoke(MessageAssistantTools.TOOL_READ, USER,
                refs(REF, MessageCandidates.idOf(second)));

        assertThat(itemsOf(result))
                .as("请求给的顺序就是带回来的顺序：模型据此把每条对回它自己列出的清单")
                .extracting(item -> item.get("messageText"))
                .containsExactly("第一条", "第二条");
        verify(messages, times(1)).getMessage(MESSAGE_ID, USER);
        verify(messages, times(1)).getMessage(second, USER);
    }

    @Test
    void aBatchFailsAsAWholeWhenAnySingleMessageIsOutOfScope() {
        UUID second = UUID.fromString("88888888-8888-4888-8888-888888888881");
        when(messages.getMessage(MESSAGE_ID, USER)).thenReturn(message("第一条", null));
        when(messages.getMessage(second, USER)).thenReturn(null);

        ToolResult result = registry.invoke(MessageAssistantTools.TOOL_READ, USER,
                refs(REF, MessageCandidates.idOf(second)));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND);
        assertThat(result.data())
                .as("不做部分成功：「带回一半、静默少一条」会让模型把少的那些当成本来就不存在")
                .isEmpty();
    }

    @Test
    void moreReferencesThanTheDeclaredMaximumIsRejectedBeforeAnyLookup() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= MessageAssistantTools.MAX_REFS; i++) {
            tooMany.add(MessageCandidates.idOf(UUID.randomUUID()));
        }

        ToolResult result = registry.invoke(MessageAssistantTools.TOOL_READ, USER, Map.of("messageRefs", tooMany));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        verify(messages, never()).getMessage(any(), any());
    }

    @Test
    void aBlankReferenceIsRejectedAsAMissingArgumentRatherThanReturningNothing() {
        ToolResult result = registry.invoke(MessageAssistantTools.TOOL_READ, USER, refs("   "));

        assertThat(result.isError()).isTrue();
        assertThat(result.code())
                .as("「带回了 0 条消息」是个看起来成功的形状，必须报成缺参数")
                .isEqualTo(ToolExecutionException.MISSING_ARGUMENT);
        verify(messages, never()).getMessage(any(), any());
    }

    // ---------- 批量不等于可以把结果放大到无界（口径 4 的另一半） ----------

    @Test
    void aSingleBodyLongerThanTheBatchBudgetIsStillReturnedWhole() {
        String body = "长".repeat(5000);
        when(messages.getMessage(MESSAGE_ID, USER)).thenReturn(message(body, null));

        ToolResult result = registry.invoke(MessageAssistantTools.TOOL_READ, USER, refs(REF));

        assertThat(itemOf(result, 0)).containsEntry("messageText", body);
        assertThat(result.data())
                .as("单条读取的保真度与改批量之前逐字一致：不能因为超预算就把它截掉或丢掉")
                .doesNotContainKey("omittedRefs");
    }

    @Test
    void messagesBeyondTheTextBudgetAreListedAsOmittedInsteadOfSilentlyDropped() {
        UUID second = UUID.fromString("88888888-8888-4888-8888-888888888881");
        UUID third = UUID.fromString("88888888-8888-4888-8888-888888888882");
        String body = "长".repeat(2000);
        when(messages.getMessage(MESSAGE_ID, USER)).thenReturn(message(body, null));
        when(messages.getMessage(second, USER)).thenReturn(message(second, body, null));
        when(messages.getMessage(third, USER)).thenReturn(message(third, body, null));

        ToolResult result = registry.invoke(MessageAssistantTools.TOOL_READ, USER,
                refs(REF, MessageCandidates.idOf(second), MessageCandidates.idOf(third)));

        assertThat(itemsOf(result)).as("这一轮的预算只装得下第一条").hasSize(1);
        assertThat(result.data()).containsEntry("omittedRefs",
                List.of(MessageCandidates.idOf(second), MessageCandidates.idOf(third)));
        assertThat(result.message())
                .as("丢了的必须写清楚，并给出下一步：只传这几个引用再调一次")
                .contains("2 条")
                .contains("omittedRefs");
    }

    @Test
    void theBatchBudgetLeavesRoomInsideASingleObservation() {
        assertThat(MessageAssistantTools.BATCH_TEXT_BUDGET_CHARS)
                .as("预算必须为正，且真的给 observation 的信封留下余量 —— 两份数字是同一处来源")
                .isGreaterThan(0)
                .isLessThan(AssistantPromptBuilder.OBSERVATION_MAX_CHARS);
    }

    // ---------- 声明 ----------

    @Test
    void theDeclarationIsReadOnlyAndBindsTheMessageCandidateSet() {
        ToolDefinition definition = registry.find(MessageAssistantTools.TOOL_READ).orElseThrow();

        assertThat(definition.tool().annotations().readOnlyHint()).isTrue();
        assertThat(definition.tool().annotations().destructiveHint()).isFalse();
        assertThat(definition.referenceBindings())
                .as("绑定必须能从这个字段上读出来：读不出来就等于「模型编造 id」的比对静默失效")
                .containsExactly(Map.entry("messageRefs", MessageCandidates.NAME));
        assertThat(fieldOf(definition, "messageRefs"))
                .containsEntry("type", "array")
                .containsEntry("minItems", 1)
                .containsEntry("maxItems", MessageAssistantTools.MAX_REFS);
    }

    // ---------- 夹具 ----------

    private static Map<String, Object> refs(String... references) {
        return Map.of("messageRefs", List.of(references));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(ToolResult result) {
        return (List<Map<String, Object>>) result.data().get("messages");
    }

    private static Map<String, Object> itemOf(ToolResult result, int index) {
        return itemsOf(result).get(index);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attachmentsOf(Map<String, Object> item) {
        return (List<Map<String, Object>>) item.get("attachments");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldOf(ToolDefinition definition, String name) {
        return (Map<String, Object>) ((Map<String, Object>) definition.tool().inputSchema().get("properties")).get(name);
    }

    private static MessageResponse message(String bodyText, String bodyHtml) {
        return message(MESSAGE_ID, bodyText, bodyHtml);
    }

    private static MessageResponse message(UUID id, String bodyText, String bodyHtml) {
        return new MessageResponse(id, "inbound", "text", null, bodyText, bodyHtml, "chatapp",
                "李工", "我", Instant.parse("2026-09-20T10:00:00Z"), "delivered", 3, List.of());
    }
}
