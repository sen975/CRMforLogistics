package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.dto.response.MessageAttachmentResponse;
import com.crmforlogistics.messagecenter.dto.response.MessageResponse;
import com.crmforlogistics.messagecenter.service.assistant.MessageCandidates;
import com.crmforlogistics.messagecenter.service.message.MessageQueryService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code message.read}：三件事，其中第一件是这次口径变更的落点。
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

    @Test
    void theMessageTextIsReturnedBecauseThatIsTheWholePointOfTheTool() {
        when(messages.getMessage(MESSAGE_ID, USER)).thenReturn(message(
                "这批货能不能便宜点，我们长期合作", "<p>这批货能不能便宜点，我们长期合作</p>"));

        ToolResult result = registry.invoke(MessageAssistantTools.TOOL_READ, USER, Map.of("messageRef", REF));

        assertThat(result.isError()).isFalse();
        assertThat(result.data()).containsEntry("messageText", "这批货能不能便宜点，我们长期合作");
        assertThat(result.data()).containsEntry("direction", "inbound");
        assertThat(result.data()).containsEntry("kind", "text");
        assertThat(result.data()).containsEntry("messageRef", REF);
        assertThat(result.message())
                .as("一句话里只说这条消息是哪一个；正文进 message 就等于让同一段文本占两份预算")
                .doesNotContain("这批货能不能便宜点");
    }

    @Test
    void theLookupIsOwnerScopedBecauseTheToolPassesTheCallerIdentityThrough() {
        when(messages.getMessage(MESSAGE_ID, USER)).thenReturn(message("你好", null));

        registry.invoke(MessageAssistantTools.TOOL_READ, USER, Map.of("messageRef", REF));

        verify(messages).getMessage(MESSAGE_ID, USER);
    }

    @Test
    void htmlIsOnlyCarriedWhenThereIsNoPlainText() {
        when(messages.getMessage(MESSAGE_ID, USER))
                .thenReturn(message("正文在这", "<p style=\"color:#333\">正文在这</p>"));
        ToolResult withText = registry.invoke(MessageAssistantTools.TOOL_READ, USER, Map.of("messageRef", REF));

        assertThat(withText.data())
                .as("有纯文本时不带 HTML：否则样式会把真实内容挤出 observation 预算")
                .doesNotContainKey("bodyHtml");

        when(messages.getMessage(MESSAGE_ID, USER)).thenReturn(message(null, "<p>只有 HTML 的邮件</p>"));
        ToolResult htmlOnly = registry.invoke(MessageAssistantTools.TOOL_READ, USER, Map.of("messageRef", REF));

        assertThat(htmlOnly.data())
                .as("反过来，没有纯文本时不带 HTML 就等于什么都没带")
                .containsEntry("bodyHtml", "<p>只有 HTML 的邮件</p>");
    }

    @Test
    void anOutOfScopeMessageUsesNonEnumeratingAccessFailure() {
        when(messages.getMessage(any(), any())).thenReturn(null);

        ToolResult result = registry.invoke(MessageAssistantTools.TOOL_READ, USER, Map.of("messageRef", REF));

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
                Map.of("messageRef", AssistantFixtures.CONVERSATION_ZHOU));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
    }

    @Test
    void attachmentsAreCarriedAsMetadataOnly() {
        when(messages.getMessage(MESSAGE_ID, USER)).thenReturn(new MessageResponse(
                MESSAGE_ID, "inbound", "text", null, "见附件", null, "email",
                "李工", "我", Instant.parse("2026-09-20T10:00:00Z"), "delivered", 3,
                List.of(new MessageAttachmentResponse(UUID.randomUUID(), "file", "application/pdf",
                        "报价单.pdf", 2048L))));

        ToolResult result = registry.invoke(MessageAssistantTools.TOOL_READ, USER, Map.of("messageRef", REF));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments = (List<Map<String, Object>>) result.data().get("attachments");
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).keySet())
                .as("只搬元数据：附件 id 与下载位置都不该进提示词")
                .containsExactlyInAnyOrder("fileName", "mimeType", "sizeBytes");
    }

    @Test
    void explicitMessageReadNeverAddsRawSenderOrRecipientFields() {
        when(messages.getMessage(MESSAGE_ID, USER)).thenReturn(new MessageResponse(
                MESSAGE_ID, "inbound", "text", null, "用户点名读取的正文", null, "email",
                "sender@example.test", "recipient@example.test", Instant.parse("2026-09-20T10:00:00Z"),
                "delivered", 3, List.of()));

        ToolResult result = registry.invoke(MessageAssistantTools.TOOL_READ, USER, Map.of("messageRef", REF));

        assertThat(result.data()).containsOnlyKeys("messageRef", "kind", "direction", "subject", "messageText",
                "channelType", "occurredAt", "status", "attachments");
        assertThat(result.data().toString()).doesNotContain("sender@example.test", "recipient@example.test", "userId");
        assertThat(result.message()).doesNotContain("sender@example.test", "recipient@example.test");
    }

    @Test
    void theDeclarationIsReadOnlyAndBindsTheMessageCandidateSet() {
        ToolDefinition definition = registry.find(MessageAssistantTools.TOOL_READ).orElseThrow();

        assertThat(definition.tool().annotations().readOnlyHint()).isTrue();
        assertThat(definition.tool().annotations().destructiveHint()).isFalse();
        assertThat(definition.referenceBindings())
                .containsExactly(Map.entry("messageRef", MessageCandidates.NAME));
    }

    // ---------- 夹具 ----------

    private static MessageResponse message(String bodyText, String bodyHtml) {
        return new MessageResponse(MESSAGE_ID, "inbound", "text", null, bodyText, bodyHtml, "chatapp",
                "李工", "我", Instant.parse("2026-09-20T10:00:00Z"), "delivered", 3, List.of());
    }
}
