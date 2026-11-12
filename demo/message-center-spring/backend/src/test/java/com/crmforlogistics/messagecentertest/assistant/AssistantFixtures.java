package com.crmforlogistics.messagecentertest.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import com.crmforlogistics.messagecenter.service.assistant.AssistantContext;
import com.crmforlogistics.messagecenter.service.assistant.ContactBriefProvider;
import com.crmforlogistics.messagecenter.service.assistant.ContactCandidateProvider;
import com.crmforlogistics.messagecenter.service.assistant.ContactCandidates;
import com.crmforlogistics.messagecenter.service.assistant.ConversationCandidateProvider;
import com.crmforlogistics.messagecenter.service.assistant.ConversationCandidates;
import com.crmforlogistics.messagecenter.service.assistant.TodoCandidates;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ContactAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ConversationAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.TodoAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolInputValidator;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.crmforlogistics.messagecenter.service.conversation.ConversationPreferenceService;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/**
 * 助手测试的公共夹具。
 *
 * <p>为什么用<b>真的</b> {@link ToolRegistry} + 真的 {@link TodoItemService} + 假 Mapper：
 * 解析器与编排层的被测行为里有一大半是「参数校验」与「工具名合法性」，
 * 而这两件事都发生在注册表与 service 里。mock 掉它们就等于把被测行为一起 mock 掉了 ——
 * 测试会全绿，而线上照旧允许一个 schema 不符的调用。
 *
 * <p>第二个域加入后，夹具里多了一组「会话候选」与两个会话工具。它们的存在方式与待办那组
 * <b>完全一致</b>（一组候选 + 两个声明），这正是候选集抽象要验证的事：
 * 加一个域不该让夹具变形，也不该让编排层变形。
 */
public final class AssistantFixtures {

    private AssistantFixtures() {
    }

    public static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID OTHER_USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID CONVERSATION = UUID.fromString("55555555-5555-4555-8555-555555555555");

    public static final UUID CONTACT_ZHOU = UUID.fromString("66666666-6666-4666-8666-666666666666");
    public static final UUID GROUP_SEA = UUID.fromString("77777777-7777-4777-8777-777777777777");

    /** 会话候选的 id 是复合键，形状与 {@code ConversationPreferenceService} 的 (targetType, targetId) 对齐。 */
    public static final String CONVERSATION_ZHOU =
            ConversationCandidates.Item.idOf(ConversationCandidates.TYPE_CONTACT, CONTACT_ZHOU);
    public static final String CONVERSATION_SEA =
            ConversationCandidates.Item.idOf(ConversationCandidates.TYPE_WECOM_GROUP, GROUP_SEA);

    /**
     * 联系人候选的 id。
     *
     * <p>刻意与 {@link #CONVERSATION_ZHOU} <b>是同一个值</b>：统一会话查询里
     * {@code type='CONTACT'} 那一支的 id 就是联系人 id，两域本来就是同一个 id 空间。
     * 起两个名字只是为了在各自的用例里读得通，值相同这件事本身也是被测行为之一。
     */
    public static final String CONTACT_ZHOU_REF = CONVERSATION_ZHOU;

    /** 与阶段 0.3 探针里用的候选 id 一致，便于两边对照。 */
    public static final String TODO_QUOTE = "11111111-1111-4111-8111-111111111111";
    public static final String TODO_MINUTES = "22222222-2222-4222-8222-222222222222";
    public static final String TODO_BUDGET = "33333333-3333-4333-8333-333333333333";

    /** 2026-09-21 是星期一。固定日期让「明天」的换算断言可写。 */
    public static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    public static ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /** 助手配置：默认只读轮数 3，与 {@code application.yml} 的默认值同源。 */
    public static AssistantConfig config() {
        return config(3);
    }

    public static AssistantConfig config(int maxReadTurns) {
        return new AssistantConfig(true, "https://api.deepseek.com", "secret", "deepseek-chat",
                30, 2000, 8, 8000, 600, maxReadTurns);
    }

    public static ToolRegistry registry(TodoItemService service) {
        TodoAssistantTools tools = new TodoAssistantTools(service);
        return new ToolRegistry(
                List.of(tools.todoSearchTool(), tools.todoCreateTool(), tools.todoCompleteTool(),
                        tools.todoDeleteTool(), tools.todoUpdateTool()),
                new ToolInputValidator(), objectMapper());
    }

    /**
     * 加上会话域两个工具的完整注册表。
     *
     * <p>与 {@link #registry} 分开，是为了让只关心待办的用例（解析器、策略、工具）不受影响：
     * 它们验的是「待办那条链路」，多两个无关工具只会让断言变脆。
     */
    public static ToolRegistry registryWithConversations(TodoItemService service,
                                                         ConversationCandidateProvider provider) {
        TodoAssistantTools todos = new TodoAssistantTools(service);
        ConversationAssistantTools conversations =
                new ConversationAssistantTools(provider, mock(ConversationPreferenceService.class));
        return new ToolRegistry(
                List.of(todos.todoSearchTool(), todos.todoCreateTool(), todos.todoCompleteTool(),
                        todos.todoDeleteTool(), todos.todoUpdateTool(),
                        conversations.conversationSearchTool(), conversations.conversationPinTool()),
                new ToolInputValidator(), objectMapper());
    }

    public static ToolRegistry registryWithMapper(TodoItemMapper mapper) {
        return registry(new TodoItemService(mapper));
    }

    public static ToolRegistry registryWithMockMapper() {
        return registryWithMapper(mock(TodoItemMapper.class));
    }

    /**
     * 三个域的完整注册表。
     *
     * <p>与 {@link #registryWithConversations} 分开的理由相同：只关心某一域的用例不该被
     * 别的域的工具影响断言。这个工厂存在本身也是「加一个域 = 加一组候选 + 两个工具声明」的证据 ——
     * 编排层、解析器、提示词这三处都没有为联系人域改过一行。
     */
    public static ToolRegistry registryWithContacts(TodoItemService service,
                                                    ConversationCandidateProvider conversations,
                                                    ContactCandidateProvider contacts,
                                                    ContactBriefProvider briefs) {
        TodoAssistantTools todos = new TodoAssistantTools(service);
        ConversationAssistantTools conversationTools =
                new ConversationAssistantTools(conversations, mock(ConversationPreferenceService.class));
        ContactAssistantTools contactTools = new ContactAssistantTools(contacts, briefs);
        return new ToolRegistry(
                List.of(todos.todoSearchTool(), todos.todoCreateTool(), todos.todoCompleteTool(),
                        todos.todoDeleteTool(), todos.todoUpdateTool(),
                        conversationTools.conversationSearchTool(), conversationTools.conversationPinTool(),
                        contactTools.contactSearchTool(), contactTools.contactBriefTool()),
                new ToolInputValidator(), objectMapper());
    }

    /** 三条未完成待办，覆盖「有时间」「无时间」两种形状。 */
    public static AssistantContext context() {
        return context(List.of(
                new AssistantContext.CandidateTodo(TODO_QUOTE, "2026-09-22", "15:00", "和张总确认报价"),
                new AssistantContext.CandidateTodo(TODO_MINUTES, "2026-09-25", null, "整理上周会议纪要"),
                new AssistantContext.CandidateTodo(TODO_BUDGET, "2026-10-08", "09:30", "提交季度预算初稿")));
    }

    public static AssistantContext context(List<AssistantContext.CandidateTodo> candidates) {
        return new AssistantContext("Asia/Shanghai", TODAY, "星期一",
                List.of(new TodoCandidates(70, candidates), conversationCandidates(), contactCandidates()));
    }

    public static AssistantContext emptyContext() {
        return context(List.of());
    }

    /** 两个会话候选，覆盖「联系人」与「企微群」两种类型、以及置顶/未读两种状态。 */
    public static ConversationCandidates conversationCandidates() {
        return new ConversationCandidates(ConversationCandidateProvider.LIMIT, List.of(
                new ConversationCandidates.Item(CONVERSATION_ZHOU, ConversationCandidates.TYPE_CONTACT,
                        "周明", List.of("wechat"), "2026-09-21T02:00:00Z", 2, true),
                new ConversationCandidates.Item(CONVERSATION_SEA, ConversationCandidates.TYPE_WECOM_GROUP,
                        "海运客户群", List.of("wecom"), "2026-09-20T08:00:00Z", 0, false)));
    }

    /**
     * 一个联系人候选（周明）。
     *
     * <p>刻意比会话候选<b>少一条</b>：会话那组里的企微群不是联系人，这正是「两域不能共用一组候选」
     * 的直观证据 —— 拿 {@link #CONVERSATION_SEA} 去调 {@code contact.brief} 应当被解析层拒掉。
     */
    public static ContactCandidates contactCandidates() {
        return new ContactCandidates(ContactCandidateProvider.LIMIT, List.of(
                new ContactCandidates.Item(CONTACT_ZHOU_REF, "周明", "张江物流 对接人")));
    }
}
