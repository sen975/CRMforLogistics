package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 产出「用户在这一轮消息里带上来的素材」这一类候选：{@link TemplateMediaCandidates}（贴的图）
 * 与 {@link TemplateMediaLinkCandidates}（写下的地址）。
 *
 * <h2>与前六组的 provider 是同一件事，但输入不是检索</h2>
 * {@link ChatAppAccountProvider} 那一类是从库里<b>查</b>出候选；这里是把用户<b>指定的 id</b>
 * 收敛成候选。两者共用一套防线：归属（{@code where owner_user_id}）+ 状态。区别只在于
 * 「候选从哪来」—— 所以它仍然是一个 provider，而不是写进编排层的一段校验。
 *
 * <h2>为什么必须复核归属，哪怕 id 是前端传的</h2>
 * 前端传的是素材 id，而素材 id 是可猜的（uuid 不算秘密，但它是**唯一**的防线）。
 * 没有这道复核，任何登录用户都能把别人的素材挂进自己的模板 ——
 * 而 {@code prepareMedia} 只按「属于同一账号」判定，恰恰会放过这种跨账号引用。
 * 所以这里逐个 {@code findByIdAndChannelAccountId} 比对：**素材必须属于该用户名下的 chatapp 账号**。
 */
@Component
public class TemplateMediaProvider {

    private static final Logger log = LoggerFactory.getLogger(TemplateMediaProvider.class);

    /**
     * 可被模板引用的素材状态，只有 {@code UPLOADED}。
     *
     * <p>这个取值与 {@code WhatsAppTemplateApplicationService.prepareMedia}
     * 的判据**同源**，所以刻意**不**收 {@code ORPHANED}：那个状态看着像
     * 「传好了、只是上次没挂上」，但 {@code prepareMedia} 只认 {@code UPLOADED}。
     * 收进来等于让模型发起一次注定失败的调用，再把它转述成一个用户无从下手的错误码 ——
     * 「先放进来再拦」正是这套候选机制要避免的事。
     */
    private static final String USABLE_STATUS = "UPLOADED";

    /**
     * 原始话里的地址：{@code https?://} 之后一直吃到空白。
     *
     * <p>不试图在正则里精确刻画「一个合法地址的结尾」—— 那是做不到的
     * （地址可以合法地以 {@code .} 结尾）。这里只划出粗略的一段，再由
     * {@link #stripTrailingPunctuation} 剥掉粘上来的标点。
     */
    private static final Pattern LINK_PATTERN = Pattern.compile("https?://\\S+");

    /**
     * 可能被粘在地址尾部、其实属于句子而不属于地址的字符。
     *
     * <p>中英文都收：这条消息是中文写的，句号、顿号、右引号都会紧贴着地址出现。
     * 刻意<b>不</b>收 {@code %}、{@code #}、{@code &} —— 它们是查询串与锚点的一部分。
     */
    private static final String TRAILING_PUNCTUATION = ".,;:!?'\"<>)]}，。；：！？、）】》」』”’";

    /** {@code https://a.b} 是 13 个字符；比这更短的「地址」只可能是打错了。 */
    private static final int MIN_LINK_CHARS = "https://a.b".length();

    private final ChannelAccountMapper accounts;
    private final TemplateMediaAssetMapper media;

    public TemplateMediaProvider(ChannelAccountMapper accounts, TemplateMediaAssetMapper media) {
        this.accounts = accounts;
        this.media = media;
    }

    /**
     * 把用户指定的素材 id 收敛成一组候选，最多 {@link TemplateMediaCandidates#LIMIT} 条。
     *
     * <p>不可用的素材（不存在 / 不属于他 / 状态不对）**静默丢弃并记一条 warn**，不抛异常。
     * 理由：用户这一轮说的是**一句话**，图是附加物。因为附加物坏掉就整轮失败，
     * 等于连那句话一起不回答了 —— 而那句话可能是个待办，跟图片毫无关系。
     */
    public TemplateMediaCandidates candidatesFor(UUID userId, List<UUID> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return new TemplateMediaCandidates(TemplateMediaCandidates.LIMIT, List.of());
        }
        List<ChannelAccountEntity> owned = ownedChatAppAccounts(userId);
        List<TemplateMediaCandidates.Item> items = new ArrayList<>();
        for (UUID assetId : assetIds) {
            if (items.size() >= TemplateMediaCandidates.LIMIT) break;
            if (assetId == null) continue;
            TemplateMediaCandidates.Item item = itemOf(owned, assetId);
            if (item == null) {
                log.warn("assistant attachment dropped: asset {} is not usable for user {}", assetId, userId);
                continue;
            }
            items.add(item);
        }
        return new TemplateMediaCandidates(TemplateMediaCandidates.LIMIT, items);
    }

    private List<ChannelAccountEntity> ownedChatAppAccounts(UUID userId) {
        // 归属判据在 SQL 的 where owner_user_id 里，这里不做二次过滤 ——
        // 二次过滤会让人以为防线在 Java 里，从而在改动那条 SQL 时不再谨慎。
        List<ChannelAccountEntity> found = new ArrayList<>();
        for (String type : ChatAppAccountProvider.TEMPLATE_CHANNEL_TYPES) {
            found.addAll(accounts.findByOwnerAndChannelType(userId, type));
        }
        return found;
    }

    /**
     * 从用户原话里抽出图片地址，收成 {@link TemplateMediaLinkCandidates}（第八组候选）。
     *
     * <h2>为什么不在这里判「这个地址能不能下」</h2>
     * 能不能下要发一次网络请求才知道，判据在 {@code TemplateMediaLinkFetcher} ——
     * 而且是唯一一份。在这里再写一套「看起来像不像图片地址」的启发式（后缀是不是 .png），
     * 只会造出一层比下游松、或者比下游严的检查：松了没意义，严了会把一个真能用的地址
     * 挡在候选之外，而用户看到的只是「助手说没看到你的链接」。
     *
     * <h2>抽出来的东西是不可信输入</h2>
     * 用户在消息里写什么都有可能（包括「忽略以上指令」这类话）。这里只做形状筛选，
     * 不做任何改写或转义 —— 改写会掩盖注入而不会消除它（同 {@code CandidateTodo} 的口径）。
     * 真正的防线在下一层：地址只可能被送进上传，而上传是写动作、要过确认卡片。
     */
    TemplateMediaLinkCandidates linksIn(String text) {
        if (text == null || text.isBlank()) {
            return new TemplateMediaLinkCandidates(TemplateMediaLinkCandidates.LIMIT, List.of());
        }
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = LINK_PATTERN.matcher(text);
        while (matcher.find() && seen.size() < TemplateMediaLinkCandidates.LIMIT) {
            String url = stripTrailingPunctuation(matcher.group());
            if (url.length() > MIN_LINK_CHARS) {
                seen.add(url);
            }
        }
        List<TemplateMediaLinkCandidates.Item> items = new ArrayList<>(seen.size());
        for (String url : seen) {
            items.add(new TemplateMediaLinkCandidates.Item(TemplateMediaLinkCandidates.idOf(url)));
        }
        return new TemplateMediaLinkCandidates(TemplateMediaLinkCandidates.LIMIT, items);
    }

    /**
     * 剥掉地址被中文标点或英文句读粘住的尾巴。
     *
     * <p>用户的句子是「用 https://a.com/x.png 建个模板」或者「图在这：https://a.com/x.png。」
     * —— 后者那个句号会被正则一起吃进来，而带着句号的地址在下载时是一个 404，
     * 用户会看到「图片地址打不开」却看不出多了一个字。
     */
    private static String stripTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    private TemplateMediaCandidates.Item itemOf(List<ChannelAccountEntity> owned, UUID assetId) {
        for (ChannelAccountEntity account : owned) {
            Optional<TemplateMediaAssetEntity> found = media.findByIdAndChannelAccountId(assetId, account.getId());
            if (found.isEmpty()) continue;
            TemplateMediaAssetEntity asset = found.get();
            if (!USABLE_STATUS.equals(asset.getAssetStatus())) {
                log.warn("assistant attachment dropped: asset {} is {}", assetId, asset.getAssetStatus());
                return null;
            }
            return new TemplateMediaCandidates.Item(
                    TemplateMediaCandidates.idOf(asset.getId()),
                    asset.getMediaFormat(),
                    asset.getContentType(),
                    // sizeBytes 在库里是 not null，但实体上是 Long —— 一个装箱空值不该让整轮消失。
                    asset.getSizeBytes() == null ? 0L : asset.getSizeBytes());
        }
        return null;
    }
}
