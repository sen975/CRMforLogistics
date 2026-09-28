package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.service.assistant.AssistantActionPolicy;
import com.crmforlogistics.messagecenter.service.assistant.ContactCandidates;
import com.crmforlogistics.messagecenter.service.contactmemory.ContactMemoryRecomputeService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code contact.refresh_memory} —— 联系人记忆域目前唯一的动作。
 *
 * <h1>1. 「必须确认」看策略，不看注解</h1>
 * 权威是 {@link AssistantActionPolicy}（MCP 注解只是给模型的提示，客户端不得据此做安全判断），
 * 所以用例直接问策略要答案。它会让模型改写已有的画像与标签，没有任何理由免确认。
 *
 * <h1>2. 最要紧的一条：不许承诺「已经更新」</h1>
 * 重算是后台任务、受处理窗口约束。工具说明与回话里只要出现「画像已经更新好了」，
 * 用户就会以为点完即生效 —— 而那要等到下一个处理窗口。所以这里同时钉住
 * <b>说明</b>写了这句话、<b>回话</b>没有写成已更新。
 *
 * <h1>3. 「没有新内容」必须与「已提交」分开说</h1>
 * 前者一个字节都没写库。说成「已提交」，用户会一直等一个永远不会发生的变化。
 */
class ContactMemoryAssistantToolsTest {

    private static final UUID USER = AssistantFixtures.USER;
    private static final UUID CONTACT = AssistantFixtures.CONTACT_ZHOU;
    private static final String REF = AssistantFixtures.CONTACT_ZHOU_REF;

    private final ContactMemoryRecomputeService memory = mock(ContactMemoryRecomputeService.class);
    private final ContactMemoryAssistantTools tools = new ContactMemoryAssistantTools(memory);
    private final ToolRegistry registry = new ToolRegistry(
            List.of(tools.contactRefreshMemoryTool()),
            new ToolInputValidator(), AssistantFixtures.objectMapper());

    // ---------- 声明 ----------

    @Test
    void theToolRequiresConfirmationBecauseItLetsTheModelRewriteGeneratedContent() {
        AssistantActionPolicy policy = new AssistantActionPolicy();

        assertThat(policy.decide(definition(ContactMemoryAssistantTools.TOOL_REFRESH_MEMORY)))
                .as("它会改写已有的画像与标签，且要花一次模型调用")
                .isEqualTo(AssistantActionPolicy.Decision.CONFIRM);
    }

    @Test
    void theOnlyParameterIsTheContactReferenceAndItIsBoundToTheContactCandidates() {
        assertThat(List.copyOf(propertiesOf(ContactMemoryAssistantTools.TOOL_REFRESH_MEMORY).keySet()))
                .as("重算哪个联系人是模型唯一能决定的；其余参数（版本、游标、渠道）都该由服务端定")
                .containsExactly("contactRef");
        assertThat(definition(ContactMemoryAssistantTools.TOOL_REFRESH_MEMORY).referenceBindings())
                .containsExactly(Map.entry("contactRef", ContactCandidates.NAME));
    }

    @Test
    void theDescriptionSaysOutLoudThatSubmittingDoesNotMeanUpdated() {
        String description = definition(ContactMemoryAssistantTools.TOOL_REFRESH_MEMORY)
                .tool().description();

        assertThat(description)
                .as("删掉这句话，模型就会对用户说「已经重算好了」—— 而实际要等到下一个处理窗口")
                .contains("不代表已经更新")
                .contains("稍后");
        assertThat(definition(ContactMemoryAssistantTools.TOOL_REFRESH_MEMORY).tool().annotations().readOnlyHint())
                .isFalse();
        assertThat(definition(ContactMemoryAssistantTools.TOOL_REFRESH_MEMORY).tool().annotations().openWorldHint())
                .as("它只动本系统自己的数据，不对外发东西")
                .isFalse();
    }

    // ---------- 四种结果 ----------

    @Test
    void aSubmittedRecomputeSaysSubmittedAndNeverSaysUpdated() {
        when(memory.requestRecompute(USER, CONTACT))
                .thenReturn(Optional.of(ContactMemoryRecomputeService.Outcome.SUBMITTED));

        ToolResult result = registry.invoke(ContactMemoryAssistantTools.TOOL_REFRESH_MEMORY, USER,
                Map.of("contactRef", REF));

        assertThat(result.isError()).isFalse();
        assertThat(result.message())
                .contains("已提交")
                .doesNotContain("已经更新")
                .doesNotContain("已更新");
        assertThat(result.data())
                .containsEntry("contactRef", REF)
                .containsEntry("status", "SUBMITTED");
    }

    @Test
    void anAlreadyQueuedContactIsNotSubmittedAgain() {
        when(memory.requestRecompute(USER, CONTACT))
                .thenReturn(Optional.of(ContactMemoryRecomputeService.Outcome.ALREADY_PENDING));

        ToolResult result = registry.invoke(ContactMemoryAssistantTools.TOOL_REFRESH_MEMORY, USER,
                Map.of("contactRef", REF));

        assertThat(result.message()).contains("队列").doesNotContain("已提交");
        assertThat(result.data()).containsEntry("status", "ALREADY_PENDING");
    }

    @Test
    void aContactBeingProcessedIsReportedAsInProgress() {
        when(memory.requestRecompute(USER, CONTACT))
                .thenReturn(Optional.of(ContactMemoryRecomputeService.Outcome.IN_PROGRESS));

        ToolResult result = registry.invoke(ContactMemoryAssistantTools.TOOL_REFRESH_MEMORY, USER,
                Map.of("contactRef", REF));

        assertThat(result.message()).contains("正在重算");
    }

    @Test
    void whenThereIsNothingNewTheAnswerSaysNothingWillChange() {
        when(memory.requestRecompute(USER, CONTACT))
                .thenReturn(Optional.of(ContactMemoryRecomputeService.Outcome.NOTHING_NEW));

        ToolResult result = registry.invoke(ContactMemoryAssistantTools.TOOL_REFRESH_MEMORY, USER,
                Map.of("contactRef", REF));

        assertThat(result.isError()).isFalse();
        assertThat(result.message())
                .as("说成「已提交」会让用户等一个永远不会发生的变化")
                .contains("没有新的往来内容")
                .contains("不会产生任何变化")
                .doesNotContain("已提交");
    }

    // ---------- 拒绝 ----------

    @Test
    void aContactWhoseMemoryIsNotYoursIsRefusedWithoutTouchingTheQueue() {
        when(memory.requestRecompute(USER, CONTACT)).thenReturn(Optional.empty());

        ToolResult result = registry.invoke(ContactMemoryAssistantTools.TOOL_REFRESH_MEMORY, USER,
                Map.of("contactRef", REF));

        assertThat(result.isError()).isTrue();
        assertThat(result.code())
                .as("参数没写错，但记忆不归当前用户；不能报成内部错误或暴露归属")
                .isEqualTo(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND);
        assertThat(result.message()).isEqualTo(ToolExecutionException.ACCESS_DENIED_MESSAGE);
    }

    @Test
    void aMissingOrMalformedReferenceNeverReachesTheService() {
        ToolResult missing = registry.invoke(ContactMemoryAssistantTools.TOOL_REFRESH_MEMORY, USER, Map.of());
        assertThat(missing.code()).isEqualTo(ToolExecutionException.MISSING_ARGUMENT);

        ToolResult malformed = registry.invoke(ContactMemoryAssistantTools.TOOL_REFRESH_MEMORY, USER,
                Map.of("contactRef", AssistantFixtures.CONVERSATION_SEA));
        assertThat(malformed.code())
                .as("企业微信群不是联系人 —— 说清形状不对，而不是拿它去查一个不存在的档案")
                .isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(malformed.message()).contains("contactRef");

        verifyNoInteractions(memory);
    }

    @Test
    void theCallersIdentityIsWhatGetsPassedToTheService() {
        when(memory.requestRecompute(any(), any()))
                .thenReturn(Optional.of(ContactMemoryRecomputeService.Outcome.SUBMITTED));

        registry.invoke(ContactMemoryAssistantTools.TOOL_REFRESH_MEMORY, USER, Map.of("contactRef", REF));

        verify(memory).requestRecompute(USER, CONTACT);
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
