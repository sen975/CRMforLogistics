package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.dto.response.ContactResponse;
import com.crmforlogistics.messagecenter.dto.response.TimelineResponse;
import com.crmforlogistics.messagecenter.service.assistant.ContactCandidates;
import com.crmforlogistics.messagecenter.service.assistant.MessageCandidates;
import com.crmforlogistics.messagecenter.service.callrecord.CallRecordException;
import com.crmforlogistics.messagecenter.service.callrecord.ContactTimelineService;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 跨渠道往来时间线：三条判据，其中最要紧的是第一条。
 *
 * <h1>1. 正文不许出边界（这组用例存在的理由）</h1>
 * {@code ContactTimelineService} 的返回里，消息那一条的 payload 带
 * {@code text = message.bodyText}，也就是<b>客户原话</b>。
 *
 * <p>注意这里的判据在 2026-09-23 变过一次口径，但<b>结论没变</b>：
 * 原来（2026-09-22 选项 b）是「原文一律不出边界」；现在是
 * 「允许原文进上下文，但只给<b>用户点名要的那一份</b>」。
 * 这条时间线一次回 20 条，20 段正文既不是用户要看的那一份、又会把真正的明细挤出 4000 字符预算，
 * 所以它照样必须剥掉 —— 而 {@code message.read} 一次只取一条，那边带原文才是对的。
 * 抄了服务层返回也能"功能正常、测试全绿"，唯一的差别是客户原话被发给了模型供应商，
 * 而这件事<b>没有任何机制会发现</b>。所以这里不验"行为对不对"，而是逐字段钉住输出的键集合，
 * 并且<b>先证明夹具里确实有正文</b> —— 否则「输出里没有 text」可能只是因为输入里也没有，
 * 那这条用例等于什么都没验。
 *
 * <p><b>另一半结论是 2026-09-23 新增的</b>：剥掉正文之后必须<b>补上</b> {@code messageRef}
 * 并产出 message 候选窗口，否则「先看时间线、再取某一条的原文」这条链路根本走不通。
 * 只剥不加不是一个保守的选择，而是一个缺失的功能（见下面那三条用例）。
 *
 * <h1>2. 截断必须说出来</h1>
 * 不说的话模型会把「只给了最近 20 条」读成「一共只有 20 条往来」。
 *
 * <h1>3. 「这个人不在你的范围内」不能被报成系统故障</h1>
 * 这条时间线用 {@code CallRecordException(CONTACT_NOT_FOUND)} 表达它（不是
 * {@code IllegalArgumentException}）。不专门转译，它会冒到注册表的兜底 catch，
 * 变成一句「执行失败，请稍后再试」，用户会以为系统坏了并反复重试。
 */
class ContactTimelineAssistantToolsTest {

    private static final UUID USER = AssistantFixtures.USER;
    private static final UUID CONTACT = AssistantFixtures.CONTACT_ZHOU;
    private static final String REF = AssistantFixtures.CONTACT_ZHOU_REF;

    /**
     * 时间线上那一条消息的 id。
     *
     * <p>用真的 UUID 而不是 {@code "m-1"} 这样的占位串：{@code messageRef} 只能由合法 UUID 生成
     * （畸形 id 会被 {@code messageRefOf} 判成"没有 ref"），用占位串会让"有没有 ref"
     * 这条断言永远看不到 ref，从而静默变成一条空断言。
     */
    private static final UUID MESSAGE_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID CALL_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    private final ContactTimelineService timelineService = mock(ContactTimelineService.class);
    private final ContactService contactService = mock(ContactService.class);
    private final ContactTimelineAssistantTools tools =
            new ContactTimelineAssistantTools(timelineService, contactService);
    private final ToolRegistry registry = new ToolRegistry(
            List.of(tools.contactTimelineTool()),
            new ToolInputValidator(), AssistantFixtures.objectMapper());

    @Test
    void theToolReturnsNoMessageTextEvenWhenTheServiceProvidesIt() {
        when(timelineService.timeline(eq(USER), eq(CONTACT), any(), anyInt()))
                .thenReturn(responseWithText());
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", "张江物流 对接人"));

        ToolResult result = registry.invoke(ContactTimelineAssistantTools.TOOL_TIMELINE, USER,
                Map.of("contactRef", REF));

        assertThat(result.isError()).isFalse();

        // 先证明夹具里真的有正文：否则下面的断言可能只是因为"输入本来就没给"。
        TimelineResponse raw = responseWithText();
        assertThat(raw.items().get(0).payload())
                .as("夹具必须真的带 text，否则这条用例什么都没验")
                .containsEntry("text", "这批货能不能便宜点，我们长期合作");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).keySet())
                .as("message 那一条只允许这四个键 + messageRef：多一个 text 就是把客户原话送出去了")
                .containsExactlyInAnyOrder("type", "occurredAt", "direction", "status", "messageRef");
        assertThat(items.get(1).keySet())
                .as("callRecord 那一条同理，且不含任何转写、也不含 ref")
                .containsExactlyInAnyOrder("type", "occurredAt", "direction",
                        "durationSeconds", "state", "attempts");

        // 整个返回序列化出来的文本里都不许出现那句话。
        assertThat(result.data().toString())
                .as("正文一个字符都不该出现在这条结果里 —— 它会原样进观察结果发给模型供应商")
                .doesNotContain("这批货能不能便宜点");
    }

    @Test
    void theToolAlsoSaysOutLoudThatContentIsNotIncluded() {
        when(timelineService.timeline(eq(USER), eq(CONTACT), any(), anyInt()))
                .thenReturn(responseWithText());
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));

        ToolResult result = registry.invoke(ContactTimelineAssistantTools.TOOL_TIMELINE, USER,
                Map.of("contactRef", REF));

        assertThat(result.message()).contains("周明").contains("1 条消息").contains("1 通电话")
                .as("必须明说没有正文，否则模型会自己补一段出来")
                .contains("没有消息正文与通话内容");
    }

    @Test
    void truncationIsStatedNotSilentlyDropped() {
        // itemCount=87 而只给了 2 条：这正是"服务端截断了但没说"的那种返回。
        when(timelineService.timeline(eq(USER), eq(CONTACT), any(), anyInt()))
                .thenReturn(responseWithText(87));
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));

        ToolResult result = registry.invoke(ContactTimelineAssistantTools.TOOL_TIMELINE, USER,
                Map.of("contactRef", REF));

        assertThat(result.message())
                .as("不说的话模型会把「只给了 20 条」读成「一共只有 20 条」")
                .contains("共 87 条历史往来")
                .contains("只显示最近 2 条");
        assertThat(result.data()).containsEntry("messageCount", 1).containsEntry("callCount", 1);
    }

    @Test
    void anEmptyTimelineIsANormalAnswerNotAnError() {
        when(timelineService.timeline(eq(USER), eq(CONTACT), any(), anyInt()))
                .thenReturn(new TimelineResponse(List.of(), null, 0, "rev"));
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));

        ToolResult result = registry.invoke(ContactTimelineAssistantTools.TOOL_TIMELINE, USER,
                Map.of("contactRef", REF));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("还没有可显示的往来记录");
    }

    @Test
    void anOutOfScopeContactIsReportedAsAnArgumentProblemNotAsASystemFailure() {
        when(timelineService.timeline(eq(USER), eq(CONTACT), any(), anyInt()))
                .thenThrow(new CallRecordException("CONTACT_NOT_FOUND", 404,
                        "Contact does not exist", false));

        ToolResult result = registry.invoke(ContactTimelineAssistantTools.TOOL_TIMELINE, USER,
                Map.of("contactRef", REF));

        assertThat(result.isError()).isTrue();
        assertThat(result.code())
                .as("不转译的话它会变成 INTERNAL，用户会以为系统坏了并反复重试")
                .isEqualTo(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND);
        assertThat(result.message()).isEqualTo(ToolExecutionException.ACCESS_DENIED_MESSAGE);
    }

    @Test
    void aMalformedReferenceIsRejectedWithoutTouchingTheService() {
        ToolResult result = registry.invoke(ContactTimelineAssistantTools.TOOL_TIMELINE, USER,
                Map.of("contactRef", AssistantFixtures.CONVERSATION_SEA));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
    }

    @Test
    void theDeclarationIsReadOnlyAndBindsTheContactCandidateSet() {
        ToolDefinition definition = registry.find(ContactTimelineAssistantTools.TOOL_TIMELINE).orElseThrow();

        assertThat(definition.tool().annotations().readOnlyHint()).isTrue();
        assertThat(definition.tool().annotations().destructiveHint()).isFalse();
        assertThat(definition.referenceBindings())
                .containsExactly(Map.entry("contactRef", ContactCandidates.NAME));
    }

    // ---------- 它同时是 message.read 的入口（2026-09-23） ----------

    /**
     * 时间线上的每条消息带一个 ref，<b>并且这组 ref 构成本轮的候选窗口</b>。
     *
     * <p>两件事必须一起成立。只给 ref 不给候选 ⇒ 下一轮引用它会被解析器拒掉，
     * 于是「先看时间线、再取原文」这条链路在第一步之后就断了，而失败点离原因很远
     * （模型看到的是「不在当前候选清单中」，而它明明是从上一轮结果里抄的）。
     */
    @Test
    void aTimelineMessageBecomesAReferenceTheNextRoundCanCite() {
        when(timelineService.timeline(eq(USER), eq(CONTACT), any(), anyInt()))
                .thenReturn(responseWithText());
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));

        ToolResult result = registry.invoke(ContactTimelineAssistantTools.TOOL_TIMELINE, USER,
                Map.of("contactRef", REF));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertThat(items.get(0)).containsEntry("messageRef", MessageCandidates.idOf(MESSAGE_ID));
        assertThat(items.get(1))
                .as("通话域还没有可读工具（call.read 未实现），所以它不该拿到一个没有消费方的 ref")
                .doesNotContainKey("messageRef");

        assertThat(result.candidates()).isInstanceOf(MessageCandidates.class);
        MessageCandidates candidates = (MessageCandidates) result.candidates();
        assertThat(candidates.items()).extracting(MessageCandidates.Item::id)
                .as("候选里的 ref 必须与结果里那个逐字相同 —— 两处各算一遍早晚会漂移")
                .containsExactly(MessageCandidates.idOf(MESSAGE_ID));
        assertThat(candidates.items().get(0).direction()).isEqualTo("inbound");
        assertThat(candidates.items().get(0).status()).isEqualTo("delivered");
    }

    /**
     * 一条消息都没有时不替换候选窗口。
     *
     * <p>与 {@code conversation.search} 的空结果同一条理由：这次没产出候选，
     * 不该把别处刚发现的那一批抹掉。区别是这里更容易写错 ——
     * 顺手传一个空的 {@code MessageCandidates} 也能"编译通过、看起来对称"，
     * 而它的效果是<strong>把上一轮的窗口清空</strong>。
     */
    @Test
    void aTimelineWithoutMessagesDoesNotWipeTheMessageWindow() {
        when(timelineService.timeline(eq(USER), eq(CONTACT), any(), anyInt()))
                .thenReturn(responseWithOnlyCalls());
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));

        ToolResult result = registry.invoke(ContactTimelineAssistantTools.TOOL_TIMELINE, USER,
                Map.of("contactRef", REF));

        assertThat(result.isError()).isFalse();
        assertThat(result.candidates()).isNull();
    }

    /**
     * 畸形 id 不产出 ref，也不产出候选。
     *
     * <p>服务层在 id 为空时会给空串（{@code sortId = message.getId() != null ? … : ""}）。
     * 直接拼一个 {@code MESSAGE:} 出去的话，模型照抄之后会在提问路径被拒，
     * 而用户看到的是「它老说找不到那条消息」—— 一条没有原因的失败。
     */
    @Test
    void aMalformedMessageIdProducesNoReferenceAndNoCandidate() {
        when(timelineService.timeline(eq(USER), eq(CONTACT), any(), anyInt()))
                .thenReturn(responseWithRawSortId(""));
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));

        ToolResult result = registry.invoke(ContactTimelineAssistantTools.TOOL_TIMELINE, USER,
                Map.of("contactRef", REF));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertThat(items.get(0)).doesNotContainKey("messageRef");
        assertThat(result.candidates()).isNull();
    }

    // ---------- 夹具 ----------

    private static TimelineResponse responseWithText() {
        return responseWithText(2);
    }

    /**
     * 一条消息 + 一通电话。
     *
     * <p>消息那条的 payload 刻意包含 {@code text}（正文）与 {@code channel} ——
     * 前者必须被工具剥掉，后者不在白名单里所以也不该出现。这两个字段是"照抄服务层返回"
     * 这个错误写法的指纹。
     */
    private static TimelineResponse responseWithText(int itemCount) {
        return response(itemCount, MESSAGE_ID.toString(), true);
    }

    private static TimelineResponse responseWithOnlyCalls() {
        return response(1, MESSAGE_ID.toString(), false);
    }

    private static TimelineResponse responseWithRawSortId(String rawSortId) {
        return response(1, rawSortId, true);
    }

    private static TimelineResponse response(int itemCount, String messageSortId, boolean withMessage) {
        List<TimelineResponse.TimelineItem> items = new ArrayList<>();

        if (withMessage) {
            Map<String, Object> messagePayload = new LinkedHashMap<>();
            messagePayload.put("direction", "inbound");
            messagePayload.put("channel", "");
            messagePayload.put("text", "这批货能不能便宜点，我们长期合作");
            messagePayload.put("status", "delivered");
            items.add(new TimelineResponse.TimelineItem("message",
                    Instant.parse("2026-09-20T10:00:00Z"), messageSortId, messagePayload));
        }

        Map<String, Object> callPayload = new LinkedHashMap<>();
        callPayload.put("id", "c-1");
        callPayload.put("direction", "outbound");
        callPayload.put("phonePointId", "pp-1");
        callPayload.put("durationSeconds", 320);
        callPayload.put("state", "COMPLETED");
        callPayload.put("errorCode", "");
        callPayload.put("errorMessage", "");
        callPayload.put("errorRetryable", false);
        callPayload.put("attempts", 1);
        callPayload.put("version", 2);
        // 转写不进 payload 本身，但这里显式放一个含"原文"味道的字段，证明白名单搬运在起作用。
        callPayload.put("transcript", "他说下周给我答复");
        items.add(new TimelineResponse.TimelineItem("callRecord",
                Instant.parse("2026-09-19T09:00:00Z"), CALL_ID.toString(), callPayload));

        return new TimelineResponse(items, null, itemCount, "rev-1");
    }

    private static ContactResponse contact(String displayName, String remark) {
        return new ContactResponse(CONTACT, displayName, remark, List.of("wechat"), null,
                null, 0, 0, List.of(), List.of());
    }
}
