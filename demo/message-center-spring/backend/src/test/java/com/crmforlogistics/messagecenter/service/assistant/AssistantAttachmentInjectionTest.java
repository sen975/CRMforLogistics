package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import com.crmforlogistics.messagecenter.mapper.AssistantPendingActionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ChatAppTemplateAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolInputValidator;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.crmforlogistics.messagecenter.service.channel.OutboundMessageService;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppSharedTemplateCatalogService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaCatalogService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaIngestService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 用户「当面递来一张图」这一路，从请求体走到模型面前。
 *
 * <h1>为什么要单独一个类</h1>
 * 附件不是检索出来的候选，它<b>只在这条缝上存在</b>：请求体 → 编排入口 →
 * {@link TemplateMediaProvider} → {@link AssistantContext} → 提示词 → 解析器。
 * 单独看任何一段都是对的，缺的是「这一整条缝真的连通了吗」——
 * 而这条缝的断法都很安静：注入点写错用户 id（归属失效）、
 * 参数忘了往下传（图永远到不了模型）、候选注入晚了（解析器按上一轮的清单判）。
 * 所以本类只断一件事：<b>模型最后看到的清单里有没有这张图，以及它引用之后会怎样</b>。
 *
 * <h1>断言为什么读提示词</h1>
 * 候选集进入模型<b>只有提示词这一条通道</b>（{@code AssistantPromptBuilder} 把每组候选
 * 渲染成一节）。所以「提示词里有这张图的候选 id」就是「模型能引用它」。
 * 反过来，用一个不在候选里的 id 去调用，解析器必须拒 —— 那半条在本类第二个用例里，
 * 两者合起来才说明「这张图不是靠运气飘进去的」。
 */
class AssistantAttachmentInjectionTest {

    private static final AssistantConfig CONFIG = AssistantFixtures.config();

    private static final UUID USER = AssistantFixtures.USER;

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final String ACCOUNT_REF = ChatAppAccountCandidates.idOf(ACCOUNT);

    private static final UUID ATTACHED = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static final UUID OTHER_ASSET = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private final TodoItemMapper todoMapper = mock(TodoItemMapper.class);
    private final AssistantPendingActionMapper pendingMapper = mock(AssistantPendingActionMapper.class);
    private final AssistantContextBuilder contextBuilder = mock(AssistantContextBuilder.class);
    private final AssistantModelClient modelClient = mock(AssistantModelClient.class);
    private final AssistantAuditService audit = mock(AssistantAuditService.class);
    private final AssistantConversationLogService conversationLog = mock(AssistantConversationLogService.class);

    private final TemplateMediaProvider mediaProvider = mock(TemplateMediaProvider.class);
    private final ChatAppAccountProvider templateAccounts = mock(ChatAppAccountProvider.class);
    private final WhatsAppTemplateApplicationService applications =
            mock(WhatsAppTemplateApplicationService.class);

    private AssistantConversationService service;

    @BeforeEach
    void setUp() {
        ChatAppTemplateAssistantTools templateTools = new ChatAppTemplateAssistantTools(
                templateAccounts,
                mock(WhatsAppSharedTemplateCatalogService.class),
                applications,
                mediaProvider,
                mock(WhatsAppTemplateMediaCatalogService.class),
                mock(WhatsAppTemplateMediaIngestService.class));
        ToolRegistry registry = new ToolRegistry(
                List.of(templateTools.chatAppTemplateListTool(), templateTools.chatAppTemplateApplyTool()),
                new ToolInputValidator(), AssistantFixtures.objectMapper());

        // 用户的这一条消息里没有写图片地址：第八组候选是空的。
        // 显式给一个空集合（而不是让 mock 返回 null），这样「本轮没有地址」是这条用例里
        // 写出来的事实 —— 编排层虽然对 null 有兜底，但那个兜底不该被用来表达正常情况。
        when(mediaProvider.linksIn(anyString())).thenReturn(
                new TemplateMediaLinkCandidates(TemplateMediaLinkCandidates.LIMIT, List.of()));

        // 上下文里要有账号候选：apply 的第一个引用参数是 accountRef，
        // 它比 mediaRef 先被解析器比对，缺了它这一轮会在「与素材无关的地方」就被拒掉。
        when(contextBuilder.build()).thenReturn(
                AssistantFixtures.context().withCandidateSet(accountsWith(ACCOUNT)));
        when(templateAccounts.available(USER)).thenReturn(accountsWith(ACCOUNT));

        AssistantPendingActionService pending = new AssistantPendingActionService(
                pendingMapper, new AssistantDecisionParser(registry, new ToolInputValidator(),
                AssistantFixtures.objectMapper()), registry, audit, CONFIG,
                AssistantFixtures.objectMapper(),
                Clock.fixed(Instant.parse("2026-09-21T02:00:00Z"), ZoneOffset.UTC),
                mock(OutboundMessageService.class), mock(ContactMapper.class));

        service = new AssistantConversationService(
                contextBuilder,
                new AssistantPromptBuilder(registry, CONFIG, AssistantFixtures.objectMapper()),
                modelClient, new AssistantDecisionParser(registry, new ToolInputValidator(),
                AssistantFixtures.objectMapper()),
                new AssistantActionPolicy(), pending, audit, conversationLog,
                new AssistantRequestGuard(CONFIG), registry, CONFIG,
                // 压缩链与联系人候选窗口与「附件能不能到模型面前」无关，留空即走最直接那条路径。
                null, null, mediaProvider);
    }

    @Test
    void aHandedOverImageReachesTheModelAsACandidate() {
        modelReplies("{\"decision\":\"reply\",\"reply\":\"收到\"}");
        theProviderHasTheImage(ATTACHED);

        respond("用这张图做模板图片头", List.of(ATTACHED));

        String prompt = thePromptTheModelSaw();
        assertThat(prompt).contains(TemplateMediaCandidates.idOf(ATTACHED));
        assertThat(prompt).contains(TemplateMediaCandidates.NAME);
        // 归属复核用的是**调用方身份**，不是别的什么东西 —— 这条断言是防「注入点传错 userId」的。
        verify(mediaProvider).candidatesFor(USER, List.of(ATTACHED));
    }

    /**
     * 没有附件时，素材那一层<b>连库都不问</b>。
     *
     * <p>用「一次都没查库」而不是「清单为空」：后者无法区分「查过了但没有」与「根本没查」。
     * 绝大多数消息不带图，这条路径上多两次查询是白付的代价。
     *
     * <p>这里<b>不</b>断言 {@code verifyNoInteractions}：编排层现在每轮都会从用户原话里抽一次
     * 图片地址（{@code linksIn}），那是纯文本处理、不发查询，而它必须每轮都跑 ——
     * 用户可能只是随手写了个地址。真正该省的是查库那一半，所以断言落在 {@code candidatesFor} 上。
     */
    @Test
    void withoutAnAttachmentTheMediaLayerIsNotEvenConsulted() {
        modelReplies("{\"decision\":\"reply\",\"reply\":\"收到\"}");

        respond("随便聊聊", List.of());

        assertThat(thePromptTheModelSaw()).doesNotContain(TemplateMediaCandidates.idOf(ATTACHED));
        verify(mediaProvider, never()).candidatesFor(any(), anyList());
    }

    /**
     * 用户写下的地址变成第八组候选，而且<b>不需要任何附件</b>。
     *
     * <p>这是「用户给了个链接」那一半：地址来自原话而不是请求体，所以它必须在没有附件时
     * 也能进候选 —— 否则模型永远拿不到 linkRef，上传那条路就是死的。
     */
    @Test
    void anAddressInTheUsersOwnWordsBecomesACandidate() {
        String url = "https://cdn.example.com/quote.png";
        when(mediaProvider.linksIn(anyString())).thenReturn(new TemplateMediaLinkCandidates(
                TemplateMediaLinkCandidates.LIMIT,
                List.of(new TemplateMediaLinkCandidates.Item(TemplateMediaLinkCandidates.idOf(url)))));
        modelReplies("{\"decision\":\"reply\",\"reply\":\"收到\"}");

        respond("用 " + url + " 建个模板", List.of());

        String prompt = thePromptTheModelSaw();
        assertThat(prompt).contains(TemplateMediaLinkCandidates.idOf(url));
        assertThat(prompt).contains(TemplateMediaLinkCandidates.NAME);
    }

    /**
     * 图片头的完整链路：候选在 → 模型引用它 → 解析器放行 → 落成确认卡片、<b>不提交</b>。
     *
     * <p>这条用例替代不了 {@code ChatAppTemplateAssistantToolsTest}（那边断的是
     * 「组装出来的 HEADER 是 IMAGE + 素材 id」），它断的是**引用被接受**这件事：
     * 素材候选没接上时，同一个调用会以完全一样的方式被拒。
     */
    @Test
    void anImageHeaderCallIsAcceptedAndOnlyLeavesAConfirmationCard() {
        theProviderHasTheImage(ATTACHED);
        modelReplies("""
                {"decision":"call","tool":"chatapp.template_apply",
                 "arguments":{"accountRef":"%s","name":"shipment_update","category":"UTILITY",
                              "body":"您的货物已发出。","headerFormat":"IMAGE","mediaRef":"%s"}}
                """.formatted(ACCOUNT_REF, TemplateMediaCandidates.idOf(ATTACHED)));

        AssistantTurnResult result = respond("拿这张图做个模板", List.of(ATTACHED));

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.CONFIRMATION_REQUIRED);
        assertThat(result.proposal()).isNotNull();
        assertThat(result.proposal().tool()).isEqualTo(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_APPLY);
        // **没有提交**：模板提交不可撤回，确认卡片是这条路径上唯一的闸。
        verifyNoInteractions(applications);
    }

    /**
     * 用户<b>上一轮</b>发过、这一轮没带的图，模型引用不了。
     *
     * <p>附件刻意不做任何记忆（见 {@code AssistantConversationService.runTurn} 的注释），
     * 所以「上一轮那张图」在这一轮就是不在候选里 —— 解析器拒掉它，模型拿不到卡片。
     * 这条与上面那条是同一个调用、只差一个附件，正好把「图是靠当轮带上来的」钉死。
     */
    @Test
    void anAssetThatWasNotHandedOverThisTurnCannotBeReferenced() {
        modelReplies("""
                {"decision":"call","tool":"chatapp.template_apply",
                 "arguments":{"accountRef":"%s","name":"shipment_update","category":"UTILITY",
                              "body":"您的货物已发出。","headerFormat":"IMAGE","mediaRef":"%s"}}
                """.formatted(ACCOUNT_REF, TemplateMediaCandidates.idOf(OTHER_ASSET)));

        AssistantTurnResult result = respond("用刚才那张图", List.of());

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ERROR);
        assertThat(result.errorCode()).isEqualTo(AssistantException.REQUEST_INVALID);
        verifyNoInteractions(applications);
        // 进入 call 分支后的失败不给第二次机会（与 todo 那条同一条规则）。
        verify(modelClient, times(1)).complete(any(), any(), any());
    }

    /**
     * 素材不可用时只丢素材，<b>那句话照常回答</b>。
     *
     * <p>{@code TemplateMediaProvider} 对不可用的素材是静默丢弃（空清单），所以这里
     * mock 直接给空清单 —— 编排层面对的是同一个形状：一次「有附件但候选为空」的请求。
     * 断言的是「这一轮没有因此变成失败」，这是「用户说的是话、图是附加物」那条选择的兑现。
     */
    @Test
    void anUnusableAttachmentIsDroppedWithoutFailingTheTurn() {
        when(mediaProvider.candidatesFor(any(), anyList())).thenReturn(
                new TemplateMediaCandidates(TemplateMediaCandidates.LIMIT, List.of()));
        modelReplies("{\"decision\":\"reply\",\"reply\":\"好的，我记下了\"}");

        AssistantTurnResult result = respond("这张图帮我存一下，另外提醒我明天发货", List.of(ATTACHED));

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ANSWER);
        // 它确实被问过了（不是「没走到注入点」）—— 差别在于问的结果是空清单。
        verify(mediaProvider).candidatesFor(USER, List.of(ATTACHED));
    }

    // ---------- 夹具 ----------

    private AssistantTurnResult respond(String text, List<UUID> attachments) {
        return service.respond(USER, AssistantFixtures.CONVERSATION, List.of(), text, attachments,
                AssistantTurnSink.NONE);
    }

    private void theProviderHasTheImage(UUID asset) {
        when(mediaProvider.candidatesFor(USER, List.of(asset))).thenReturn(
                new TemplateMediaCandidates(TemplateMediaCandidates.LIMIT, List.of(
                        new TemplateMediaCandidates.Item(
                                TemplateMediaCandidates.idOf(asset), "IMAGE", "image/png", 2048L))));
    }

    /**
     * 模型实际看到的那一段文本。
     *
     * <p>取 {@code values()} 而不是按 {@code content} 取键：这里要的是「模型看到的全部文本」，
     * 提示词被拆成几条消息是 {@code AssistantPromptBuilder} 的实现细节，
     * 断言不该依赖它。而「候选进模型只有提示词这一条通道」这个前提，
     * 恰好要求把所有消息都收进来才成立。
     */
    @SuppressWarnings("unchecked")
    private String thePromptTheModelSaw() {
        ArgumentCaptor<List<Map<String, String>>> messages = ArgumentCaptor.forClass(List.class);
        verify(modelClient, org.mockito.Mockito.atLeastOnce()).complete(messages.capture(), any(), any());
        return messages.getAllValues().stream()
                .flatMap(turn -> turn.stream())
                .flatMap(message -> message.values().stream())
                .collect(Collectors.joining("\n"));
    }

    private void modelReplies(String... contents) {
        org.mockito.stubbing.OngoingStubbing<AssistantModelClient.ModelReply> stub =
                when(modelClient.complete(any(), any(), any()));
        for (String content : contents) {
            stub = stub.thenReturn(new AssistantModelClient.ModelReply(content, "deepseek-chat", 900));
        }
    }

    private static ChatAppAccountCandidates accountsWith(UUID accountId) {
        return new ChatAppAccountCandidates(ChatAppAccountCandidates.LIMIT, List.of(
                new ChatAppAccountCandidates.Item(
                        ChatAppAccountCandidates.idOf(accountId), "悦为", true)));
    }
}
