package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 把「用户递来的素材 id」收敛成候选这一层。
 *
 * <p>本类钉三件事，它们的共同点是<b>错了都不会抛异常</b>，所以只能靠断言钉住：
 * 归属复核真的跑过（否则别人的素材能挂进自己的模板）、
 * 状态只认 {@code UPLOADED}（否则模型会发起一次注定失败的调用）、
 * 以及没有附件时<b>一个查询都不发</b>（这条路径上的每一句话都会走它）。
 */
class TemplateMediaProviderTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID CHATAPP_ACCOUNT = UUID.randomUUID();
    private static final UUID ASSET = UUID.randomUUID();

    private final ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
    private final TemplateMediaAssetMapper media = mock(TemplateMediaAssetMapper.class);
    private final TemplateMediaProvider provider = new TemplateMediaProvider(accounts, media);

    @Test
    void anUploadedAssetOfTheUsersOwnAccountBecomesACandidate() {
        ChannelAccountEntity account = account(CHATAPP_ACCOUNT);
        when(accounts.findByOwnerAndChannelType(USER, "chatapp")).thenReturn(List.of(account));
        when(media.findByIdAndChannelAccountId(ASSET, CHATAPP_ACCOUNT))
                .thenReturn(Optional.of(asset(ASSET, "UPLOADED", "IMAGE", "image/png", 2048L)));

        TemplateMediaCandidates result = provider.candidatesFor(USER, List.of(ASSET));

        assertThat(result.items()).hasSize(1);
        TemplateMediaCandidates.Item item = result.items().get(0);
        assertThat(item.id()).isEqualTo(TemplateMediaCandidates.idOf(ASSET));
        assertThat(item.format()).isEqualTo("IMAGE");
        assertThat(item.contentType()).isEqualTo("image/png");
        assertThat(item.sizeBytes()).isEqualTo(2048L);
        // 候选里必须是**候选组的 id**（带前缀），不是裸 uuid —— 模型要照抄它填 mediaRef。
        assertThat(result.contains(TemplateMediaCandidates.idOf(ASSET))).isTrue();
        assertThat(result.contains(ASSET.toString())).isFalse();
    }

    /**
     * 两个渠道类型都要查。
     *
     * <p>「chatapp 账号」在库里有两个 {@code channel_type} 取值（{@code chatapp} / {@code whatsapp}），
     * 只查一个会让一半用户的素材在候选里凭空消失，而表现是「助手说你这张图不能用」。
     */
    @Test
    void bothTemplateChannelTypesAreSearched() {
        provider.candidatesFor(USER, List.of(ASSET));

        verify(accounts).findByOwnerAndChannelType(USER, "chatapp");
        verify(accounts).findByOwnerAndChannelType(USER, "whatsapp");
    }

    /**
     * 素材不属于该用户的任何一个账号 ⇒ 静默丢弃。
     *
     * <p>这是那道**跨账号**防线：{@code prepareMedia} 只校验「素材与账号同属」，
     * 如果候选这里不逐个按账号复核，任何人都能把别人的素材 id 填进自己的模板申请。
     * 断言到「查询是带账号做的」这一步，是因为单看返回空集无法区分
     * 「复核过但没命中」与「根本没复核」。
     */
    @Test
    void anAssetThatBelongsToSomebodyElseIsSilentlyDropped() {
        ChannelAccountEntity account = account(CHATAPP_ACCOUNT);
        when(accounts.findByOwnerAndChannelType(USER, "chatapp")).thenReturn(List.of(account));
        // 默认 stub 就是 Optional.empty()：这条素材在该账号名下查不到。
        TemplateMediaCandidates result = provider.candidatesFor(USER, List.of(ASSET));

        assertThat(result.items()).isEmpty();
        verify(media).findByIdAndChannelAccountId(ASSET, CHATAPP_ACCOUNT);
    }

    /**
     * 只有 {@code UPLOADED} 能用，{@code ORPHANED} 刻意不收。
     *
     * <p>这是本类最容易被「顺手放宽」的一格：{@code ORPHANED} 看上去像
     * 「传好了、只是上次没挂上」，但 {@code prepareMedia} 与
     * {@code TemplateMediaAssetMapper.markAttached} 的 SQL 都只认 {@code UPLOADED}
     * ⇒ 收进来等于让模型发起一次注定失败的调用。所以逐条把其它状态钉成「不能用」。
     */
    @Test
    void onlyUploadedAssetsAreUsable() {
        ChannelAccountEntity account = account(CHATAPP_ACCOUNT);
        when(accounts.findByOwnerAndChannelType(USER, "chatapp")).thenReturn(List.of(account));

        for (String status : List.of("PROCESSING", "FAILED", "SUBMISSION_UNKNOWN",
                "ATTACHED", "ATTACHMENT_UNKNOWN", "ORPHANED")) {
            when(media.findByIdAndChannelAccountId(ASSET, CHATAPP_ACCOUNT))
                    .thenReturn(Optional.of(asset(ASSET, status, "IMAGE", "image/png", 2048L)));

            assertThat(provider.candidatesFor(USER, List.of(ASSET)).items())
                    .as("状态 %s 不该进候选", status)
                    .isEmpty();
        }
    }

    /**
     * 没有附件时<b>一个查询都不发</b>。
     *
     * <p>这条不是优化洁癖：绝大多数助手消息不带图，若这里先查一遍账号再发现没素材，
     * 等于给每条消息白加两次查询。断言用 {@code verifyNoInteractions}，
     * 为的是「未来谁把短路去掉」会立刻红，而不是安静地多两次查询。
     */
    @Test
    void noAttachmentMeansNoQueryAtAll() {
        assertThat(provider.candidatesFor(USER, List.of()).items()).isEmpty();
        assertThat(provider.candidatesFor(USER, null).items()).isEmpty();

        verifyNoInteractions(accounts, media);
    }

    /**
     * 一张坏的顶不掉后面那张好的。
     *
     * <p>用户可能一次拖进来两张，其中一张还没传完。丢掉坏的、留下好的，
     * 是「静默丢弃」这个选择的全部意义 —— 否则静默丢弃就只是「整轮都不回答」。
     */
    @Test
    void aBrokenAssetDoesNotCrowdOutAGoodOne() {
        UUID broken = UUID.randomUUID();
        ChannelAccountEntity account = account(CHATAPP_ACCOUNT);
        when(accounts.findByOwnerAndChannelType(USER, "chatapp")).thenReturn(List.of(account));
        when(media.findByIdAndChannelAccountId(broken, CHATAPP_ACCOUNT))
                .thenReturn(Optional.of(asset(broken, "PROCESSING", "IMAGE", "image/png", 1024L)));
        when(media.findByIdAndChannelAccountId(ASSET, CHATAPP_ACCOUNT))
                .thenReturn(Optional.of(asset(ASSET, "UPLOADED", "IMAGE", "image/png", 2048L)));

        TemplateMediaCandidates result = provider.candidatesFor(USER, List.of(broken, ASSET));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(TemplateMediaCandidates.idOf(ASSET));
    }

    /** 列表里混着 null（请求体里的畸形项）不该让整轮消失。 */
    @Test
    void aNullIdInTheListIsSkipped() {
        ChannelAccountEntity account = account(CHATAPP_ACCOUNT);
        when(accounts.findByOwnerAndChannelType(USER, "chatapp")).thenReturn(List.of(account));
        when(media.findByIdAndChannelAccountId(ASSET, CHATAPP_ACCOUNT))
                .thenReturn(Optional.of(asset(ASSET, "UPLOADED", "IMAGE", "image/png", 2048L)));

        // 用可变的 ArrayList：List.of 不允许 null 元素。
        List<UUID> ids = new java.util.ArrayList<>();
        ids.add(null);
        ids.add(ASSET);

        assertThat(provider.candidatesFor(USER, ids).items()).hasSize(1);
    }

    /**
     * {@code size_bytes} 在库里 not null，但实体是 {@code Long}。
     *
     * <p>一个装箱空值不该让整轮消失 —— 候选里报 0 比「助手突然不回答」好。
     */
    @Test
    void aNullSizeBecomesZeroInsteadOfBlowingUp() {
        ChannelAccountEntity account = account(CHATAPP_ACCOUNT);
        when(accounts.findByOwnerAndChannelType(USER, "chatapp")).thenReturn(List.of(account));
        when(media.findByIdAndChannelAccountId(ASSET, CHATAPP_ACCOUNT))
                .thenReturn(Optional.of(asset(ASSET, "UPLOADED", "IMAGE", "image/png", null)));

        assertThat(provider.candidatesFor(USER, List.of(ASSET)).items().get(0).sizeBytes()).isZero();
    }

    private static ChannelAccountEntity account(UUID id) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(id);
        return account;
    }

    private static TemplateMediaAssetEntity asset(UUID id, String status, String format,
                                                  String contentType, Long sizeBytes) {
        TemplateMediaAssetEntity asset = new TemplateMediaAssetEntity();
        asset.setId(id);
        asset.setAssetStatus(status);
        asset.setMediaFormat(format);
        asset.setContentType(contentType);
        asset.setSizeBytes(sizeBytes);
        return asset;
    }

    // ---------- 第八组：用户原话里的地址 ----------

    /**
     * 原话里的链接变成候选，id 里带着明文地址。
     *
     * <p>与附件那条路的关系在这里最清楚：附件走的是「用户递来一个 id、服务端去库里克复核」，
     * 这里走的是「用户递来一段话、服务端从中取出地址」。两者都不依赖任何检索 ——
     * 所以模型编不出可用的值：值不在它手里。
     */
    @Test
    void aLinkInTheUsersOwnWordsBecomesACandidate() {
        TemplateMediaLinkCandidates links = provider.linksIn("用这张图 https://cdn.example.com/a.png 建个模板");

        assertThat(links.items()).hasSize(1);
        assertThat(links.items().get(0).id())
                .isEqualTo(TemplateMediaLinkCandidates.idOf("https://cdn.example.com/a.png"));
        assertThat(links.contains(links.items().get(0).id())).isTrue();
        // 抽链不该碰数据库。
        verifyNoInteractions(accounts, media);
    }

    /**
     * 粘在地址后面的标点不属于地址。
     *
     * <p>「图在这：https://a.com/x.png。」里的句号会被正则一起吃进来，
     * 而带句号的地址在下载时是一个 404 —— 用户看到「图片地址打不开」，却看不出多了一个字。
     */
    @Test
    void trailingPunctuationIsNotPartOfTheAddress() {
        assertThat(urlsIn("图在这：https://a.example.com/x.png。"))
                .containsExactly("https://a.example.com/x.png");
        assertThat(urlsIn("（见 https://a.example.com/x.png）"))
                .containsExactly("https://a.example.com/x.png");
        assertThat(urlsIn("https://a.example.com/x.png, 还有 https://b.example.com/y.jpg"))
                .containsExactly("https://a.example.com/x.png", "https://b.example.com/y.jpg");
    }

    /** 查询串与锚点里的 {@code &}、{@code #} 是地址的一部分，不能被剥掉。 */
    @Test
    void theQueryStringSurvives() {
        assertThat(urlsIn("https://a.example.com/x.png?v=2&size=large"))
                .containsExactly("https://a.example.com/x.png?v=2&size=large");
    }

    @Test
    void onlyTheFirstFewLinksAreOffered() {
        TemplateMediaLinkCandidates links = provider.linksIn(
                "https://a.example.com/1.png https://a.example.com/2.png "
                        + "https://a.example.com/3.png https://a.example.com/4.png");

        assertThat(links.items()).hasSize(TemplateMediaLinkCandidates.LIMIT);
        assertThat(links.limit()).isEqualTo(TemplateMediaLinkCandidates.LIMIT);
    }

    @Test
    void theSameLinkTwiceIsOnlyOneCandidate() {
        assertThat(urlsIn("https://a.example.com/x.png 或者 https://a.example.com/x.png"))
                .containsExactly("https://a.example.com/x.png");
    }

    /** 没写地址就是空候选 —— 这条路径上每句话都会走它，所以它不许有任何副作用。 */
    @Test
    void noLinkMeansAnEmptyCandidateSet() {
        assertThat(provider.linksIn("帮我看下联系人").items()).isEmpty();
        assertThat(provider.linksIn(null).items()).isEmpty();
        assertThat(provider.linksIn("   ").items()).isEmpty();
        // 没有协议头的裸域名不算 —— 只认用户明确写下的 http(s) 链接。
        assertThat(provider.linksIn("图在 cdn.example.com/x.png").items()).isEmpty();
        // 短到不可能是地址的也不收。
        assertThat(provider.linksIn("https://a.b").items()).isEmpty();
        verifyNoInteractions(accounts, media);
    }

    private List<String> urlsIn(String text) {
        return provider.linksIn(text).items().stream()
                .map(item -> TemplateMediaLinkCandidates.urlOf(item.id()))
                .toList();
    }
}
