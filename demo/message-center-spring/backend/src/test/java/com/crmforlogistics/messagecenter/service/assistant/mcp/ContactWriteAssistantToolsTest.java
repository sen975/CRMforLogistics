package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.dto.request.ContactTagsRequest;
import com.crmforlogistics.messagecenter.dto.response.ContactResponse;
import com.crmforlogistics.messagecenter.service.assistant.ContactCandidates;
import com.crmforlogistics.messagecenter.service.contact.ContactAccessException;
import com.crmforlogistics.messagecenter.service.contact.ContactGroupService;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 联系人域四个写工具。
 *
 * <h2>这组测试防的是三类「缺了也不会红」的问题</h2>
 * <ol>
 *   <li><b>回话里吐内部标识</b>：{@code CONTACT:<uuid>} 对用户毫无意义。
 *       本项目的约定是「面向用户的回话不许吐内部标识」，所以名字随授权查询一起返回。</li>
 *   <li><b>写动作被声明成无害</b>：这四个全部是有损的（覆盖备注、覆盖资料、替换标签、清未读），
 *       注解必须说清楚，策略必须让它们走确认。注解错了不会有任何运行时症状。</li>
 *   <li><b>{@code set_tags} 的"替换"语义被当成"追加"</b>：底层是<b>先删后插</b>，
 *       描述与卡片若不说清楚，用户会在确认卡片上看到一份变短的标签清单却不知道少了什么。</li>
 * </ol>
 */
class ContactWriteAssistantToolsTest {

    private static final UUID USER = AssistantFixtures.USER;
    private static final UUID CONTACT = AssistantFixtures.CONTACT_ZHOU;
    private static final String REF = AssistantFixtures.CONTACT_ZHOU_REF;

    private final ContactGroupService groupService = mock(ContactGroupService.class);
    private final ContactService contactService = mock(ContactService.class);
    private final ContactWriteAssistantTools tools =
            new ContactWriteAssistantTools(groupService, contactService);
    private final ToolRegistry registry = new ToolRegistry(
            List.of(tools.contactUpdateRemarkTool(), tools.contactUpdateProfileTool(),
                    tools.contactSetTagsTool(), tools.contactMarkReadTool()),
            new ToolInputValidator(), AssistantFixtures.objectMapper());

    // ---------- 备注 ----------

    @Test
    void updateRemarkPassesTheTrimmedTextAndAnswersWithTheContactsName() {
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));

        ToolResult result = registry.invoke(ContactWriteAssistantTools.TOOL_UPDATE_REMARK, USER,
                Map.of("contactRef", REF, "remark", "  张江物流 对接人  "));

        ArgumentCaptor<String> remark = ArgumentCaptor.forClass(String.class);
        verify(groupService).updateRemark(eq(CONTACT), remark.capture(), eq(USER));
        assertThat(remark.getValue()).isEqualTo("张江物流 对接人");

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("周明").contains("张江物流 对接人");
        assertThat(result.message())
                .as("internal reference must not reach the user")
                .doesNotContain(REF);
        assertThat(result.data()).containsEntry("contactRef", REF).containsEntry("remark", "张江物流 对接人");
    }

    /** 空串表示"清除备注"，不能被当成"没给这个参数"。 */
    @Test
    void anEmptyRemarkClearsIt() {
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));

        ToolResult result = registry.invoke(ContactWriteAssistantTools.TOOL_UPDATE_REMARK, USER,
                Map.of("contactRef", REF, "remark", ""));

        verify(groupService).updateRemark(CONTACT, null, USER);
        assertThat(result.message()).contains("已清除");
        assertThat(result.data()).containsEntry("remark", null);
    }

    @Test
    void aMissingRemarkIsAMissingArgument() {
        ToolResult result = registry.invoke(ContactWriteAssistantTools.TOOL_UPDATE_REMARK, USER,
                Map.of("contactRef", REF));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.MISSING_ARGUMENT);
        verify(groupService, never()).updateRemark(any(), any(), any());
    }

    // ---------- 资料 ----------

    /** 只给 contactRef 不构成一次修改 —— 把这条约束放进 schema，模型才看得到。 */
    @Test
    void updateProfileRefusesACallWithNothingToChange() {
        ToolResult result = registry.invoke(ContactWriteAssistantTools.TOOL_UPDATE_PROFILE, USER,
                Map.of("contactRef", REF));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("至少要给出一个要修改的字段");
        verify(groupService, never()).updateProfile(any(), any(), any(), any());
    }

    @Test
    void theProfileToolDescriptionRedirectsRemarkRequestsToTheRemarkTool() {
        // 2026-09-23 事故：模型把「改备注」当成 update_profile 覆盖了渠道同步来的昵称。
        // 模型只看得到工具声明，所以纠偏必须写在 description 里 —— 这里钉住它不许被删。
        String profile = registry.find(ContactWriteAssistantTools.TOOL_UPDATE_PROFILE).orElseThrow()
                .tool().description();
        assertThat(profile).contains("contact.update_remark").contains("昵称");

        String remark = registry.find(ContactWriteAssistantTools.TOOL_UPDATE_REMARK).orElseThrow()
                .tool().description();
        assertThat(remark).contains("不会改动他的显示名");
    }

    @Test
    void updateProfilePassesOnlyTheFieldsThatWereGiven() {
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));

        ToolResult result = registry.invoke(ContactWriteAssistantTools.TOOL_UPDATE_PROFILE, USER,
                Map.of("contactRef", REF, "roleTitle", "采购总监"));

        verify(groupService).updateProfile(CONTACT, null, "采购总监", USER);
        assertThat(result.data()).containsEntry("roleTitle", "采购总监").doesNotContainKey("displayName");
    }

    /** 空串表示"清除职务"，与"没给"是两回事。 */
    @Test
    void anEmptyRoleTitleClearsItRatherThanMeaningNotGiven() {
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));

        registry.invoke(ContactWriteAssistantTools.TOOL_UPDATE_PROFILE, USER,
                Map.of("contactRef", REF, "roleTitle", ""));

        verify(groupService).updateProfile(CONTACT, null, "", USER);
    }

    // ---------- 标签 ----------

    @Test
    void setTagsPassesTheWholeListBecauseTheServiceReplacesRatherThanAppends() {
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));

        ToolResult result = registry.invoke(ContactWriteAssistantTools.TOOL_SET_TAGS, USER,
                Map.of("contactRef", REF, "tags", List.of("潜在客户", "华东")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ContactTagsRequest.ContactTagInput>> tags =
                ArgumentCaptor.forClass(List.class);
        verify(groupService).updateTags(eq(CONTACT), tags.capture(), eq(USER));
        assertThat(tags.getValue()).extracting(ContactTagsRequest.ContactTagInput::name)
                .containsExactly("潜在客户", "华东");

        assertThat(result.message()).contains("替换为").contains("潜在客户");
        assertThat(result.data()).containsEntry("tags", List.of("潜在客户", "华东"));
    }

    @Test
    void anEmptyTagListMeansClearAllAndSaysSo() {
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));

        ToolResult result = registry.invoke(ContactWriteAssistantTools.TOOL_SET_TAGS, USER,
                Map.of("contactRef", REF, "tags", List.of()));

        verify(groupService).updateTags(eq(CONTACT), eq(List.of()), eq(USER));
        assertThat(result.message()).contains("已清空");
    }

    @Test
    void aNonArrayTagValueIsRejectedByTheSchemaValidator() {
        ToolResult result = registry.invoke(ContactWriteAssistantTools.TOOL_SET_TAGS, USER,
                Map.of("contactRef", REF, "tags", "潜在客户"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        verify(groupService, never()).updateTags(any(), anyList(), any());
    }

    // ---------- 标记已读 ----------

    @Test
    void markReadGoesThroughTheContactScopedService() {
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));

        ToolResult result = registry.invoke(ContactWriteAssistantTools.TOOL_MARK_READ, USER,
                Map.of("contactRef", REF));

        verify(contactService).markAsRead(USER, CONTACT);
        assertThat(result.message()).contains("周明").contains("全部消息");
    }

    // ---------- 越权与形状 ----------

    @Test
    void anOutOfScopeContactIsAnAccessFailureWithoutLeakingWhy() {
        when(contactService.getById(eq(USER), eq(CONTACT)))
                .thenThrow(new IllegalArgumentException("Contact not found: " + CONTACT));

        ToolResult result = registry.invoke(ContactWriteAssistantTools.TOOL_UPDATE_REMARK, USER,
                Map.of("contactRef", REF, "remark", "x"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND);
        assertThat(result.message()).isEqualTo(ToolExecutionException.ACCESS_DENIED_MESSAGE);
        verify(groupService, never()).updateRemark(any(), any(), any());
    }

    @Test
    void profileValidationFailureIsNotMisreportedAsAuthorizationFailure() {
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));
        org.mockito.Mockito.doThrow(new IllegalArgumentException("roleTitle must be <= 100 characters"))
                .when(groupService).updateProfile(eq(CONTACT), any(), any(), eq(USER));

        ToolResult result = registry.invoke(ContactWriteAssistantTools.TOOL_UPDATE_PROFILE, USER,
                Map.of("contactRef", REF, "roleTitle", "测试"));

        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).doesNotContain("roleTitle must be <=");
    }

    @Test
    void accessRevokedAfterTargetLookupRemainsNonEnumerating() {
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));
        org.mockito.Mockito.doThrow(new ContactAccessException())
                .when(groupService).updateRemark(eq(CONTACT), any(), eq(USER));

        ToolResult result = registry.invoke(ContactWriteAssistantTools.TOOL_UPDATE_REMARK, USER,
                Map.of("contactRef", REF, "remark", "新备注"));

        assertThat(result.code()).isEqualTo(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND);
        assertThat(result.message()).isEqualTo(ToolExecutionException.ACCESS_DENIED_MESSAGE);
    }

    @Test
    void aGroupReferenceIsNotAContactReference() {
        ToolResult result = registry.invoke(ContactWriteAssistantTools.TOOL_MARK_READ, USER,
                Map.of("contactRef", AssistantFixtures.CONVERSATION_SEA));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        verify(contactService, never()).markAsRead(any(), any());
    }

    @Test
    void aServiceLevelAssemblyFailureBecomesAnInternalErrorNotAUserInputComplaint() {
        when(contactService.getById(USER, CONTACT)).thenReturn(contact("周明", null));
        doThrow(new IllegalStateException("Contact tag support is unavailable"))
                .when(groupService).updateTags(eq(CONTACT), anyList(), eq(USER));

        ToolResult result = registry.invoke(ContactWriteAssistantTools.TOOL_SET_TAGS, USER,
                Map.of("contactRef", REF, "tags", List.of("潜在客户")));

        assertThat(result.code()).isEqualTo(ToolExecutionException.INTERNAL);
        assertThat(result.message())
                .as("装配问题是服务端的事，不该让用户以为是自己参数写错了")
                .doesNotContain("Contact tag support");
    }

    // ---------- 声明 ----------

    @Test
    void allFourAreDeclaredDestructiveAndBoundToTheContactCandidateSet() {
        for (String name : List.of(ContactWriteAssistantTools.TOOL_UPDATE_REMARK,
                ContactWriteAssistantTools.TOOL_UPDATE_PROFILE,
                ContactWriteAssistantTools.TOOL_SET_TAGS,
                ContactWriteAssistantTools.TOOL_MARK_READ)) {
            ToolDefinition definition = registry.find(name).orElseThrow();
            assertThat(definition.tool().annotations().readOnlyHint()).as(name).isFalse();
            assertThat(definition.tool().annotations().destructiveHint())
                    .as(name + " 会覆盖或清除已有数据，注解必须说出来")
                    .isTrue();
            assertThat(definition.referenceBindings())
                    .as(name)
                    .containsExactly(Map.entry("contactRef", ContactCandidates.NAME));
        }
    }

    // ---------- 夹具 ----------

    private static ContactResponse contact(String displayName, String remark) {
        return new ContactResponse(CONTACT, displayName, remark, List.of("wechat"), null,
                null, 0, 0, List.of(), List.of());
    }
}
