package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.dto.response.ContactResponse;
import com.crmforlogistics.messagecenter.service.assistant.AssistantActionPolicy;
import com.crmforlogistics.messagecenter.service.assistant.ContactCandidates;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import com.crmforlogistics.messagecenter.service.channel.OutboundException;
import com.crmforlogistics.messagecenter.service.channel.OutboundMessageService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code message.send_email} 与 {@code message.send_chatapp} —— 助手第一次能造成<b>不可撤回</b>的后果。
 *
 * <h1>1. 「收件人不是参数」这件事必须被结构性地钉住</h1>
 * 本类最重要的一条断言不是「地址取对了」，而是<b>声明的参数集合里根本没有地址</b>。
 * 让模型给地址再校验，只能挡住格式不对的；给一个格式完全合法、却属于别人的地址是拦不住的，
 * 而邮件发出去收不回来。所以这里断言 {@code properties} 的 key 集合，而不是断言某个校验分支 ——
 * 前者在有人「顺手加个 to 参数方便调试」时会立刻红。
 *
 * <h1>2. 「必须确认」要看策略，不看注解</h1>
 * MCP 规范里注解只是给模型的提示，客户端不得据此做安全判断。所以「这两个工具要不要确认」
 * 的唯一权威是 {@link AssistantActionPolicy}，用例也就直接问它要答案 ——
 * 断言 {@code destructiveHint=true} 只能证明声明写得对，证明不了策略会拦。
 *
 * <h1>3. 失败要给出可执行的下一步</h1>
 * 三种失败对用户意味着三件不同的事：补档案、找管理员、<b>先确认再说、绝不能重发</b>。
 * 其中「结果未知」尤其不能被并进通用内部错误 —— 那会让模型自己去重试。
 */
class MessageSendAssistantToolsTest {

    private static final UUID USER = AssistantFixtures.USER;
    private static final UUID CONTACT = AssistantFixtures.CONTACT_ZHOU;
    private static final String REF = AssistantFixtures.CONTACT_ZHOU_REF;

    private final OutboundMessageService outbound = mock(OutboundMessageService.class);
    private final ContactService contacts = mock(ContactService.class);
    private final MessageSendAssistantTools tools = new MessageSendAssistantTools(outbound, contacts);
    private final ToolRegistry registry = new ToolRegistry(
            List.of(tools.messageSendEmailTool(), tools.messageSendChatAppTool()),
            new ToolInputValidator(), AssistantFixtures.objectMapper());

    @BeforeEach
    void theContactIsReadable() {
        when(contacts.getById(USER, CONTACT)).thenReturn(new ContactResponse(
                CONTACT, "周明", "张江物流 对接人", List.of("email"), null, null, 0, 0, List.of(), List.of()));
    }

    // ---------- 声明 ----------

    @Test
    void neitherToolHasARecipientParameterSoTheModelCannotInventOne() {
        assertThat(List.copyOf(propertiesOf(MessageSendAssistantTools.TOOL_SEND_EMAIL).keySet()))
                .as("地址只能由服务端从联系人档案解析；模型能给的只有「发给谁」")
                .containsExactlyInAnyOrder("contactRef", "subject", "body");
        assertThat(List.copyOf(propertiesOf(MessageSendAssistantTools.TOOL_SEND_CHATAPP).keySet()))
                .containsExactlyInAnyOrder("contactRef", "text");
    }

    @Test
    void bothToolsRequireConfirmationBecauseThePolicyConfirmsAnythingNotAllowlisted() {
        AssistantActionPolicy policy = new AssistantActionPolicy();

        assertThat(policy.decide(definition(MessageSendAssistantTools.TOOL_SEND_EMAIL)))
                .as("对外发送是不可撤回的，必须落确认卡片")
                .isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
        assertThat(policy.decide(definition(MessageSendAssistantTools.TOOL_SEND_CHATAPP)))
                .isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
    }

    @Test
    void theDeclarationIsWriteAndBindsTheContactCandidateSet() {
        for (String name : List.of(MessageSendAssistantTools.TOOL_SEND_EMAIL,
                MessageSendAssistantTools.TOOL_SEND_CHATAPP)) {
            ToolDefinition definition = definition(name);
            assertThat(definition.tool().annotations().readOnlyHint()).isFalse();
            assertThat(definition.tool().annotations().destructiveHint())
                    .as("发出去的邮件删不掉 —— 这是破坏性动作的典型情形")
                    .isTrue();
            assertThat(definition.tool().annotations().idempotentHint())
                    .as("重发一次就是真的多一封信，不是「重复调用无害」")
                    .isFalse();
            assertThat(definition.referenceBindings())
                    .containsExactly(Map.entry("contactRef", ContactCandidates.NAME));
        }
    }

    // ---------- 邮件 ----------

    @Test
    void emailRecipientBoundaryIsReturnedAsAStructuredToolError() {
        when(outbound.sendEmail(USER, CONTACT, "", "body"))
                .thenThrow(new OutboundException("EMAIL_RECIPIENT_NOT_FOUND", "Recipient not found"));
        ToolResult result = registry.invoke(MessageSendAssistantTools.TOOL_SEND_EMAIL, USER,
                Map.of("contactRef", REF, "body", "body"));
        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo("EMAIL_RECIPIENT_NOT_FOUND");
    }

    @Test
    void aDraftIsHandedToTheServiceWithTheCallersIdentityAndTheResolvedAddressComesBack() {
        when(outbound.sendEmail(USER, CONTACT, "报价确认", "价格按上次谈的走"))
                .thenReturn(new OutboundMessageService.EmailReceipt(
                        "msg-1", "me@mycorp.com", "zhou@example.com", "报价确认", "sent"));

        ToolResult result = registry.invoke(MessageSendAssistantTools.TOOL_SEND_EMAIL, USER,
                Map.of("contactRef", REF, "subject", "报价确认", "body", "价格按上次谈的走"));

        assertThat(result.isError()).isFalse();
        assertThat(result.message())
                .as("回话必须带实际地址：用户唯一能核对「发给谁了」的东西")
                .contains("周明").contains("zhou@example.com").contains("报价确认");
        assertThat(result.data()).containsEntry("to", "zhou@example.com")
                .containsEntry("subject", "报价确认").containsEntry("status", "sent");
        // 收件人由 service 按调用方身份解析 —— 不是工具自己拼出来的地址。
        verify(outbound).sendEmail(USER, CONTACT, "报价确认", "价格按上次谈的走");
    }

    @Test
    void theSubjectIsOptional() {
        when(outbound.sendEmail(any(), any(), any(), any()))
                .thenReturn(new OutboundMessageService.EmailReceipt("m", "me@x.com", "zhou@example.com", "", "sent"));

        ToolResult result = registry.invoke(MessageSendAssistantTools.TOOL_SEND_EMAIL, USER,
                Map.of("contactRef", REF, "body", "只有正文"));

        assertThat(result.isError()).isFalse();
        verify(outbound).sendEmail(USER, CONTACT, "", "只有正文");
    }

    @Test
    void aBodyOverTheDeclaredLimitIsRejectedBeforeAnythingIsSent() {
        ToolResult result = registry.invoke(MessageSendAssistantTools.TOOL_SEND_EMAIL, USER,
                Map.of("contactRef", REF, "body", "正".repeat(MessageSendAssistantTools.EMAIL_BODY_MAX_CHARS + 1)));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        verifyNoInteractions(outbound);
    }

    @Test
    void aContactWithoutAnAddressIsAnArgumentProblemNotASystemFailure() {
        when(outbound.sendEmail(any(), any(), any(), any()))
                .thenThrow(new OutboundException(OutboundException.RECIPIENT_MISSING, "没有地址"));

        ToolResult result = registry.invoke(MessageSendAssistantTools.TOOL_SEND_EMAIL, USER,
                Map.of("contactRef", REF, "body", "正文"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code())
                .as("报成 INTERNAL 会让模型换个收件人重试，而问题不在收件人")
                .isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("通讯录");
    }

    @Test
    void aChannelThatIsNotUsableIsUnavailableNotInvalid() {
        when(outbound.sendEmail(any(), any(), any(), any()))
                .thenThrow(new OutboundException(OutboundException.CHANNEL_UNAVAILABLE, "账号没配好"));

        ToolResult result = registry.invoke(MessageSendAssistantTools.TOOL_SEND_EMAIL, USER,
                Map.of("contactRef", REF, "body", "正文"));

        assertThat(result.code())
                .as("「管理员没开功能」与「你的参数不对」对用户的指引完全不同")
                .isEqualTo(ToolExecutionException.UNAVAILABLE);
    }

    @Test
    void anUnknownSendOutcomeMustNotBeReportedAsRetryable() {
        when(outbound.sendEmail(any(), any(), any(), any()))
                .thenThrow(new OutboundException(OutboundException.OUTCOME_UNKNOWN, "SMTP 收了但没记全"));

        ToolResult result = registry.invoke(MessageSendAssistantTools.TOOL_SEND_EMAIL, USER,
                Map.of("contactRef", REF, "body", "正文"));

        assertThat(result.code())
                .as("这是唯一一个「重试会造成第二次真实投递」的失败，必须有自己的码")
                .isEqualTo(ToolExecutionException.SEND_OUTCOME_UNKNOWN);
        assertThat(result.message()).contains("不要重发").contains("确认");
    }

    // ---------- chatapp ----------

    @Test
    void anAcceptedChatAppMessageIsReportedAsQueuedNeverAsDelivered() {
        when(outbound.sendChatApp(USER, CONTACT, "货已发出"))
                .thenReturn(new OutboundMessageService.ChatAppReceipt(
                        UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"), "pending", false));

        ToolResult result = registry.invoke(MessageSendAssistantTools.TOOL_SEND_CHATAPP, USER,
                Map.of("contactRef", REF, "text", "货已发出"));

        assertThat(result.isError()).isFalse();
        assertThat(result.message())
                .as("accept 只是入队。写成「已发送」会让用户以为对方看到了，投递失败时他也不会再去看状态")
                .contains("提交")
                .doesNotContain("已送达");
        assertThat(result.data()).containsEntry("status", "pending");
    }

    @Test
    void aChatAppChannelFailureIsUnavailable() {
        when(outbound.sendChatApp(any(), any(), any()))
                .thenThrow(new OutboundException(OutboundException.CHANNEL_UNAVAILABLE, "渠道不可用"));

        ToolResult result = registry.invoke(MessageSendAssistantTools.TOOL_SEND_CHATAPP, USER,
                Map.of("contactRef", REF, "text", "你好"));

        assertThat(result.code()).isEqualTo(ToolExecutionException.UNAVAILABLE);
    }

    // ---------- 引用形状 ----------

    @Test
    void aGroupReferenceFromTheConversationDomainCannotBeUsedAsARecipient() {
        ToolResult result = registry.invoke(MessageSendAssistantTools.TOOL_SEND_EMAIL, USER,
                Map.of("contactRef", AssistantFixtures.CONVERSATION_SEA, "body", "正文"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message())
                .as("企业微信群不是联系人 —— 说清形状不对，而不是让它去查一个不存在的档案")
                .contains("contactRef");
        verifyNoInteractions(outbound);
    }

    @Test
    void anInaccessibleContactIsAnAccessFailureAndTheAddressIsNeverResolved() {
        when(contacts.getById(USER, CONTACT)).thenThrow(new IllegalArgumentException("Contact not found"));

        ToolResult result = registry.invoke(MessageSendAssistantTools.TOOL_SEND_EMAIL, USER,
                Map.of("contactRef", REF, "body", "正文"));

        assertThat(result.code()).isEqualTo(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND);
        assertThat(result.message()).isEqualTo(ToolExecutionException.ACCESS_DENIED_MESSAGE);
        verifyNoInteractions(outbound);
    }

    // ---------- 夹具 ----------

    private ToolDefinition definition(String name) {
        return registry.find(name).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> propertiesOf(String name) {
        return (Map<String, Object>) definition(name).tool().inputSchema().get("properties");
    }
}
