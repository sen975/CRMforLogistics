package com.crmforlogistics.messagecenter.service.assistant.mcp;

import com.crmforlogistics.messagecenter.dto.response.SharedTemplateResponse;
import com.crmforlogistics.messagecenter.service.assistant.ChatAppAccountCandidates;
import com.crmforlogistics.messagecenter.service.assistant.ChatAppAccountProvider;
import com.crmforlogistics.messagecenter.service.assistant.TemplateMediaCandidates;
import com.crmforlogistics.messagecenter.service.assistant.TemplateMediaLinkCandidates;
import com.crmforlogistics.messagecenter.service.assistant.TemplateMediaProvider;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppSharedTemplateCatalogService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaCatalogService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaIngestService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateException;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ButtonType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.MediaAssetStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.OperationStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateButton;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateCommand;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * chatapp（WhatsApp）模板域的助手能力：模板侧的 {@code chatapp.template_list} 与
 * {@code chatapp.template_apply}，素材侧的 {@code chatapp.template_media_list} 与
 * {@code chatapp.template_media_upload}。
 *
 * <h2>这两个工具解决的是开发客户时的一个具体卡点</h2>
 * WhatsApp 不允许给陌生号码随便发自由文本：<b>先要有审核通过的模板</b>，才能发起第一句触达。
 * 于是「开发客户」的第一步其实是「申请一个模板」，而申请表单在界面上是嵌套结构
 * （HEADER / BODY / FOOTER / BUTTONS 四类组件，恰好一个 BODY），
 * 模型既看不懂也不该直接填它。
 *
 * <p>所以这一层做的是<b>扁平参数 → 嵌套组件</b>的组装：模型只说「标题写什么、正文写什么、
 * 底部放个什么链接」，{@code TemplateComponent} 的形状由 {@link #componentsOf} 拼出来。
 * 模型碰不到结构，也就没有「少一个 BODY / 多一个 HEADER」这类错可犯。
 *
 * <h2>为什么两个工具、以及它们必须一起用</h2>
 * 「申请模板」需要 {@code accountRef}，而账号是<b>候选引用</b>（见 {@link ChatAppAccountCandidates}），
 * 候选只能来自某次只读调用的回灌。若只有申请工具，模型就会遇到一个死结：
 * 它需要一个账号 id，却没有任何途径拿到它。
 *
 * <p>所以 {@code chatapp.template_list} 承担两件事：<b>查重</b>（这个账号下已经有哪些模板）
 * 与<b>交回账号候选</b>（{@code ToolResult.discovered} 的第三个参数）。工具描述里把
 * 「申请之前先调用它」写成硬要求，这条链才走得通。
 *
 * <h2>申请为什么必须过确认卡片</h2>
 * 它是本系统里<b>唯一一个把内容提交给外部平台审核</b>的动作：模板一旦提交就进入 Meta 的审核
 * 队列，我们这边改不掉也撤不回，被拒还会在这个账号上留下记录。代价与「发出去一封邮件」同级，
 * 所以它与 {@code message.send_email} 同档 —— 不进任何白名单，
 * {@code AssistantActionPolicy} 一律判 CONFIRM，卡片上显示模板的完整内容（见
 * {@code AssistantPendingActionService.card}）。
 *
 * <h2>归属防线在哪</h2>
 * 在服务层。这一层只调 {@link WhatsAppTemplateApplicationService#createForActor}，
 * 它的第一行是 {@code requireOwnedAccount(accountId, actorUserId)} —— 判据是 SQL 里的
 * {@code owner_user_id}，两个模板域共用同一条（共享域那个服务层方法自己不查归属，
 * 所以这一查必须有）。这里不重复判 —— 工具层再判一次会让人以为防线在这里，
 * 从而在改服务层时不再谨慎。
 * 账号候选本身也已经由 {@code where owner_user_id = ?} 限定过（见 {@link ChatAppAccountProvider}）。

 * <h2>素材侧两个工具补的是同一件事的另一半</h2>
 * 模板的图片头只认<b>内部素材 id</b>（{@code prepareMedia} 把它当 UUID 解析），而素材只可能由上传产生。
 * 于是「给模板配一张图」在助手看来一直是一条断链：{@code mediaRef} 要求候选里有那张图，
 * 而候选此前只有一个来源 —— 用户在这一轮消息里贴了图（{@code TemplateMediaCandidates}）。
 *
 * <p>{@code chatapp.template_media_upload} 补上「用户写下的是<b>地址</b>而不是文件」那一种情形
 * （{@link TemplateMediaLinkCandidates} 那段注释解释了为什么地址必须来自用户的原话），
 * {@code chatapp.template_media_list} 补上「用之前那几张里的某一张」——
 * 后者同时是前者的下游：新收进来的图与历史上传的图在库里是同一种东西，同一个入口列出来。
 *
 * <h2>模板侧刻意只有两个动作：看一眼、新建</h2>
 * 删除与修改模板<b>不开放给助手</b> —— 服务层有 {@code modifyPrivate}/{@code modifyShared}/
 * {@code deletePrivate}/{@code deleteShared}，但这里没有、也不该有任何工具声明它们。
 * 理由不是「用不到」，而是这两种动作的性质：改或删一个已经在平台上跑的模板，
 * 影响面是<b>所有正在用它的会话</b>，而且同样提交给外部平台、我们这边撤不回。
 * 新建最坏的结果只是多一个待审核的模板 —— 风险面小一个量级。
 * 所以域分派（Business App / 企业 API 两条路）只覆盖新建这一种。
 * 这条边界由 {@code ChatAppTemplateAssistantToolsTest#theToolSurfaceOffersNoDeleteOrModifyOfTemplates} 钉住。
 *
 * <p>{@code chatapp.template_media_upload} 同样只增不改：素材没有删除口、也没有改名口，
 * 服务商那边那个对象我们连句柄都没有（库里这行只是账）。所以「素材只能越攒越多」不是疏忽，
 * 是这一层没有能力做减法 —— 要清理得去服务商控制台。这句话对助手同样是结论：
 * <b>不要承诺「帮你删掉那张图」</b>。
 *
 * <p>注意：本类<b>没有</b> {@code to} 那种「自由文本目标」参数。模板名会被提交给外部平台，
 * 但它落在你自己名下的账号里，且要过确认卡片 —— 这是它与 {@code message.send_email}
 * 不给收件地址参数的差别所在（后者能发给任意第三方，前者不能）。
 */
@Configuration(proxyBeanMethods = false)
public class ChatAppTemplateAssistantTools {

    public static final String TOOL_TEMPLATE_LIST = "chatapp.template_list";
    public static final String TOOL_TEMPLATE_APPLY = "chatapp.template_apply";
    public static final String TOOL_TEMPLATE_MEDIA_LIST = "chatapp.template_media_list";
    public static final String TOOL_TEMPLATE_MEDIA_UPLOAD = "chatapp.template_media_upload";

    /**
     * 模板语言默认值。
     *
     * <p>{@code zh_CN} 而不是 {@code en_US}：本系统的使用者是中文货代/物流场景，
     * 未指定时猜中文比猜英文更可能对。模型明确说了别的语言就用它给的。
     */
    static final String DEFAULT_LANGUAGE = "zh_CN";

    /**
     * 语言代码的长度上界（{@code zh_CN} / {@code pt_BR} 这类，留足余量）。
     *
     * <p>这一组长度上限都是 {@code public} 的，理由与 {@link MessageSendAssistantTools} 那边一样：
     * 确认卡片的最坏长度由它们加出来（见 {@code AssistantPendingActionService#renderTemplateBody}），
     * 而算那个长度的那条用例在另一个包里 —— 在测试里抄一份数字过去，就会与这里悄悄脱钩，
     * 于是「卡片塞得进预算」这条保证会在没人察觉的情况下失效。
     */
    public static final int LANGUAGE_MAX_CHARS = 16;

    /**
     * 模板名的长度上界。名字会出现在 Meta 后台，长度不是关键，防的是拿它当正文用。
     *
     * <p>为什么是 {@code public}：见 {@link #LANGUAGE_MAX_CHARS}。
     */
    public static final int NAME_MAX_CHARS = 64;

    /**
     * 一个模板最多给几个网址按钮 —— <b>直接引用服务层那个常量，不在这里另写一个数</b>。
     *
     * <p>它不是本工具自己的预算：服务层 {@link WhatsAppTemplateValidator} 单独限制
     * 「URL 类型的按钮最多几个」，而本工具开的槽位<b>全是 URL 按钮</b>，
     * 所以管着这里的是服务层那条 {@code MAX_URL_BUTTONS}，不是按钮总数那条。
     *
     * <p>抄第二份数字的后果是「工具放行、服务层拒」：模型拿着一个表面合法的调用撞上
     * 一句它无从修正的报错，用户看到的是「助手说能建、建出来失败」。
     */
    public static final int MAX_URL_BUTTONS = WhatsAppTemplateValidator.MAX_URL_BUTTONS;

    /**
     * 网址按钮链接的长度上界。
     *
     * <p>平台校验器不管 URL 长度（{@link WhatsAppTemplateValidator} 只数按钮个数），
     * 所以这个数<b>纯粹是本工具自己的预算</b>。
     *
     * <p><b>它是被确认卡片倒推出来的，不是拍脑袋选的。</b>卡片正文一字不摘地写进摘要，
     * 而模板卡片要装下「名称 + 类别 + 语言 + 标题 + 正文 + 页脚 + {@link #MAX_URL_BUTTONS} 个按钮」。
     * 把每个上限都用满时，除 URL 之外的部分是 1401 个字符（见
     * {@code AssistantPendingActionServiceTest#aTemplateCardAlwaysFitsInTheSummaryBudgetSoNoLineIsSilentlyCut}
     * 里逐项加出来的那个数），摘要预算 4000 ⇒ 留给两个 URL 的余量是 2599 ⇒ 单个不得超过 1299。
     * 取 512 而不是贴着 1299：真实落地页地址（含 utm 参数）通常不到 200 个字符，
     * 而卡片超预算会被<b>裸截断</b>，用户会对着半个模板点确认，而那次提交撤不回。
     *
     * <p>按钮数或任一长度上限被调大，都要重算这条 —— 那条用例是绊线，它会红。
     */
    public static final int BUTTON_URL_MAX_CHARS = 512;

    /** 槽位说明只挂在第 1 个按钮上，后两个不重复一遍（重复会稀释真正要模型记住的那句）。 */
    static final String BUTTON_SLOT_NOTE =
            "；最多 " + MAX_URL_BUTTONS + " 个按钮，按位次顺序排在消息底部，中间没给的就跳过";

    /** 头部类型的参数名。取值用 {@link HeaderFormat} 的枚举名，不自造词。 */
    public static final String HEADER_FORMAT = "headerFormat";

    /**
     * 头部素材的参数名。
     *
     * <p>它引用的是 {@link TemplateMediaCandidates}（第七组候选）—— 也就是
     * <b>用户这一轮亲手发来的那张图</b>，不是素材库里随便挑的一张。
     * 所以模型编不出可用的 id：编出来的既不在候选里（编排层按 {@code x-candidateSet} 拦），
     * 也不会属于这个账号（工具侧再复核一次）。
     */
    public static final String MEDIA_REF = "mediaRef";

    /**
     * 要收进素材库的那个图片地址的参数名。
     *
     * <p>它引用的是 {@link TemplateMediaLinkCandidates}（第八组候选）—— 也就是
     * <b>用户在这一轮原话里写下的链接</b>。与 {@link #MEDIA_REF} 是同一套手法：
     * 模型编不出可用的值，因为值不在它手里，而在用户的输入里。
     *
     * <p>名字叫 {@code linkRef} 而不是 {@code imageUrl}，是为了让「这里只能填一个候选引用」
     * 这件事在参数名上就看得出来 —— 一个叫 {@code url} 的参数，下一个改这条链的人
     * 会理所当然地认为它可以是任意地址。
     */
    public static final String LINK_REF = "linkRef";

    /**
     * 每个账号最多列几条素材。
     *
     * <p>{@code chatapp.template_media_list} 会遍历<b>全部</b>可用账号，所以单账号的条数上限
     * 与最终候选集的 {@code LIBRARY_LIMIT} 是两回事：前者防「一个账号把候选占满」，
     * 后者是交给模型的总量。多账号时按账号顺序填满为止 —— 剩下的那张图不是不能用，
     * 是这一轮没列出来（回话里会说明列了几条）。
     */
    static final int MEDIA_PER_ACCOUNT = 5;

    /** 一次列出多少个账号的模板（每个账号各取这么多条）。有界是候选集的硬要求。 */
    static final int LIST_SIZE = 20;

    /** 变量示例的写法 {@code name=值} 里的分隔符。 */
    static final char EXAMPLE_SEPARATOR = '=';

    /** 变量示例里单个值的长度上界。 */
    static final int EXAMPLE_VALUE_MAX_CHARS = 60;

    /** 一次最多给多少个变量示例 —— 超过这个数的正文已经不该是「模板」了。 */
    static final int EXAMPLE_MAX_ITEMS = 10;

    static final String CATEGORY_UTILITY = "UTILITY";
    static final String CATEGORY_MARKETING = "MARKETING";

    private final ChatAppAccountProvider accounts;
    private final WhatsAppSharedTemplateCatalogService catalog;
    private final WhatsAppTemplateApplicationService applications;
    private final TemplateMediaProvider media;
    private final WhatsAppTemplateMediaCatalogService mediaCatalog;
    private final WhatsAppTemplateMediaIngestService mediaIngest;

    public ChatAppTemplateAssistantTools(ChatAppAccountProvider accounts,
                                         WhatsAppSharedTemplateCatalogService catalog,
                                         WhatsAppTemplateApplicationService applications,
                                         TemplateMediaProvider media,
                                         WhatsAppTemplateMediaCatalogService mediaCatalog,
                                         WhatsAppTemplateMediaIngestService mediaIngest) {
        this.accounts = accounts;
        this.catalog = catalog;
        this.applications = applications;
        this.media = media;
        this.mediaCatalog = mediaCatalog;
        this.mediaIngest = mediaIngest;
    }

    // ---------- 声明 ----------

    @Bean
    public ToolDefinition chatAppTemplateListTool() {
        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_TEMPLATE_LIST)
                        .title("查看 WhatsApp 模板清单")
                        .description("列出你名下 WhatsApp（chatapp）账号里<b>已经申请过</b>的模板，"
                                + "含名称、语言、类别、审核状态、被拒原因与当前能否发送。"
                                + "本工具没有参数 —— 它按你的身份自己找到全部可用账号。"
                                + "两个用途：① 申请新模板之前先看有没有现成的，避免重复提交同名模板；"
                                + "② 它会把你的可用账号作为候选清单交给你，"
                                + "而 chatapp.template_apply 需要引用其中的 accountRef —— "
                                + "<b>所以申请之前必须先调用本工具</b>，否则你拿不到 accountRef。"
                                + "这是只读操作。")
                        .inputSchema(objectSchema(new LinkedHashMap<>(), List.of()))
                        .annotations(readOnly())
                        .build(),
                this::listTemplates);
    }

    @Bean
    public ToolDefinition chatAppTemplateApplyTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("accountRef", referenceTo(ChatAppAccountCandidates.NAME,
                "用哪个账号申请，只能取自候选清单里的 accountRef（形如 CHATAPP_ACCOUNT:<uuid>）；"
                        + "那份清单来自 chatapp.template_list 的返回结果。"
                        + "账号走企业 API 绑定还是 Business App 绑定由系统按账号自己判断，"
                        + "你不用管也不用问用户，两种都能申请；"
                        + "候选里 templateScopeReady=false 只表示这个账号还没绑定模板空间，"
                        + "申请时系统会先按它的凭证去绑，只有凭证不可用时才会失败"));
        properties.put("name", stringSpec(
                "模板名：英文小写字母、数字与下划线，一旦提交不能改。"
                        + "取一个能看出用途的名字，如 quote_follow_up",
                "maxLength", NAME_MAX_CHARS));
        properties.put("category", enumSpec(
                "模板类别。" + CATEGORY_UTILITY + " = 与用户已有的事项相关（订单、账单、物流进展）；"
                        + CATEGORY_MARKETING + " = 推广性质（新品、优惠、开发新客户）。"
                        + "选错类别会被平台拒绝，且此时无法当场改正",
                CATEGORY_UTILITY, CATEGORY_MARKETING));
        properties.put("language", stringSpec(
                "语言代码，如 zh_CN、en_US、pt_BR；不传表示 " + DEFAULT_LANGUAGE,
                "maxLength", LANGUAGE_MAX_CHARS));
        properties.put(HEADER_FORMAT, enumSpec(
                "头部类型；不传表示这个模板没有头部。"
                        + HeaderFormat.TEXT.name() + " = 纯文本标题（配 headerText）；"
                        + HeaderFormat.IMAGE.name() + " = 图片（配 " + MEDIA_REF + "）。"
                        + "图片只能来自用户发给你的那张图 —— 你变不出一张图，"
                        + "也拿不到任何图片地址。用户给了图并要建模板时才用它",
                HeaderFormat.TEXT.name(), HeaderFormat.IMAGE.name()));
        properties.put("headerText", stringSpec(
                "标题（可选），最多 " + WhatsAppTemplateValidator.MAX_HEADER_OR_FOOTER_LENGTH + " 个字符。"
                        + "头部用图片时不要给这个参数；"
                        + "标题里最多只能放 1 个 $(变量)，整个标题就是一个变量也是允许的 ——"
                        + "正文那条「变量不能落在首尾」对标题不适用",
                "maxLength", WhatsAppTemplateValidator.MAX_HEADER_OR_FOOTER_LENGTH));
        properties.put(MEDIA_REF, referenceTo(TemplateMediaCandidates.NAME,
                "头部图片，只能取自「用户这一轮发来的素材」候选清单（形如 TEMPLATE_MEDIA:<uuid>）。"
                        + "必须与 " + HEADER_FORMAT + "=" + HeaderFormat.IMAGE.name() + " 成对给；"
                        + "用户没有发图时这份候选清单是空的，那就不要给这个参数"));
        /*
         * 正文的写法规则里，最容易被忽略、代价又最高的是「变量不能落在首尾」：
         * 平台的硬规则（拒审原话 Variables can't be at the start or end of the template），
         * 触发之后模板进不了审核通过态，用户白等一轮，且模板名要重新走一遍。
         *
         * 这条规则原先一处都没写 —— 更糟的是这里原来举的示范
         * 「$(customer_name)，您好」本身就是变量开头，等于在教模型犯这个错。
         * 所以示范必须换成变量两侧都有固定文字的形态，规则也必须明写在模型填正文时
         * 一定会读到的那一处（参数描述）。不额外抄到工具 description：同一句话写两遍
         * 会稀释真正要模型记住的那条（同 BUTTON_SLOT_NOTE 的取舍）。
         */
        properties.put("body", stringSpec(
                "正文（必填），最多 " + WhatsAppTemplateValidator.MAX_BODY_LENGTH + " 个字符。"
                        + "需要按客户个性化时用 $(变量名) 占位，"
                        + "并在 variableExamples 里给出每个变量的示例；"
                        + "不支持 {{1}} 或 ${var} 这两种写法。"
                        + "**$(变量) 不能出现在正文的开头或结尾**：正文的第一个字和最后一个字"
                        + "都必须是固定文字，例如「您好 $(customer_name)，您的货物预计 $(days) 天后送达，"
                        + "请留意查收」。把变量放在最前面、或让正文以变量收尾，都会被平台直接拒审"
                        + "（平台的拒审原话：Variables can't be at the start or end of the template）；"
                        + "两个变量之间也要夹上固定文字，不要紧挨着",
                "maxLength", WhatsAppTemplateValidator.MAX_BODY_LENGTH));
        properties.put("footerText", stringSpec(
                "页脚（可选），最多 " + WhatsAppTemplateValidator.MAX_HEADER_OR_FOOTER_LENGTH + " 个字符。"
                        + "只有用户明确要求加页脚时才填（例如要求放退订说明）；"
                        + "用户没提就不要填 —— 别自己补一句听起来得体的提示语。"
                        + "页脚里不能放 $(变量)：平台不认页脚变量，写了会被拒审",
                "maxLength", WhatsAppTemplateValidator.MAX_HEADER_OR_FOOTER_LENGTH));
        for (int index = 1; index <= MAX_URL_BUTTONS; index++) {
            // 槽位是固定三个、名字是拼出来的，而不是一个对象数组：
            // ToolInputValidator 的 SUPPORTED_ITEM_KEYS 里没有 properties，写对象数组会启动失败。
            properties.put(buttonTextKey(index), stringSpec(
                    "第 " + index + " 个网址按钮上的文字（可选，与 " + buttonUrlKey(index) + " 成对给）"
                            + BUTTON_SLOT_NOTE,
                    "maxLength", WhatsAppTemplateValidator.MAX_HEADER_OR_FOOTER_LENGTH));
            properties.put(buttonUrlKey(index), stringSpec(
                    "第 " + index + " 个网址按钮指向的链接（可选，与 " + buttonTextKey(index) + " 成对给）。"
                            + "写完整地址，含 https://，最多 " + BUTTON_URL_MAX_CHARS + " 个字符",
                    "maxLength", BUTTON_URL_MAX_CHARS));
        }
        properties.put("variableExamples", arraySpec(
                "变量的示例值，每项写成「变量名=示例值」（如 customer_name=张总）。"
                        + "正文里出现的每个 $(变量) 都必须在这里给出示例，多给或少给都会被平台拒绝；"
                        + "正文里没有变量时不要传这个参数",
                EXAMPLE_VALUE_MAX_CHARS, EXAMPLE_MAX_ITEMS));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_TEMPLATE_APPLY)
                        .title("申请一个 WhatsApp 消息模板")
                        .description("向 WhatsApp 提交一个新消息模板申请。模板通过审核之后，"
                                + "才能给还没有聊过天的号码发出第一句消息 —— 这是开发新客户的前提。"
                                + "必填的只有正文，头部（文本或用户发来的图片）、页脚与最多 "
                                + MAX_URL_BUTTONS + " 个网址按钮都是可选的，"
                                + "只在用户明确要了才给 —— 用户没提就不要替他添，"
                                + "模板上每一块内容客户都会真的看到。"
                                + "组件结构由系统按平台规格组装，你不需要、也无法直接描述它。"
                                + "accountRef 必须先调用 chatapp.template_list 拿到。"
                                + "**提交之后进入平台审核队列，我们这边改不掉也撤不回**，"
                                + "所以只在用户明确要求申请模板时调用；"
                                + "用户只是描述想发的内容时，先把内容写给他看并问清楚。"
                                + "执行前需要用户在确认卡片上确认。")
                        .inputSchema(objectSchema(properties,
                                List.of("accountRef", "name", "category", "body")))
                        .annotations(write())
                        .build(),
                this::applyTemplate);
    }

    @Bean
    public ToolDefinition chatAppTemplateMediaListTool() {
        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_TEMPLATE_MEDIA_LIST)
                        .title("查看可用的图片素材")
                        .description("列出你名下 WhatsApp（chatapp）账号里<b>可用的图片素材</b>"
                                + "（上传过、且还没被别的模板用掉的），含类型、大小与上传时间；"
                                + "同时把它们作为「素材库候选清单」交给你，"
                                + "chatapp.template_apply 的 " + MEDIA_REF + " 可以引用其中的任意一条。"
                                + "用途：用户说「用之前那张图」「素材库里那张报价图」时，先调它把可用的图列出来。"
                                + "本工具没有参数 —— 它按你的身份自己找到全部可用账号。"
                                + "这是只读操作。"
                                + "注意素材只会越攒越多：这里没有删除，也不要答应用户「帮你删掉那张图」。")
                        .inputSchema(objectSchema(new LinkedHashMap<>(), List.of()))
                        .annotations(readOnly())
                        .build(),
                this::listMedia);
    }

    @Bean
    public ToolDefinition chatAppTemplateMediaUploadTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("accountRef", referenceTo(ChatAppAccountCandidates.NAME,
                "把素材存到哪个账号下，只能取自候选清单里的 accountRef（形如 CHATAPP_ACCOUNT:<uuid>）；"
                        + "那份清单来自 chatapp.template_list 的返回结果，所以要先调用它"));
        properties.put(LINK_REF, referenceTo(TemplateMediaLinkCandidates.NAME,
                "要收进素材库的图片地址，只能取自「用户这一轮写下的图片地址」候选清单"
                        + "（形如 " + TemplateMediaLinkCandidates.TYPE + ":https://…）。"
                        + "那份清单就是用户这一条消息里出现的 https 链接 —— "
                        + "<b>你变不出一个地址来</b>：候选里没有就说明用户没给地址，"
                        + "此时改让用户把图直接贴在输入框里（贴图会由客户端直接上传，不需要本工具）。"
                        + "只接受 https 开头、指向公网域名、内容确实是 PNG 或 JPEG 的地址"));

        return new ToolDefinition(
                McpSchema.Tool.builder()
                        .name(TOOL_TEMPLATE_MEDIA_UPLOAD)
                        .title("把用户给的图片地址存进素材库")
                        .description("服务端去<b>下载</b>用户写下的那个图片地址，再把它作为图片素材"
                                + "存进这个账号的素材库；之后它就能被模板的图片头引用"
                                + "（用 chatapp.template_media_list 可以看到它）。"
                                + "适用于用户<b>写了一个链接</b>的场景；用户直接贴了图时不需要本工具，"
                                + "那张图已经在上传了。"
                                + "同一个地址重复提交不会存出两条（素材按内容去重）。"
                                + "只接受 https 开头的公网地址，且内容必须是 PNG 或 JPEG；"
                                + "这两个条件任何一条不满足都会失败，改地址没有用。"
                                + "执行前需要用户在确认卡片上确认。")
                        .inputSchema(objectSchema(properties, List.of("accountRef", LINK_REF)))
                        .annotations(writeIdempotent())
                        .build(),
                this::uploadMedia);
    }

    // ---------- 执行：列表 ----------

    private ToolResult listTemplates(UUID userId, Map<String, Object> arguments) {
        ChatAppAccountCandidates available = accounts.available(userId);
        if (available.items().isEmpty()) {
            // 没有账号也要回灌（空候选集）：模型据此能分清「你没有账号」与「你还没查」。
            return ToolResult.discovered(
                    "你名下还没有 WhatsApp（chatapp）账号，所以暂时没有模板可看，也无法申请新模板。"
                            + "WhatsApp 账号需要管理员先完成号码入驻，请联系管理员",
                    Map.of("count", 0, "accounts", 0),
                    available);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> accountViews = new ArrayList<>();
        for (ChatAppAccountCandidates.Item account : available.items()) {
            UUID accountId = ChatAppAccountCandidates.targetOf(account.id());
            Map<String, Object> accountView = new LinkedHashMap<>();
            accountView.put("accountRef", account.id());
            accountView.put("name", account.name());
            accountView.put("templateScopeReady", account.templateScopeReady());

            long count = 0;
            if (accountId != null) {
                // 只读查询。归属已由候选的 where owner_user_id 限定；这里不再叠一层 ——
                // 写动作的归属由 createForActor 自己做（见类注释）。
                // 读哪一份库（账号私有 / 它所在空间的共享库）由 listForAccount 按账号的
                // 绑定方式决定：选错那把钥匙不报错，只是安静地返回 0 条。
                SharedTemplateResponse.Page page =
                        catalog.listForAccount(userId, accountId, 1, LIST_SIZE, null);
                // total 而不是 items().size()：后者是「本页几条」，超过 LIST_SIZE 时
                // 会把「有 200 个模板」讲成「有 20 个」，而用户会据此以为不用查了。
                count = page.total();
                for (SharedTemplateResponse template : page.items()) {
                    rows.add(templateRow(account.id(), template));
                }
            }
            accountView.put("templateCount", count);
            accountViews.add(accountView);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accounts", accountViews);
        data.put("templates", rows);
        data.put("count", rows.size());

        return ToolResult.discovered(describeList(available, rows.size()), data, available);
    }

    private static Map<String, Object> templateRow(String accountRef, SharedTemplateResponse template) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("accountRef", accountRef);
        row.put("templateCode", template.templateCode());
        row.put("name", template.name());
        row.put("language", template.language());
        row.put("category", template.category());
        row.put("reviewStatus", template.reviewStatus());
        row.put("allowSend", template.allowSend());
        // 被拒原因必须带出来：用户问「上次那个为什么没通过」时，这是唯一的答案来源。
        // 为空时不放这个 key（不塞空串），让「没有拒绝原因」与「原因是空串」不可混淆。
        if (template.rejectionReason() != null && !template.rejectionReason().isBlank()) {
            row.put("rejectionReason", template.rejectionReason());
        }
        if (template.deletedAt() != null) {
            row.put("deleted", true);
        }
        return row;
    }

    // ---------- 执行：申请 ----------

    /**
     * 头部的一个具体形态：没有、纯文本、或一张图。
     *
     * <p>写成 record 而不是往 {@code componentsOf} 再加一个 {@code String}：那个方法的签名
     * 本来就有两个相邻的 {@code String}，再补一个 {@code mediaAssetId} 会让调用点出现
     * 「两个字符串位置写反」这种编译器抓不到的错。
     */
    record HeaderSpec(String text, UUID mediaAssetId) {
        /** 没有头部。 */
        static HeaderSpec none() {
            return new HeaderSpec("", null);
        }
    }

    /**
     * 三个头部参数 → 一个 {@link HeaderSpec}。
     *
     * <h2>三条本地判据，不指望平台校验器</h2>
     * <ol>
     *   <li><b>给图就必须说头部是图片。</b> 只说「用这张图」而不给 {@code headerFormat}，
     *       服务层会当成「没有头部」，图被静默忽略 —— 而用户以为模板带图了。</li>
     *   <li><b>说图片头就必须给图。</b> 否则服务层的 {@code prepareMedia} 会报一句
     *       模型转述不清的参数错。</li>
     *   <li><b>图片头不能再给标题文本。</b> 一个模板只有一个 HEADER，两个都给必被平台拒。</li>
     * </ol>
     *
     * <p>形状过了还要复核「这个素材现在还能不能用」。编排层已经按 {@code x-candidateSet}
     * 拦过「不在本轮候选里的 id」，但候选是这一轮<b>开始时</b>建的快照：
     * 同一张图可能刚被挂到另一个模板上（状态变成 {@code ATTACHED}），
     * 而 {@code prepareMedia} 只认 {@code UPLOADED}。这里提前说清楚，
     * 比让它到服务层炸成一句参数错要好。
     */
    HeaderSpec headerOf(UUID userId, Map<String, Object> arguments) {
        String format = optionalText(arguments, HEADER_FORMAT, "");
        String text = optionalText(arguments, "headerText", "");
        String reference = optionalText(arguments, MEDIA_REF, "");
        boolean image = HeaderFormat.IMAGE.name().equals(format);
        if (image && reference.isEmpty()) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    HEADER_FORMAT + "=" + HeaderFormat.IMAGE.name() + " 时必须给出 " + MEDIA_REF
                            + "（取自用户发来的图片候选）");
        }
        if (!image && !reference.isEmpty()) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    MEDIA_REF + " 只能和 " + HEADER_FORMAT + "=" + HeaderFormat.IMAGE.name()
                            + " 一起用；这次 " + HEADER_FORMAT + " 是「" + format + "」");
        }
        if (!image) {
            return new HeaderSpec(text, null);
        }
        if (!text.isBlank()) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "头部是图片时不能再给 headerText —— 一个模板只有一个头部");
        }
        if (!TemplateMediaCandidates.looksLikeReference(reference)) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    MEDIA_REF + " 只能取自候选清单里的素材（形如 " + TemplateMediaCandidates.TYPE
                            + ":<uuid>）；候选清单就是用户这一轮发来的图片");
        }
        UUID assetId = TemplateMediaCandidates.targetOf(reference);
        if (assetId == null) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    MEDIA_REF + " 里的编号不是一个有效的素材 id");
        }
        if (!media.candidatesFor(userId, List.of(assetId)).contains(TemplateMediaCandidates.idOf(assetId))) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "这张图现在不能用：它要么还没传完，要么已经挂到别的模板上了。"
                            + "请用户重新发一张");
        }
        return new HeaderSpec("", assetId);
    }

    private ToolResult applyTemplate(UUID userId, Map<String, Object> arguments) {
        AccountTarget target = accountTarget(userId, arguments);
        String name = requiredText(arguments, "name");
        String category = requiredText(arguments, "category");
        String body = requiredText(arguments, "body");
        String language = optionalText(arguments, "language", DEFAULT_LANGUAGE);
        String footerText = optionalText(arguments, "footerText", "");
        List<TemplateButton> buttons = urlButtonsOf(arguments);
        HeaderSpec header = headerOf(userId, arguments);

        List<TemplateComponent> components = componentsOf(header, body, footerText, buttons);
        Map<String, List<String>> examples = examplesOf(arguments);

        /*
         * clientRequestId 是服务层的幂等键，而模型<b>不拥有</b>这个参数 —— 它既不该看见它，
         * 也无从编出一个稳定的 id，所以只能在这里生成。漏填的后果不是「少传一个字段」那么轻：
         * 服务层 {@code beginOperation} 的第一行就是「1..255 必填」，缺了整条路 100% 走不通，
         * 而且回话会把锅甩给「模板内容不符合规格」，让模型去改一个它从来没传过的字段。
         *
         * 一次执行一个 id，同时当作 traceId 用：操作表里 idempotency_key 与 trace_id 同值，
         * 按哪一个查都能定位到同一行。形状与 {@link #traceId()} 同源（{@code assistant-<uuid>}），
         * 也满足媒体上传那条更严的字符集 {@code [A-Za-z0-9._~:-]{1,255}}。
         */
        String trace = traceId();
        TemplateCommand command = new TemplateCommand(
                name, language, category, components, examples, null, trace);
        try {
            WhatsAppTemplateApplicationService.OperationView view =
                    applications.createForActor(target.id(), command, userId, trace);
            return applied(target, name, category, language, view);
        } catch (WhatsAppTemplateException e) {
            throw translate(e);
        }
    }

    /**
     * 扁平字段 → 平台要求的组件列表。
     *
     * <p>顺序按平台惯例 HEADER → BODY → FOOTER → BUTTONS。BODY 恒定出现且只有一个，
     * 这正是 {@code WhatsAppTemplateValidator} 要求的「exactly one BODY」——
     * 模型没有机会违反它，因为 BODY 不是它拼出来的。
     *
     * <p>头部有三态（无 / 文本 / 图片），由 {@link HeaderSpec} 表达。文本头带 {@code text}，
     * 图片头带 {@code mediaAssetId} —— 两者互斥，所以这里是一个 if/else 而不是两个独立判断。
     */
    static List<TemplateComponent> componentsOf(HeaderSpec header, String body,
                                                String footerText, List<TemplateButton> buttons) {
        List<TemplateComponent> components = new ArrayList<>();
        if (header.mediaAssetId() != null) {
            components.add(new TemplateComponent(ComponentType.HEADER, HeaderFormat.IMAGE, null,
                    header.mediaAssetId().toString(), null));
        } else if (!header.text().isBlank()) {
            components.add(new TemplateComponent(ComponentType.HEADER, HeaderFormat.TEXT, header.text(), null, null));
        }
        components.add(new TemplateComponent(ComponentType.BODY, null, body, null, null));
        if (!footerText.isBlank()) {
            components.add(new TemplateComponent(ComponentType.FOOTER, null, footerText, null, null));
        }
        if (!buttons.isEmpty()) {
            components.add(new TemplateComponent(ComponentType.BUTTONS, null, null, null, buttons));
        }
        return components;
    }

    /**
     * 三个扁平槽位 → 按钮列表。按位次收集，<b>中间空一位不占位</b>。
     *
     * <p>为什么不是「一个对象数组」：{@code ToolInputValidator.SUPPORTED_ITEM_KEYS} 里没有
     * {@code properties}，数组元素只能是标量。写成 {@code [{text,url}]} 不是「暂不支持」而是
     * <b>启动失败</b> —— {@code ToolRegistry} 的自检在构造器里跑。这也正是本类一开始就选定
     * 「扁平字段 + 工具侧组装」的原因，两个按钮只是把同一条路走宽。
     *
     * <p>「按位次收集」而不是「要求连着填」：只给第 1、3 个时得到两个按钮，位次仍是模型
     * 给的那个顺序。要求连着填只会多一条模型会踩的规则，换不到任何东西。
     *
     * <p>每一对都在本地判「成对给」。服务层对半个按钮只会说 buttons 不合法，
     * 而模型需要知道的是<b>第几个</b>按钮的哪两个参数要成对 —— 两个槽位下这个区别更重要。
     */
    static List<TemplateButton> urlButtonsOf(Map<String, Object> arguments) {
        List<TemplateButton> buttons = new ArrayList<>();
        for (int index = 1; index <= MAX_URL_BUTTONS; index++) {
            String text = optionalText(arguments, buttonTextKey(index), "");
            String url = optionalText(arguments, buttonUrlKey(index), "");
            if (text.isEmpty() != url.isEmpty()) {
                throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                        buttonTextKey(index) + " 与 " + buttonUrlKey(index) + " 要么一起给、要么都不给");
            }
            if (!text.isEmpty()) {
                buttons.add(new TemplateButton(ButtonType.URL, text, url, null));
            }
        }
        return buttons;
    }

    /**
     * 第 {@code index} 个按钮的文字参数名。
     *
     * <p>第 1 个沿用 {@code buttonText} 而不是改名成 {@code button1Text}：这个参数名已经
     * 进过模型的历史与确认卡片，改名换不到任何东西。{@code public} 是因为确认卡片的渲染
     * （{@code AssistantPendingActionService}，另一个包）要按同样的名字逐槽位取值 ——
     * 让那边自己拼一遍 {@code "button" + n + "Text"} 就等于把命名规则写了两份。
     */
    public static String buttonTextKey(int index) {
        return index == 1 ? "buttonText" : "button" + index + "Text";
    }

    /** 第 {@code index} 个按钮的链接参数名。为什么是 {@code public}：见 {@link #buttonTextKey(int)}。 */
    public static String buttonUrlKey(int index) {
        return index == 1 ? "buttonUrl" : "button" + index + "Url";
    }

    /**
     * {@code ["customer_name=张总"]} → {@code {"customer_name": ["张总"]}}。
     *
     * <p>参数刻意用「字符串数组 + 等号」而不是嵌套对象：{@code ToolInputValidator} 的
     * {@code items.type} 目前只支持 {@code string}（见 {@code checkArray}），
     * 而一个「看起来支持 object、其实只判了外层」的声明比不支持它更糟。
     *
     * <p>这里<b>不校验</b>「变量集合是否与正文一致」—— 那条判据只有一份，
     * 在 {@link WhatsAppTemplateValidator#validateExamples} 里。抄第二份必然漂移，
     * 而且漂移的方向是「工具放行、服务层拒绝」，用户看到的会是两种说法。
     * 工具只负责把 {@code name=值} 这种形状拆开，并在拆不开时说清楚该写成什么样。
     */
    static Map<String, List<String>> examplesOf(Map<String, Object> arguments) {
        Object raw = arguments.get("variableExamples");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> examples = new LinkedHashMap<>();
        for (Object element : list) {
            String entry = element instanceof String text ? text.strip() : "";
            int at = entry.indexOf(EXAMPLE_SEPARATOR);
            if (at <= 0 || at == entry.length() - 1) {
                throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                        "variableExamples 的每一项都要写成「变量名=示例值」，收到「" + entry + "」");
            }
            String variable = entry.substring(0, at).strip();
            String value = entry.substring(at + 1).strip();
            examples.computeIfAbsent(variable, key -> new ArrayList<>()).add(value);
        }
        return examples;
    }

    /** 申请成功（或提交结果未知）时的回话与结构化结果。 */
    private ToolResult applied(AccountTarget target, String name, String category, String language,
                               WhatsAppTemplateApplicationService.OperationView view) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountRef", target.ref());
        data.put("accountName", target.name());
        data.put("name", name);
        data.put("category", category);
        data.put("language", language);
        data.put("templateCode", view.templateCode());
        data.put("operationId", view.operationId() == null ? null : view.operationId().toString());
        data.put("status", view.operationStatus() == null ? null : view.operationStatus().name());

        if (view.operationStatus() == OperationStatus.SUBMISSION_UNKNOWN) {
            // 与发信的 OUTCOME_UNKNOWN 同源：重试 = 第二条真实提交，而模板重名会被平台拒。
            throw new ToolExecutionException(ToolExecutionException.SEND_OUTCOME_UNKNOWN,
                    "模板「" + name + "」可能已经提交给 WhatsApp 了，但系统没能确认结果。"
                            + "请不要重复申请（同名模板会被平台拒绝），"
                            + "先用 chatapp.template_list 看一眼它在不在");
        }
        if (view.operationStatus() == OperationStatus.FAILED) {
            throw new ToolExecutionException(ToolExecutionException.INTERNAL,
                    "模板「" + name + "」提交失败了"
                            + (view.errorCode() == null ? "" : "（" + view.errorCode() + "）")
                            + "，请稍后再试");
        }

        // 措辞停在「已提交」：审核在平台那边，我们只能等。
        // 写成「已通过」会在下一次同步时被事实推翻，而用户已经按它做了决定。
        return ToolResult.ok(
                "已把模板「" + name + "」提交给 WhatsApp 审核（账号：" + target.name()
                        + "，类别：" + category + "）。审核结果要等平台返回，"
                        + "之后可以用 chatapp.template_list 查看状态 —— 现在还不能用它发消息",
                data);
    }

    // ---------- 参数与目标 ----------

    /**
     * 解析 {@code accountRef}。
     *
     * <p>只判形状，不判归属：归属在 {@code createForActor} 里。形状不对要单独报
     * （那是模型该改参数），报成「无权访问」会让它去重新挑账号而不是修写法。
     */
    private AccountTarget accountTarget(UUID userId, Map<String, Object> arguments) {
        String reference = requiredText(arguments, "accountRef");
        UUID accountId = ChatAppAccountCandidates.targetOf(reference);
        if (!ChatAppAccountCandidates.looksLikeReference(reference) || accountId == null) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "账号标识格式不正确，只能取自候选清单里的 accountRef（形如 CHATAPP_ACCOUNT:<uuid>）；"
                            + "请先调用 chatapp.template_list 取候选");
        }
        // 名字只用于回话。查不到就退回 ref，绝不编一个（同 ContactTimelineAssistantTools 的口径）——
        // 拿 ref 回话难读，但它是真的；编一个名字则会让用户在确认卡片上核对一个不存在的账号。
        //
        // 候选查询返回 null 属契约违反（provider 从不返回 null），但这里仍然留一条兜底：
        // 缺了它，一次候选故障会以 NPE 的形式被注册表翻译成 INTERNAL，把「候选查不出来」
        // 说成「系统故障」；而有兜底时回话照常出得来，问题留在日志里。
        ChatAppAccountCandidates available = accounts.available(userId);
        ChatAppAccountCandidates.Item found = available == null ? null : available.find(reference);
        return new AccountTarget(reference, accountId, found == null ? reference : found.name());
    }

    /** 一次调用的目标：原始 ref（data 用）、解析后的 id（服务层用）、可读名字（回话用）。 */
    private record AccountTarget(String ref, UUID id, String name) {
    }

    // ---------- 执行：素材 ----------

    private ToolResult listMedia(UUID userId, Map<String, Object> arguments) {
        ChatAppAccountCandidates available = accounts.available(userId);
        if (available.items().isEmpty()) {
            return ToolResult.discovered(
                    "你名下还没有 WhatsApp（chatapp）账号，所以没有素材可看。"
                            + "WhatsApp 账号需要管理员先完成号码入驻，请联系管理员",
                    Map.of("count", 0, "accounts", 0),
                    available);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<TemplateMediaCandidates.Item> items = new ArrayList<>();
        for (ChatAppAccountCandidates.Item account : available.items()) {
            if (items.size() >= TemplateMediaCandidates.LIBRARY_LIMIT) break;
            UUID accountId = ChatAppAccountCandidates.targetOf(account.id());
            if (accountId == null) continue;
            for (WhatsAppTemplateMediaCatalogService.MediaAsset asset
                    : mediaCatalog.listForActor(userId, accountId, MEDIA_PER_ACCOUNT)) {
                if (items.size() >= TemplateMediaCandidates.LIBRARY_LIMIT) break;
                items.add(new TemplateMediaCandidates.Item(
                        TemplateMediaCandidates.idOf(asset.id()), asset.format().name(),
                        asset.contentType(), asset.sizeBytes()));
                rows.add(mediaRow(account.id(), asset));
            }
        }
        // 候选与 data 必须逐条对得上：data 里列了而候选里没有的那一条，模型引用它会被
        // 编排层按 x-candidateSet 拦掉 —— 那是「工具说能用、下一轮却说不在候选里」这种
        // 最费解的一种失败。所以两者从同一个 items 列表出。
        TemplateMediaCandidates candidates =
                new TemplateMediaCandidates(TemplateMediaCandidates.LIBRARY_LIMIT, items);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assets", rows);
        data.put("count", rows.size());
        return ToolResult.discovered(describeMedia(available, rows.size()), data, candidates);
    }

    private static Map<String, Object> mediaRow(String accountRef,
                                                WhatsAppTemplateMediaCatalogService.MediaAsset asset) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("accountRef", accountRef);
        // mediaRef 与候选 id 是同一个字符串（都出自 TemplateMediaCandidates.idOf）：
        // 数据里给一遍，是为了模型不必从候选清单里反查就能把它填进 template_apply。
        row.put("mediaRef", TemplateMediaCandidates.idOf(asset.id()));
        row.put("format", asset.format().name());
        row.put("contentType", asset.contentType());
        row.put("sizeBytes", asset.sizeBytes());
        row.put("uploadedAt", asset.createdAt() == null ? null : asset.createdAt().toString());
        return row;
    }

    /**
     * 素材清单的回话。
     *
     * <p>「没有素材」这一档必须说清<b>素材从哪来</b>：模型面对一句「没有素材」很容易
     * 回一句「我这边看不到任何图片」就结束，而用户此刻的下一步其实是「那我贴一张图给你」
     * 或者「那我给你个链接」。把两条来路写出来，这句话才有下一步。
     */
    private static String describeMedia(ChatAppAccountCandidates available, int count) {
        if (count == 0) {
            return "你的 " + available.items().size() + " 个 WhatsApp 账号里都没有可用的图片素材。"
                    + "素材有两个来源：用户把图片直接贴在输入框里（客户端会上传），"
                    + "或者用户给出一个图片地址、由 chatapp.template_media_upload 收进来";
        }
        return "共 " + count + " 张可用的图片素材（明细见 assets）。"
                + "其中每一条的 mediaRef 可以直接填进 chatapp.template_apply 的图片头";
    }

    private ToolResult uploadMedia(UUID userId, Map<String, Object> arguments) {
        AccountTarget target = accountTarget(userId, arguments);
        String reference = requiredText(arguments, LINK_REF);
        String url = TemplateMediaLinkCandidates.urlOf(reference);
        if (url == null) {
            throw new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    LINK_REF + " 只能取自候选清单里的地址（形如 " + TemplateMediaLinkCandidates.TYPE
                            + ":https://…）；候选清单就是用户这一条消息里写下的链接");
        }
        WhatsAppTemplateMediaUploadService.MediaAssetView asset;
        try {
            asset = mediaIngest.ingestFromLink(userId, target.id(), url, traceId()).asset();
        } catch (WhatsAppTemplateException error) {
            throw ingestFailure(error);
        }
        // 与 template_apply 同源：提交类动作的结果不确定时不撒谎、也不让模型重试 ——
        // 重试会再传一份同样的字节，而素材是按内容去重的，第二次拿回的是同一条。
        if (asset.assetStatus() == MediaAssetStatus.SUBMISSION_UNKNOWN) {
            throw new ToolExecutionException(ToolExecutionException.SEND_OUTCOME_UNKNOWN,
                    "这张图片可能已经存进来了，但系统没能确认结果。"
                            + "请先用 chatapp.template_media_list 看一眼它在不在，不要直接重试");
        }
        if (asset.assetStatus() != MediaAssetStatus.UPLOADED) {
            throw new ToolExecutionException(ToolExecutionException.INTERNAL,
                    "这张图片没能存进素材库"
                            + (asset.errorCode() == null ? "" : "（" + asset.errorCode() + "）")
                            + "，请稍后再试");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountRef", target.ref());
        data.put("accountName", target.name());
        data.put("url", url);
        data.put("mediaRef", TemplateMediaCandidates.idOf(asset.id()));
        data.put("format", asset.format().name());
        data.put("contentType", asset.contentType());
        data.put("sizeBytes", asset.sizeBytes());
        return ToolResult.ok(
                "这张图片已经存进素材库（账号：" + target.name() + "）。"
                        + "现在可以用 chatapp.template_media_list 看到它，"
                        + "并在 chatapp.template_apply 里把它作为模板的图片头（" + MEDIA_REF + "）",
                data);
    }

    // ---------- 渲染 ----------

    private static String describeList(ChatAppAccountCandidates available, int templateCount) {
        StringBuilder text = new StringBuilder("你有 ").append(available.items().size())
                .append(" 个 WhatsApp 账号，名下共 ").append(templateCount).append(" 个模板");
        List<String> notReady = new ArrayList<>();
        for (ChatAppAccountCandidates.Item account : available.items()) {
            if (!account.templateScopeReady()) {
                notReady.add(account.name());
            }
        }
        if (!notReady.isEmpty()) {
            // 「这个账号还没绑空间」必须说出来：不说的话模型会挑到它，
            // 然后用户收到一句来自服务层的、没有下一步的错误。
            // 注意措辞：没绑空间不等于不能用 —— 申请时会先按凭证补绑（见 createForActor），
            // 所以这里说「会先绑」，不说「用不了」。
            text.append("。其中「").append(String.join("」「", notReady))
                    .append("」还没绑定模板空间，申请时会先按它们的凭证绑定");
        }
        if (templateCount == 0) {
            text.append("。目前还没有申请过任何模板");
        }
        text.append("（明细见 items，账号见 accounts）");
        return text.toString();
    }

    // ---------- 失败翻译 ----------

    /**
     * 服务层词表 → 工具错误码。
     *
     * <p>判据用 {@code statusCode()} 而不是 {@code code()} 字符串：错误码有十几个且会随
     * 平台能力增加，而状态类别是稳定的三类。按码列举会漏掉新加的那个，
     * 而漏掉的后果是模型收到一个它无从处置的 {@code INTERNAL}。
     */
    private static ToolExecutionException translate(WhatsAppTemplateException e) {
        if ("TEMPLATE_VALIDATION_FAILED".equals(e.code())) {
            return new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "模板内容不符合 WhatsApp 的规格：" + renderFieldErrors(e.fieldErrors())
                            + "。改好这几个字段再提交一次", e);
        }
        HttpStatus status = e.statusCode();
        if (status == HttpStatus.CONFLICT) {
            // 账号没绑模板空间 / 号码状态不对：换参数也没用，要去别处做一步。
            return new ToolExecutionException(ToolExecutionException.UNAVAILABLE,
                    "这个账号现在还不能申请模板（模板空间或号码状态没配好），需要管理员处理", e);
        }
        if (status == HttpStatus.FORBIDDEN || status == HttpStatus.NOT_FOUND) {
            return new ToolExecutionException(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND,
                    ToolExecutionException.ACCESS_DENIED_MESSAGE, e);
        }
        if (status == HttpStatus.BAD_REQUEST) {
            return new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "这次申请的内容没能通过校验，请检查后重试", e);
        }
        return new ToolExecutionException(ToolExecutionException.INTERNAL,
                "模板申请失败，请稍后再试", e);
    }

    /**
     * 收图失败的翻译。
     *
     * <p>刻意不复用 {@link #translate}：那个的措辞是按<b>模板</b>写的（「模板内容不符合规格」、
     * 「这个账号现在还不能申请模板」），而这里失败的几乎都是「这个地址不行」——
     * 用模板的话术说，模型会去改模板参数，而真正该做的是换个地址。
     *
     * <p>地址类失败全部落 {@code BAD_REQUEST}（见 {@code TemplateMediaLinkFetcher}），
     * 所以这一档就是把服务层给的那句人话原样交给模型 —— 它已经写得很具体
     * （「只接受 https 开头的图片地址」「这个域名指向的是一个内网地址」），
     * 重写一遍只会丢掉细节。
     */
    private static ToolExecutionException ingestFailure(WhatsAppTemplateException error) {
        HttpStatus status = error.statusCode();
        if (status == HttpStatus.BAD_REQUEST) {
            return new ToolExecutionException(ToolExecutionException.INVALID_ARGUMENT,
                    "这个地址没能收进素材库：" + error.getMessage()
                            + "。请把这句话原样告诉用户，让他换一个地址", error);
        }
        if (status == HttpStatus.FORBIDDEN || status == HttpStatus.NOT_FOUND) {
            return new ToolExecutionException(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND,
                    ToolExecutionException.ACCESS_DENIED_MESSAGE, error);
        }
        return new ToolExecutionException(ToolExecutionException.INTERNAL,
                "存这张图片时服务商那边出错了，请稍后再试", error);
    }

    /**
     * 把服务层的字段错误渲染成模型能照做的中文。
     *
     * <p>只翻译本工具会触碰到的字段名；其余的 key 原样带出 —— 编一份完整词典会随上游漂移，
     * 而带错一个字段名比不翻译更坏（模型会去改一个不存在的参数）。
     */
    private static String renderFieldErrors(Map<String, String> errors) {
        if (errors == null || errors.isEmpty()) {
            return "内容不合法";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : errors.entrySet()) {
            String field = switch (entry.getKey()) {
                case "body.text" -> "正文";
                case "header.text" -> "标题";
                case "footer.text" -> "页脚";
                case "header" -> "标题";
                case "footer" -> "页脚";
                case "category" -> "类别";
                case "components" -> "内容组成部分";
                case "examples" -> "变量示例（正文里每个 $(变量) 都要在 variableExamples 里给示例，"
                        + "且不能多给没用到的变量）";
                default -> entry.getKey();
            };
            parts.add(field + "：" + entry.getValue());
        }
        return String.join("；", parts);
    }

    // ---------- 参数读取 ----------

    private static String requiredText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.strip();
        }
        throw new ToolExecutionException(ToolExecutionException.MISSING_ARGUMENT, "缺少必填参数：" + key);
    }

    /** 可空字段；空白一律当作「没给」（模型很爱传空串表示「不填」），没给时用 {@code fallback}。 */
    private static String optionalText(Map<String, Object> arguments, String key, String fallback) {
        Object value = arguments.get(key);
        return value instanceof String text && !text.isBlank() ? text.strip() : fallback;
    }

    // ---------- 声明构造 ----------

    /**
     * 本类自带的 schema 构造 helper。
     *
     * <p>与 {@code MessageSendAssistantTools} / {@code WeComAssistantTools} 里的同名方法形状相同 ——
     * 这是<b>已知的重复</b>，收敛它们要动另外两个类（其中还有别人在途的 WIP），属独立批次。
     * 新写的类里再抽一个只有自己用的 helper 类不算解决重复，只是把它挪了个地方。
     */
    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        // 安全配置，不是风格选项：身份防线就靠这一条（见 ToolRegistry 的启动自检）。
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> stringSpec(String description, Object... extra) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "string");
        field.put("description", description);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            field.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return field;
    }

    /** 枚举字段。{@code enum} 是 {@code ToolInputValidator} 真的实现过的关键字之一。 */
    private static Map<String, Object> enumSpec(String description, String... values) {
        return stringSpec(description, "enum", List.of(values));
    }

    /**
     * 字符串数组字段。
     *
     * <p>元素只有 {@code type} 与 {@code maxLength} 两个关键字可用 ——
     * {@code ToolInputValidator.SUPPORTED_ITEM_KEYS} 就这么大，多写一个会<b>启动失败</b>
     * （那是刻意的，见该常量注释）。
     */
    private static Map<String, Object> arraySpec(String description, int itemMaxLength, int maxItems) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "string");
        item.put("maxLength", itemMaxLength);

        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "array");
        field.put("description", description);
        field.put("minItems", 1);
        field.put("maxItems", maxItems);
        field.put("items", item);
        return field;
    }

    private static Map<String, Object> referenceTo(String candidateSet, String description) {
        Map<String, Object> field = stringSpec(description);
        field.put(ToolInputValidator.CANDIDATE_SET, candidateSet);
        return field;
    }

    /** 只读注解。{@code readOnlyHint=true} 是「进只读清单」的<b>声明</b>；权威在 {@code AssistantActionPolicy}。 */
    private static McpSchema.ToolAnnotations readOnly() {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(true)
                .destructiveHint(false)
                .idempotentHint(true)
                .openWorldHint(false)
                .build();
    }

    /**
     * 写动作注解。
     *
     * <p>{@code destructiveHint=true}：模板一旦提交就进入外部平台的审核队列，
     * 我们这边撤不回。{@code idempotentHint=false} 也不能写反 ——
     * 重复提交同一个模板不会得到同样的结果，而是撞上重名。
     */
    private static McpSchema.ToolAnnotations write() {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(false)
                .destructiveHint(true)
                .idempotentHint(false)
                .openWorldHint(true)
                .build();
    }

    /**
     * 幂等写动作的注解。
     *
     * <p>与 {@link #write()} 的差别只有 {@code idempotentHint} 一位，但它值得单列：
     * 模型据这一位决定「失败了要不要重试」。模板那条写 {@code false}
     * （重复提交会撞重名，我们撤不回），素材这条写 {@code true}
     * （素材按内容去重，同一个地址再传一次拿回的就是同一条）。
     * 两位都写 {@code true}/{@code false} 通吃，等于让模型在一个它必须判断的地方瞎猜。
     *
     * <p>{@code destructiveHint} 仍是 {@code true}：这一层没有删除素材的能力，
     * 意味着「传错了只能留着」。声明成 {@code false} 会让人以为这条链是可逆的。
     */
    private static McpSchema.ToolAnnotations writeIdempotent() {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(false)
                .destructiveHint(true)
                .idempotentHint(true)
                .openWorldHint(true)
                .build();
    }

    /** 审计用的 trace 前缀：与 HTTP 路径的 trace id 区分开，便于在操作记录里认出「这是助手发起的」。 */
    private static String traceId() {
        return "assistant-" + UUID.randomUUID();
    }
}
