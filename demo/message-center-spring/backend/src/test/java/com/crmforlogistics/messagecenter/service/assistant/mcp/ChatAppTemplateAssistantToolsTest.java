package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.dto.response.SharedTemplateResponse;
import com.crmforlogistics.messagecenter.service.assistant.ChatAppAccountCandidates;
import com.crmforlogistics.messagecenter.service.assistant.ChatAppAccountProvider;
import com.crmforlogistics.messagecenter.service.assistant.TemplateMediaCandidates;
import com.crmforlogistics.messagecenter.service.assistant.TemplateMediaLinkCandidates;
import com.crmforlogistics.messagecenter.service.assistant.TemplateMediaProvider;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppSharedTemplateCatalogService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateException;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ButtonType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.OperationStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.OperationType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateButton;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateValidator;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.BeforeEach;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaCatalogService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaIngestService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.MediaAssetStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code chatapp.template_list} 与 {@code chatapp.template_apply}。
 *
 * <h1>本类主要在验三件事</h1>
 *
 * <ol>
 *   <li><b>扁平字段真的被组装成平台要求的嵌套结构</b> —— 这是工具存在的全部理由。
 *       模型碰不到 {@code TemplateComponent}，所以「恰好一个 BODY」这类规格由这里保证，
 *       而不是靠模型自觉。（{@code theFlatFieldsAreAssembledInto…}）</li>
 *   <li><b>列表工具必须把账号候选交回去</b> —— 申请工具的 {@code accountRef} 只能来自那里。
 *       少了这一步，整条链在第二轮就会断在一个模型无从修正的引用错误上。
 *       （{@code theListToolHandsBackTheAccountCandidates}）</li>
 *   <li><b>五类失败不能混成一种</b> —— 参数错、不可见、账号没配好、提交结果未知、内部错。
 *       尤其是「提交结果未知」：它是唯一一个「重试 = 第二次真实提交」的失败，
 *       而模板重名会被平台拒、且已经在账号上留了记录。（{@code anUnknownSubmissionOutcome…}）</li>
 * </ol>
 *
 * <h1>断言为什么断 {@code ToolResult} 而不是「期望抛异常」</h1>
 * {@link ToolRegistry#invoke} 把 {@link ToolExecutionException} <b>收成</b>
 * {@code ToolResult.failure(code, message)}（见该方法体），所以工具层看到的失败形态是
 * 「一个带错误码的结果」，不是异常。写 {@code assertThatThrownBy} 会得到一个
 * 「期望抛异常、实际什么也没发生」的假失败 —— 这条踩过，记在这里。
 *
 * <h1>为什么两个工具都用 mock 依赖</h1>
 * 本类要验的是「声明与组装」，不是「服务层能不能把模板提交成功」—— 后者是
 * {@code WhatsAppTemplateApplicationService} 自己的测试的事。掺进真仓储只会把两件事搅在一起。
 */
class ChatAppTemplateAssistantToolsTest {

    private static final UUID USER = AssistantFixtures.USER;
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String ACCOUNT_REF = ChatAppAccountCandidates.idOf(ACCOUNT);
    /** 一张「用户发来的图」的素材 id。用固定值是为了让断言能写死预期的字符串。 */
    private static final UUID IMAGE_ASSET = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final ChatAppAccountProvider accounts = mock(ChatAppAccountProvider.class);
    private final WhatsAppSharedTemplateCatalogService catalog =
            mock(WhatsAppSharedTemplateCatalogService.class);
    private final WhatsAppTemplateApplicationService applications =
            mock(WhatsAppTemplateApplicationService.class);
    private final TemplateMediaProvider media = mock(TemplateMediaProvider.class);
    private final WhatsAppTemplateMediaCatalogService mediaCatalog =
            mock(WhatsAppTemplateMediaCatalogService.class);
    private final WhatsAppTemplateMediaIngestService mediaIngest =
            mock(WhatsAppTemplateMediaIngestService.class);

    private final ChatAppTemplateAssistantTools tools =
            new ChatAppTemplateAssistantTools(accounts, catalog, applications, media,
                    mediaCatalog, mediaIngest);

    private final ToolRegistry registry = new ToolRegistry(
            List.of(tools.chatAppTemplateListTool(), tools.chatAppTemplateApplyTool(),
                    tools.chatAppTemplateMediaListTool(), tools.chatAppTemplateMediaUploadTool()),
            new ToolInputValidator(), AssistantFixtures.objectMapper());

    /**
     * 申请路径上工具会查一次候选，只为了拿一个可读名字（见 {@code accountTarget}）。
     * 这里给个默认值，单个用例可以自己覆盖。
     */
    @BeforeEach
    void theAssistantLooksUpItsAccountsForAReadableName() {
        when(accounts.available(USER)).thenReturn(accountsWith(account(ACCOUNT, "悦为", true)));
        // 素材候选默认「一张都没有」。图片头之外的用例根本不会碰它 —— 而让它返回 null
        // 会把「漏 stub」变成一次 NPE，再被注册表翻成 INTERNAL，看起来像系统故障。
        when(media.candidatesFor(any(), anyList())).thenReturn(
                new TemplateMediaCandidates(TemplateMediaCandidates.LIMIT, List.of()));
    }

    // ---------- chatapp.template_list ----------

    /**
     * 列表工具的核心契约：把账号候选交回去。
     *
     * <p>这条断言是整条链的地基。{@code ToolResult.discovered} 的第三个参数就是
     * 「这次调用发现了哪些对象」，而下一次调用要靠它才拿得到 {@code accountRef} ——
     * 一旦这里退化成 {@code ok(…)},申请工具就会永远缺一个它无论如何也填不出来的参数。
     */
    @Test
    void theListToolHandsBackTheAccountCandidates() {
        when(catalog.listForAccount(eq(USER), eq(ACCOUNT), anyInt(), anyInt(), any()))
                .thenReturn(page(template("TPL-1", "报价跟进", "APPROVED", null), 1L));

        ToolResult result = registry.invoke(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_LIST, USER, Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(result.candidates()).isInstanceOf(ChatAppAccountCandidates.class);
        ChatAppAccountCandidates handedBack = (ChatAppAccountCandidates) result.candidates();
        assertThat(handedBack.items()).hasSize(1);
        assertThat(handedBack.items().get(0).id()).isEqualTo(ACCOUNT_REF);
        assertThat(handedBack.items().get(0).name()).isEqualTo("悦为");
        assertThat(result.message()).contains("1 个 WhatsApp 账号");
    }

    /** 没有账号时也要回灌（一个空的候选集）—— 模型据此能分清「你没有账号」与「你还没查」。 */
    @Test
    void withNoAccountTheToolStillHandsBackAnEmptyCandidateSet() {
        when(accounts.available(USER)).thenReturn(accountsWith());

        ToolResult result = registry.invoke(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_LIST, USER, Map.of());

        assertThat(result.candidates()).isInstanceOf(ChatAppAccountCandidates.class);
        assertThat(((ChatAppAccountCandidates) result.candidates()).items()).isEmpty();
        assertThat(result.message()).contains("还没有 WhatsApp").contains("管理员");
        verifyNoInteractions(catalog);
    }

    /** 被拒原因必须带出来：用户问「上次那个为什么没通过」时，这是唯一的答案来源。 */
    @Test
    void aRejectedTemplateCarriesItsRejectionReason() {
        when(catalog.listForAccount(eq(USER), eq(ACCOUNT), anyInt(), anyInt(), any()))
                .thenReturn(page(template("TPL-7", "促销", "REJECTED", "营销类模板需要明确的退订方式"), 1L));

        ToolResult result = registry.invoke(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_LIST, USER, Map.of());

        assertThat(templatesOf(result)).hasSize(1);
        assertThat(templatesOf(result).get(0))
                .containsEntry("templateCode", "TPL-7")
                .containsEntry("reviewStatus", "REJECTED")
                .containsEntry("rejectionReason", "营销类模板需要明确的退订方式");
    }

    /**
     * 账号还没绑模板空间时必须说出来。
     *
     * <p>不说的话模型会挑到它，然后用户收到的是一句来自服务层的、没有下一步的错误；
     * 说出来则是一句「这个账号还没绑空间」，用户知道下一步是补凭证。
     *
     * <p>措辞钉在「还没绑定」上，不是「用不了」：空间按账号凭证补绑，未绑的账号照样能申请
     * （见 {@code WhatsAppTemplateApplicationService#createForActor}）。
     */
    @Test
    void anAccountWithoutATemplateScopeIsCalledOutBeforeTheUserPicksIt() {
        when(accounts.available(USER)).thenReturn(accountsWith(account(ACCOUNT, "悦为", false)));
        when(catalog.listForAccount(eq(USER), eq(ACCOUNT), anyInt(), anyInt(), any())).thenReturn(page(0L));

        ToolResult result = registry.invoke(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_LIST, USER, Map.of());

        assertThat(result.message()).contains("悦为").contains("还没绑定模板空间");
    }

    @Test
    void theListDeclarationIsReadOnlyAndTakesNoArgument() {
        ToolDefinition definition = registry.find(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_LIST).orElseThrow();

        assertThat(definition.tool().annotations().readOnlyHint()).isTrue();
        assertThat(definition.requiredArguments()).isEmpty();
        assertThat(definition.referenceBindings()).isEmpty();
    }

    // ---------- chatapp.template_apply ----------

    /**
     * 核心：扁平字段 → 平台要求的组件列表。
     *
     * <p>顺序与数量都是规格的一部分：HEADER → BODY → FOOTER → BUTTONS，
     * 且 BODY <b>恒定出现且只有一个</b>（{@code WhatsAppTemplateValidator} 要求
     * 「exactly one BODY」）。模型没有机会违反它，因为 BODY 不是它拼出来的。
     */
    @Test
    void theFlatFieldsAreAssembledIntoTheNestedComponentsThePlatformRequires() {
        when(applications.createForActor(any(), any(), any(), any())).thenReturn(accepted("TPL-9"));

        ToolResult result = registry.invoke(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_APPLY, USER, Map.of(
                "accountRef", ACCOUNT_REF,
                "name", "quote_follow_up",
                "category", "UTILITY",
                "headerText", "报价跟进",
                "body", "您好，关于您的运输方案请查看链接",
                "footerText", "不想再收到请回复退订",
                "buttonText", "查看详情",
                "buttonUrl", "https://example.com/quote"));

        assertThat(result.isError()).as("这条路必须走得通：%s", result.message()).isFalse();
        TemplateCommand command = capturedCommand();
        assertThat(command.name()).isEqualTo("quote_follow_up");
        assertThat(command.category()).isEqualTo("UTILITY");
        assertThat(command.language()).as("没传语言时落到服务端默认值").isEqualTo("zh_CN");
        assertThat(command.components()).extracting(TemplateComponent::type)
                .containsExactly(ComponentType.HEADER, ComponentType.BODY,
                        ComponentType.FOOTER, ComponentType.BUTTONS);
        assertThat(command.components().get(0).headerFormat().name()).isEqualTo("TEXT");
        assertThat(command.components().get(1).text()).isEqualTo("您好，关于您的运输方案请查看链接");
        assertThat(command.components().get(3).buttons()).hasSize(1);
        assertThat(command.components().get(3).buttons().get(0).url()).isEqualTo("https://example.com/quote");
        // 账号必须解成 uuid 传下去 —— 服务层的归属校验就认它。
        verify(applications).createForActor(eq(ACCOUNT), any(), eq(USER), any());
    }

    /**
     * 幂等键必须由这一层生成，且每次执行都是新的。
     *
     * <h2>为什么这条必须有</h2>
     * 它是这个测试类里<b>唯一一条验不到的东西</b>：其余用例都把 {@code WhatsAppTemplateApplicationService}
     * 整个 mock 掉，于是服务层的第一道校验（{@code beginOperation}：「clientRequestId 1..255 必填」）
     * 永远不会被执行 —— 少填这个字段，全绿，而线上 100% 失败。
     *
     * <p>失败时的表现还很误导：服务层的字段错误经
     * {@code ChatAppTemplateAssistantTools#translate} 渲染成「模板内容不符合 WhatsApp 的规格：
     * clientRequestId: must contain 1 to 255 characters」，而 {@code clientRequestId}
     * <b>不是模型能填的参数</b> —— 它会照着这句话去改模板正文，然后在同一个地方再失败一次。
     *
     * <h2>为什么顺带量形状</h2>
     * 项目里 {@code clientRequestId} 有两条约束：新建这条只限长度（1..255），
     * 媒体上传那条更严（{@code [A-Za-z0-9._~:-]{1,255}}）。这里按<b>严的那条</b>钉，
     * 免得日后换成一个带空格或中文的前缀，在另一条路上才炸。
     */
    @Test
    void everyApplyCarriesAFreshIdempotencyKeyTheModelDoesNotOwn() {
        when(applications.createForActor(any(), any(), any(), any())).thenReturn(accepted("TPL-9"));

        applyWith(Map.of());
        applyWith(Map.of());

        ArgumentCaptor<TemplateCommand> captor = ArgumentCaptor.forClass(TemplateCommand.class);
        verify(applications, times(2)).createForActor(any(), captor.capture(), any(), any());
        List<String> ids = captor.getAllValues().stream()
                .map(TemplateCommand::clientRequestId)
                .toList();

        assertThat(ids).allSatisfy(id -> {
            assertThat(id).as("服务层 beginOperation 的第一行就是必填，空值整条路走不通").isNotBlank();
            assertThat(id).as("超过 255 会被服务层拒掉").hasSizeLessThanOrEqualTo(255);
            assertThat(id).matches("[A-Za-z0-9._~:-]{1,255}");
        });
        assertThat(ids).as("两次执行必须是两个 id，否则第二次会命中幂等拿回第一次的结果")
                .doesNotHaveDuplicates();
    }

    /** 只给正文时只有一个组件：可选字段「没给」不等于「给空串」。 */
    @Test
    void omittedOptionalFieldsProduceNoComponentAtAll() {
        when(applications.createForActor(any(), any(), any(), any())).thenReturn(accepted("TPL-9"));

        applyWith(Map.of());

        assertThat(capturedCommand().components()).extracting(TemplateComponent::type)
                .containsExactly(ComponentType.BODY);
    }

    /** 变量示例被拆成校验器要的 map。 */
    @Test
    void variableExamplesAreParsedIntoTheMapTheValidatorExpects() {
        when(applications.createForActor(any(), any(), any(), any())).thenReturn(accepted("TPL-9"));

        applyWith(Map.of(
                "body", "$(customer_name)，我们从 $(origin) 出发的方案如下",
                "variableExamples", List.of("customer_name=张总", "origin=深圳")));

        TemplateCommand command = capturedCommand();
        assertThat(command.examples()).containsOnlyKeys("customer_name", "origin");
        assertThat(command.examples().get("customer_name")).containsExactly("张总");
    }

    /**
     * 拆不开的变量示例（没有等号）在本地就拒 —— 那不是业务规则，是形状。
     *
     * <p>「变量集合是否与正文一致」那条业务判据只有一份，在服务层的校验器里；
     * 工具不复制它，只负责把 {@code name=值} 这种形状拆开。
     */
    @Test
    void aVariableExampleWithoutAnEqualsSignIsRejectedLocallyAsAShapeError() {
        ToolResult result = applyWith(Map.of("variableExamples", List.of("张总")));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("变量名=示例值");
        verifyNoInteractions(applications);
    }

    /** 按钮文字与链接必须成对：只给一个的话服务层只会说「buttons 不合法」，而模型需要「成对给」。 */
    @Test
    void aHalfSpecifiedButtonIsRejectedLocally() {
        ToolResult result = applyWith(Map.of("buttonText", "查看详情"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("buttonText 与 buttonUrl");
        verifyNoInteractions(applications);
    }

    /** 成功回话停在「已提交」：审核在平台那边，说「已通过」会在下一次同步时被事实推翻。 */
    @Test
    void successSaysSubmittedNotApproved() {
        when(applications.createForActor(any(), any(), any(), any())).thenReturn(accepted("TPL-9"));

        ToolResult result = applyWith(Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(result.message())
                .contains("提交给 WhatsApp 审核")
                .contains("quote_follow_up")
                .doesNotContain("已通过");
        assertThat(result.data()).containsEntry("templateCode", "TPL-9").containsEntry("status", "SUCCEEDED");
    }

    /**
     * 提交结果未知：唯一一个「重试 = 第二次真实提交」的失败。
     *
     * <p>必须有自己的码，否则模型看到笼统的失败就会重发，而重名模板会被平台拒 ——
     * 用户得到的是一个「明明没成功却不能再试」的状态。
     */
    @Test
    void anUnknownSubmissionOutcomeIsNeverRetried() {
        when(applications.createForActor(any(), any(), any(), any()))
                .thenReturn(new WhatsAppTemplateApplicationService.OperationView(
                        UUID.randomUUID(), OperationType.CREATE,
                        OperationStatus.SUBMISSION_UNKNOWN, "TPL-9", null, null));

        ToolResult result = applyWith(Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.SEND_OUTCOME_UNKNOWN);
        assertThat(result.message()).contains("不要重复申请");
    }

    /** 校验失败：要带上「哪个字段、为什么」的中文名，模型才知道改哪里。 */
    @Test
    void aValidationFailureNamesTheFieldInChinese() {
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put("body.text", "must not exceed 1024 characters");
        errors.put("examples", "must contain exactly the variables used by BODY");
        when(applications.createForActor(any(), any(), any(), any()))
                .thenThrow(new WhatsAppTemplateException("TEMPLATE_VALIDATION_FAILED",
                        HttpStatus.BAD_REQUEST, "WhatsApp template validation failed", errors, null, false));

        ToolResult result = applyWith(Map.of());

        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("正文").contains("变量示例");
    }

    /**
     * 账号没配好（409）→ UNAVAILABLE，而不是 INVALID_ARGUMENT。
     *
     * <p>这一个的处置是「找管理员」，换什么参数都没用。报成参数错会把模型送进
     * 「换个写法再试」的死循环里。
     */
    @Test
    void anAccountWithoutAScopeMapsToUnavailableRatherThanAParameterError() {
        when(applications.createForActor(any(), any(), any(), any()))
                .thenThrow(new WhatsAppTemplateException("WHATSAPP_PROVIDER_SCOPE_REQUIRED",
                        HttpStatus.CONFLICT, "Business App 账号尚未完成模板空间绑定", Map.of(), null, false));

        ToolResult result = applyWith(Map.of());

        assertThat(result.code()).isEqualTo(ToolExecutionException.UNAVAILABLE);
        assertThat(result.message()).contains("管理员");
    }

    /** 账号不属于你（403/404）→ 对外不区分「不存在」与「不是你的」。 */
    @Test
    void anAccountThatIsNotYoursIsIndistinguishableFromAMissingOne() {
        when(applications.createForActor(any(), any(), any(), any()))
                .thenThrow(new WhatsAppTemplateException("WHATSAPP_ACCOUNT_NOT_FOUND",
                        HttpStatus.NOT_FOUND, "not found", Map.of(), null, false));

        ToolResult result = applyWith(Map.of());

        assertThat(result.code()).isEqualTo(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND);
        assertThat(result.message()).isEqualTo(ToolExecutionException.ACCESS_DENIED_MESSAGE);
    }

    /** 形状不对的 accountRef：本地就拒，且要告诉模型去哪里拿正确的值。 */
    @Test
    void aMalformedAccountReferenceIsRejectedLocally() {
        ToolResult result = registry.invoke(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_APPLY, USER, Map.of(
                "accountRef", "悦为",
                "name", "quote", "category", "UTILITY", "body", "正文"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("chatapp.template_list");
        verifyNoInteractions(applications);
    }

    /** 类别越界由 schema 的 enum 拦在调用之前（不是靠服务层）。 */
    @Test
    void anUnknownCategoryIsRejectedByTheSchemaBeforeAnyCall() {
        ToolResult result = registry.invoke(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_APPLY, USER, Map.of(
                "accountRef", ACCOUNT_REF,
                "name", "quote", "category", "PROMOTION", "body", "正文"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("category");
        verifyNoInteractions(applications);
    }

    /**
     * 声明：绑定两组候选（账号、素材）、必填四项、且是「破坏性写」。
     *
     * <p><b>两组而不是一组</b>，这是图片头带进来的：{@code accountRef} 说「在哪个账号下建」，
     * {@code mediaRef} 说「用用户发来的哪张图」。两者都必须绑候选，理由同源：
     * 让模型填一个它编得出来的 uuid，等于把「编造」当成合法输入。
     *
     * <p>{@code destructiveHint=true} 是刻意与 {@code wecom.push_self} 相反的 ——
     * 两者都是写，但一个提交给外部平台审核（撤不回），一个推给自己（可忽略）。
     * 这条断言把差别钉在声明层，免得后来人按「都是写」把它们统一了。
     */
    @Test
    void theApplyDeclarationBindsTheAccountCandidateSet() {
        ToolDefinition definition = registry.find(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_APPLY).orElseThrow();

        // 顺序也钉住：accountRef 先于 mediaRef（声明里就是这么放的）。
        assertThat(definition.referenceBindings())
                .containsExactly(
                        Map.entry("accountRef", ChatAppAccountCandidates.NAME),
                        Map.entry("mediaRef", TemplateMediaCandidates.NAME));
        assertThat(definition.requiredArguments())
                .containsExactly("accountRef", "name", "category", "body");
        assertThat(definition.tool().annotations().readOnlyHint()).isFalse();
        assertThat(definition.tool().annotations().destructiveHint()).isTrue();
    }

    // ---------- 图片头 ----------

    /**
     * 图片头组装对了：HEADER 是 IMAGE + 素材 id，而且**没有**文本。
     *
     * <p>断的是交给服务层的那份 command，不是工具的返回值 —— 「头部做成了图片」这件事
     * 唯一的证据在 {@code components} 里，工具说的话不算。
     */
    @Test
    void anImageHeaderCarriesTheAssetIdTheUserHandedOver() {
        theUserHandedOverAnImage(IMAGE_ASSET);
        when(applications.createForActor(any(), any(), any(), any())).thenReturn(accepted("TPL-9"));

        ToolResult result = applyWith(Map.of(
                "headerFormat", "IMAGE",
                "mediaRef", TemplateMediaCandidates.idOf(IMAGE_ASSET)));

        assertThat(result.isError()).as("这条路必须走得通：%s", result.message()).isFalse();
        TemplateComponent header = capturedCommand().components().get(0);
        assertThat(header.type()).isEqualTo(ComponentType.HEADER);
        assertThat(header.headerFormat().name()).isEqualTo("IMAGE");
        assertThat(header.mediaAssetId()).isEqualTo(IMAGE_ASSET.toString());
        assertThat(header.text()).isNull();
    }

    /**
     * 只给图、不说头部类型，本地就拒。
     *
     * <p>不拒的话服务层会把这个模板当成「没有头部」，图被<b>静默忽略</b> ——
     * 而用户以为模板带图了。这是本地判据里最值钱的一条。
     */
    @Test
    void aMediaReferenceWithoutTheImageFormatIsRefusedLocally() {
        theUserHandedOverAnImage(IMAGE_ASSET);

        ToolResult result = applyWith(Map.of("mediaRef", TemplateMediaCandidates.idOf(IMAGE_ASSET)));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("headerFormat");
        verifyNoInteractions(applications);
    }

    /** 说图片头却不给图：本地拒，而不是让服务层答一句模型转述不清的参数错。 */
    @Test
    void anImageFormatWithoutAMediaReferenceIsRefusedLocally() {
        ToolResult result = applyWith(Map.of("headerFormat", "IMAGE"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("mediaRef");
        verifyNoInteractions(applications);
    }

    /** 一个模板只有一个头部：图片头不能再带标题。 */
    @Test
    void anImageHeaderCannotAlsoCarryATitle() {
        theUserHandedOverAnImage(IMAGE_ASSET);

        ToolResult result = applyWith(Map.of(
                "headerFormat", "IMAGE",
                "mediaRef", TemplateMediaCandidates.idOf(IMAGE_ASSET),
                "headerText", "报价跟进"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        verifyNoInteractions(applications);
    }

    /** mediaRef 的形状不对：它只能来自候选清单，不能是别的什么字符串。 */
    @Test
    void aMediaReferenceThatIsNotACandidateReferenceIsRefusedLocally() {
        ToolResult result = applyWith(Map.of("headerFormat", "IMAGE", "mediaRef", "随便一个 id"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains(TemplateMediaCandidates.TYPE);
        verifyNoInteractions(applications);
    }

    /**
     * 素材候选是**这一轮开始时**的快照，调用时可能已经不能用（比如刚被挂到别的模板上，
     * 状态从 UPLOADED 变成 ATTACHED，而服务层只认 UPLOADED）。
     *
     * <p>这条用例什么都不 stub —— 默认候选为空，正好就是那个场景。
     */
    @Test
    void aMediaAssetThatStoppedBeingUsableIsRefusedBeforeTheCall() {
        ToolResult result = applyWith(Map.of(
                "headerFormat", "IMAGE",
                "mediaRef", TemplateMediaCandidates.idOf(IMAGE_ASSET)));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("不能用");
        verifyNoInteractions(applications);
    }

    /**
     * schema 里的长度上限与服务层的校验常量同源。
     *
     * <p>写成「引用 {@code WhatsAppTemplateValidator} 的常量」而不是字面量：
     * 抄第二份数字的话，改宽校验却没改 schema 时模型会被一份过期的上限误导，
     * 而那条路径上没有任何东西会红。
     */
    @Test
    void theDeclaredLengthLimitsComeFromTheValidatorInsteadOfASecondCopy() {
        ToolDefinition definition = registry.find(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_APPLY).orElseThrow();

        assertThat(propertyOf(definition, "body"))
                .containsEntry("maxLength", WhatsAppTemplateValidator.MAX_BODY_LENGTH);
        assertThat(propertyOf(definition, "headerText"))
                .containsEntry("maxLength", WhatsAppTemplateValidator.MAX_HEADER_OR_FOOTER_LENGTH);
        assertThat(propertyOf(definition, "footerText"))
                .containsEntry("maxLength", WhatsAppTemplateValidator.MAX_HEADER_OR_FOOTER_LENGTH);
    }

    /**
     * 「变量能放在哪」这条平台规则必须写在模型读到参数的地方。
     *
     * <h2>为什么它值得一条绊线</h2>
     * 它不是文案，是一条会让整次申请白跑的硬规则：正文以变量开头或结尾会被平台拒审。
     * 这条规则此前一处都没写 —— 提示词里没有，工具描述里反而举了一个变量开头的坏示范，
     * 本地校验器（后端 {@code WhatsAppTemplateValidator} 与前端模板编辑器）也都不拦，
     * 于是模型只能靠猜，猜错的结果是用户等一轮审核、拿到一句英文拒审原因。
     * 现在三层齐了：描述给规则、后端拦提交、前端拦点击。
     *
     * <p>两个断言各钉一半：<b>规则句子在</b>（含平台拒审原话 —— 模型在
     * {@code chatapp.template_list} 的 {@code rejectionReason} 里看到的就是这句英文），
     * 以及<b>坏示范不在</b>。后者是这次事故真正的成因：原来的示范
     * 「$(customer_name)，您好」自己就是变量开头，等于在教模型犯这个错。
     * 只钉规则句子的话，有人把示范换回那种形态照样是绿的。
     */
    @Test
    void theVariablePlacementRulesAreStatedWhereTheModelReadsThem() {
        ToolDefinition definition = registry.find(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_APPLY).orElseThrow();

        String body = (String) propertyOf(definition, "body").get("description");
        assertThat(body)
                .contains("不能出现在正文的开头或结尾")
                .as("平台拒审原话留在描述里，模型才能与自己看到的拒审原因对上")
                .contains("Variables can't be at the start or end of the template")
                .as("变量开头的问候形态正是被拒的那种，不能再出现在示范里")
                .doesNotContain("$(customer_name)，您好")
                .as("正文那条规则不能被顺手推广到标题上 —— 标题是另一条规则（至多 1 个变量）")
                .doesNotContain("标题以变量开头或结尾");

        assertThat((String) propertyOf(definition, "footerText").get("description"))
                .as("页脚整体不支持变量，这一条与正文首尾那条是两件事")
                .contains("页脚里不能放 $(变量)");

        assertThat((String) propertyOf(definition, "headerText").get("description"))
                .as("标题是另一条规则（至多 1 个变量），把正文那条推广过去会拒掉平台本来收的模板")
                .contains("最多只能放 1 个 $(变量)")
                .doesNotContain("标题以变量开头或结尾");
    }

    /** 模板名与语言也必须有上界：它们是自由文本，无上限等于让模型决定它有多长。 */
    @Test
    void theNameAndLanguageAreBounded() {
        ToolDefinition definition = registry.find(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_APPLY).orElseThrow();

        assertThat(propertyOf(definition, "name"))
                .containsEntry("maxLength", ChatAppTemplateAssistantTools.NAME_MAX_CHARS);
        assertThat(propertyOf(definition, "language"))
                .containsEntry("maxLength", ChatAppTemplateAssistantTools.LANGUAGE_MAX_CHARS);
    }

    /** 槽位填满时按位次产出一列 —— 平台就是按这个顺序把它们排在消息底部的。 */
    @Test
    void everyButtonSlotComesOutInTheOrderTheSlotsAreNumbered() {
        when(applications.createForActor(any(), any(), any(), any())).thenReturn(accepted("TPL-9"));

        applyWith(Map.of(
                "buttonText", "查看报价",
                "buttonUrl", "https://example.com/quote",
                "button2Text", "查看舱位",
                "button2Url", "https://example.com/schedule"));

        List<TemplateButton> buttons = capturedCommand().components().get(1).buttons();
        assertThat(buttons).extracting(TemplateButton::text)
                .containsExactly("查看报价", "查看舱位");
        assertThat(buttons).extracting(TemplateButton::url)
                .containsExactly("https://example.com/quote", "https://example.com/schedule");
        assertThat(buttons).extracting(TemplateButton::type).containsOnly(ButtonType.URL);
        assertThat(buttons).as("服务层单独限制 URL 按钮的个数，这里就是它的上限")
                .hasSize(WhatsAppTemplateValidator.MAX_URL_BUTTONS);
    }

    /**
     * 空槽不占位：只给第 2 个时得到一个按钮，而不是一个「空按钮」或一句报错。
     *
     * <p>「跳过」是刻意的：模型给几个按钮就是几个。要求它连着填只会多一条它会踩的规则，
     * 而位次仍然按它给的顺序 —— 跳过换不到任何正确性。
     */
    @Test
    void anEmptyButtonSlotIsSkippedInsteadOfEndingTheList() {
        when(applications.createForActor(any(), any(), any(), any())).thenReturn(accepted("TPL-9"));

        applyWith(Map.of(
                "button2Text", "查看舱位",
                "button2Url", "https://example.com/schedule"));

        assertThat(capturedCommand().components().get(1).buttons())
                .extracting(TemplateButton::text)
                .containsExactly("查看舱位");
    }

    /**
     * 三个槽位各自判「成对给」，报错要点名是第几个。
     *
     * <p>服务层对半个按钮只会说 buttons 不合法，而槽位变成三个之后，
     * 「是第几个按钮的哪两个参数要成对」正是模型唯一需要知道的事。
     */
    @Test
    void aHalfSpecifiedSecondButtonIsRejectedByItsOwnName() {
        ToolResult result = applyWith(Map.of("button2Text", "查看舱位"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        assertThat(result.message()).contains("button2Text 与 button2Url");
        verifyNoInteractions(applications);
    }

    /**
     * 超出服务层上限的槽位<b>根本不存在</b> —— 不是「存在但会被拒」。
     *
     * <p>钉住的是「槽位数 = {@link WhatsAppTemplateValidator#MAX_URL_BUTTONS}」这件事：
     * 多开一格，模型就可能填进去、然后被服务层拒；少开一格则白丢一个能力。
     * 上一版正是多开了一格（工具 3、服务层只容 2）才有了这次事故。
     */
    @Test
    void theSlotBeyondThePlatformLimitIsNotDeclaredAtAll() {
        ToolDefinition definition = registry.find(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_APPLY).orElseThrow();

        for (int index = ChatAppTemplateAssistantTools.MAX_URL_BUTTONS + 1; index <= 4; index++) {
            assertThat(propertyOf(definition, ChatAppTemplateAssistantTools.buttonTextKey(index)))
                    .as("第 %d 个按钮的文字不该在声明里", index)
                    .isNull();
            assertThat(propertyOf(definition, ChatAppTemplateAssistantTools.buttonUrlKey(index)))
                    .as("第 %d 个按钮的链接不该在声明里", index)
                    .isNull();
        }
    }

    /**
     * 工具开的按钮数必须留在平台上限之内 —— 同源判据，不是在这边另抄一个数。
     *
     * <p><b>上一版这条断言比错了对象</b>：它拿 {@link WhatsAppTemplateValidator#MAX_BUTTONS}
     * （按钮<b>总数</b>上限 10）当参照系，而本工具开的槽位全是 URL 按钮，真正管着它们的是
     * {@link WhatsAppTemplateValidator#MAX_URL_BUTTONS}（URL 按钮单独的上限 2）。
     * 3 ≤ 10 一路绿灯，于是「工具放行、服务层拒」这个它<b>本来就是要拦</b>的场景，
     * 从它眼皮底下走了过去 —— 断言写得再工整，认错了规则也等于没有。
     */
    @Test
    void theToolOffersNoMoreButtonsThanThePlatformAccepts() {
        assertThat(ChatAppTemplateAssistantTools.MAX_URL_BUTTONS)
                .isLessThanOrEqualTo(WhatsAppTemplateValidator.MAX_URL_BUTTONS);
    }

    /** 每个按钮槽位都得在声明里，且长度上限与工具侧用的是同一份常量。 */
    @Test
    void everyButtonSlotIsDeclaredWithTheLimitTheToolItselfEnforces() {
        ToolDefinition definition = registry.find(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_APPLY).orElseThrow();

        for (int index = 1; index <= ChatAppTemplateAssistantTools.MAX_URL_BUTTONS; index++) {
            assertThat(propertyOf(definition, ChatAppTemplateAssistantTools.buttonTextKey(index)))
                    .as("第 %d 个按钮的文字", index)
                    .containsEntry("maxLength", WhatsAppTemplateValidator.MAX_HEADER_OR_FOOTER_LENGTH);
            assertThat(propertyOf(definition, ChatAppTemplateAssistantTools.buttonUrlKey(index)))
                    .as("第 %d 个按钮的链接", index)
                    .containsEntry("maxLength", ChatAppTemplateAssistantTools.BUTTON_URL_MAX_CHARS);
        }
        assertThat(propertyOf(definition, "button1Text"))
                .as("第 1 个槽位沿用 buttonText，不再多一个 button1Text")
                .isNull();
    }

    /** 申请必须真的发起：上面那些「本地就拒」的用例不能把正常路径也一起拦住。 */
    @Test
    void aValidApplicationReachesTheServiceExactlyOnce() {
        when(applications.createForActor(any(), any(), any(), any())).thenReturn(accepted("TPL-9"));

        applyWith(Map.of());

        verify(applications, times(1)).createForActor(any(), any(), any(), any());
    }

    /**
     * 工具不挑域：两种绑定（Business App / 企业 API）都交给服务层按账号自己判断。
     *
     * <p>这是 2026-09-29 那次修复的防线。修之前工具直接调 {@code createPrivate}，
     * 而它第一行要求账号是 Business App —— 于是<b>任何企业 API 账号都申请不了模板</b>，
     * 用户拿到的是一句「当前 WhatsApp 账号不是 Business App 私有模板账号」，
     * 既看不懂也改不动。换回「直接调某个具体域」的写法会让同一个洞重新出现，
     * 所以这里把「工具不挑域」钉住。
     */
    @Test
    void theApplyToolLeavesTheDomainChoiceToTheServiceLayer() {
        when(applications.createForActor(any(), any(), any(), any())).thenReturn(accepted("TPL-9"));

        applyWith(Map.of());

        verify(applications).createForActor(eq(ACCOUNT), any(), eq(USER), any());
        verify(applications, never()).createPrivate(any(), any(), any(), any());
        verify(applications, never()).create(any(), any(), any(), any(), any());
    }

    /**
     * 助手面对模板只有两个动作：<b>看一眼</b>与<b>新建</b>。
     *
     * <p>删除与修改模板刻意不开放：它们影响的是<b>所有正在用该模板的会话</b>，
     * 而且同样提交给外部平台、我们这边撤不回 —— 风险比「多一个待审核的模板」
     * 大一个量级。这条断言看着像「把现状抄一遍」，但它防的是后来顺手加一个
     * 「改模板」工具的那种改动：加了它这条会红，加的人就得先解释为什么值得放开。
     */
    @Test
    void theToolSurfaceOffersNoDeleteOrModifyOfTemplates() {
        assertThat(registry.list()).extracting(ToolDefinition::name)
                .containsExactlyInAnyOrder(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_LIST,
                        ChatAppTemplateAssistantTools.TOOL_TEMPLATE_APPLY,
                        ChatAppTemplateAssistantTools.TOOL_TEMPLATE_MEDIA_LIST,
                        ChatAppTemplateAssistantTools.TOOL_TEMPLATE_MEDIA_UPLOAD);
    }

    // ---------- 素材：清单与上传 ----------

    /**
     * 列出来的每一条素材都必须同时进候选。
     *
     * <p>这是刻意钉的一致性边界：{@code assets} 里列了而候选里没有的那一条，模型引用它会在
     * <b>下一轮</b>被编排层按 {@code x-candidateSet} 拦掉 ——
     * 那是「工具说能用、紧接着却说不在候选里」这种最费解的失败。
     * 两边都从同一个 {@code items} 列表出，这条断言就是那个约束的可执行版本。
     */
    @Test
    void everyListedAssetAlsoReachesTheCandidateSet() {
        when(mediaCatalog.listForActor(any(), eq(ACCOUNT), anyInt()))
                .thenReturn(List.of(mediaAsset(IMAGE_ASSET)));

        ToolResult result = registry.invoke(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_MEDIA_LIST,
                USER, new java.util.HashMap<>());

        assertThat(result.isError()).isFalse();
        TemplateMediaCandidates candidates = (TemplateMediaCandidates) result.candidates();
        assertThat(candidates.items()).extracting(TemplateMediaCandidates.Item::id)
                .containsExactly(TemplateMediaCandidates.idOf(IMAGE_ASSET));
        assertThat(rowsOf(result)).extracting(row -> row.get("mediaRef"))
                .as("data 里的 mediaRef 必须与候选 id 逐条对得上")
                .containsExactlyElementsOf(candidates.items().stream()
                        .map(TemplateMediaCandidates.Item::id).toList());
    }

    /** 没有账号时也要回灌一个空候选，并且把「下一步」说清楚（素材从哪来）。 */
    @Test
    void withoutAnyAccountTheMediaListSaysWhereAssetsComeFrom() {
        when(accounts.available(USER)).thenReturn(accountsWith());

        ToolResult result = registry.invoke(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_MEDIA_LIST,
                USER, new java.util.HashMap<>());

        assertThat(result.message()).contains("没有");
        // 没有账号时回灌的是**账号**那组候选（空的那一份），不是素材候选：
        // 模型据此才能分清「你没有账号」与「你的账号里没有图」——
        // 后者该做的事是让用户贴一张图，而前者只能去联系管理员。
        assertThat(result.candidates()).isInstanceOf(ChatAppAccountCandidates.class);
        assertThat(result.candidates().items()).isEmpty();
    }

    /** 上传：候选里那个地址原样交给入库服务，账号由 accountRef 定（不是模型给个字符串就行）。 */
    @Test
    void uploadingHandsTheCandidateAddressToTheIngestService() {
        String url = "https://cdn.example.com/quote.png";
        when(mediaIngest.ingestFromLink(eq(USER), eq(ACCOUNT), eq(url), any()))
                .thenReturn(uploaded(IMAGE_ASSET));

        ToolResult result = registry.invoke(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_MEDIA_UPLOAD,
                USER, uploadArguments(TemplateMediaLinkCandidates.idOf(url)));

        assertThat(result.isError()).isFalse();
        assertThat(result.message()).contains("素材库");
        verify(mediaIngest).ingestFromLink(eq(USER), eq(ACCOUNT), eq(url), any());
    }

    /**
     * 裸地址不是候选引用 —— 在发任何网络请求之前就拒。
     *
     * <p>这条是「模型编不出地址」这个性质的兜底断言：它可以照抄一个候选 id，
     * 但一个它自己拼出来的 {@code https://…} 连形状这一关都过不了。
     * 断言 {@code never()} 是为了让「将来有人放宽形状检查」立刻红。
     */
    @Test
    void anAddressThatIsNotACandidateReferenceIsRejectedBeforeAnyFetch() {
        ToolResult result = registry.invoke(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_MEDIA_UPLOAD,
                USER, uploadArguments("https://cdn.example.com/quote.png"));

        assertThat(result.isError()).isTrue();
        assertThat(result.code()).isEqualTo(ToolExecutionException.INVALID_ARGUMENT);
        verify(mediaIngest, never()).ingestFromLink(any(), any(), any(), any());
    }

    // ---------- 夹具 ----------

    /**
     * 造一份「用户这一轮发来了一张图」的候选。
     *
     * <p>默认 candidateFor 返回空候选（见 {@code @BeforeEach}），所以图片头的用例
     * 必须显式说「图是有的」—— 这也让「素材在调用那一刻已经不可用」那条用例
     * 什么都不用做：不调本方法就是那个场景。
     */
    private void theUserHandedOverAnImage(UUID assetId) {
        when(media.candidatesFor(any(), anyList())).thenReturn(new TemplateMediaCandidates(
                TemplateMediaCandidates.LIMIT,
                List.of(new TemplateMediaCandidates.Item(
                        TemplateMediaCandidates.idOf(assetId), "IMAGE", "image/png", 1024L))));
    }

    /** 申请一次（四个必填字段照传，额外参数由调用方给）。 */
    private ToolResult applyWith(Map<String, Object> extra) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("accountRef", ACCOUNT_REF);
        arguments.put("name", "quote_follow_up");
        arguments.put("category", "UTILITY");
        arguments.put("body", "正文");
        arguments.putAll(extra);
        return registry.invoke(ChatAppTemplateAssistantTools.TOOL_TEMPLATE_APPLY, USER, arguments);
    }

    private TemplateCommand capturedCommand() {
        ArgumentCaptor<TemplateCommand> captor = ArgumentCaptor.forClass(TemplateCommand.class);
        verify(applications).createForActor(any(), captor.capture(), any(), any());
        return captor.getValue();
    }

    private static WhatsAppTemplateApplicationService.OperationView accepted(String templateCode) {
        return new WhatsAppTemplateApplicationService.OperationView(
                UUID.randomUUID(), OperationType.CREATE, OperationStatus.SUCCEEDED,
                templateCode, "provider-req-1", null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> templatesOf(ToolResult result) {
        return (List<Map<String, Object>>) result.data().get("templates");
    }

    private static Map<String, Object> uploadArguments(String linkRef) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("accountRef", ACCOUNT_REF);
        arguments.put(ChatAppTemplateAssistantTools.LINK_REF, linkRef);
        return arguments;
    }

    private static WhatsAppTemplateMediaCatalogService.MediaAsset mediaAsset(UUID id) {
        return new WhatsAppTemplateMediaCatalogService.MediaAsset(
                id, HeaderFormat.IMAGE, "image/png", 1024L, java.time.Instant.parse("2026-09-29T02:00:00Z"));
    }

    private static WhatsAppTemplateMediaUploadService.UploadResult uploaded(UUID id) {
        return new WhatsAppTemplateMediaUploadService.UploadResult(
                new WhatsAppTemplateMediaUploadService.MediaAssetView(id, "link-x", HeaderFormat.IMAGE,
                        "image/png", 1024L, "sha", "https://provider.example/x.png",
                        MediaAssetStatus.UPLOADED, null, null, "trace"),
                true);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(ToolResult result) {
        return (List<Map<String, Object>>) result.data().get("assets");
    }

    private static ChatAppAccountCandidates accountsWith(ChatAppAccountCandidates.Item... items) {
        return new ChatAppAccountCandidates(ChatAppAccountCandidates.LIMIT, List.of(items));
    }

    private static ChatAppAccountCandidates.Item account(UUID id, String name, boolean scopeReady) {
        return new ChatAppAccountCandidates.Item(ChatAppAccountCandidates.idOf(id), name, scopeReady);
    }

    private static SharedTemplateResponse.Page page(SharedTemplateResponse item, long total) {
        return new SharedTemplateResponse.Page(List.of(item), total, 1, ChatAppTemplateAssistantTools.LIST_SIZE);
    }

    private static SharedTemplateResponse.Page page(long total) {
        return new SharedTemplateResponse.Page(List.of(), total, 1, ChatAppTemplateAssistantTools.LIST_SIZE);
    }

    private static SharedTemplateResponse template(String code, String name, String reviewStatus, String rejection) {
        return new SharedTemplateResponse(
                UUID.randomUUID(), 1L, code, name, null, null,
                "zh_CN", "UTILITY", reviewStatus, reviewStatus, rejection,
                true, List.of(), Map.of(), null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertyOf(ToolDefinition definition, String name) {
        Map<String, Object> properties =
                (Map<String, Object>) definition.tool().inputSchema().get("properties");
        return (Map<String, Object>) properties.get(name);
    }
}
