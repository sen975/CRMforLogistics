package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 企微消息摘要的<b>按 owner 过滤的读取路径</b>。
 *
 * <h2>为什么必须有这个类，而不是让工具直接调 {@code WeComMessageSummaryController} 那条链</h2>
 * 现有读链路是
 * {@code WeComMessageSummaryController → repository.find(PageQuery(installationId, …)) → mapper.search(installationId, …)}，
 * <b>全链路没有一处 {@code where user_id}</b>：{@code installationId} 来自部署常量
 * （{@code config.wecomSuiteId()} / {@code config.wecomLoginAuthCorpId()}），
 * 也就是说那条链路的语义是「本部署内的全部摘要」。
 *
 * <p>这在页面上是正确的（页面本来就只给运维/管理员看），但<b>直接接进助手就会破坏
 * 「只看自己的」这条不变量</b> —— 与明确拒掉 {@code Admin*} 端点是同一条理由。
 * 所以这里不是「加个声明」，而是补一条真·owner 过滤的读路径：
 * <b>先把调用者的可见性判掉，再从那次授权判定里取 {@code installationId}</b>。
 *
 * <h2>授权判定复用 {@link ConversationMapper#findAccessibleWeComGroup}</h2>
 * 那条 SQL 已经把企微的可见性完整表达了一遍，而且已经被三处复用
 * （{@code ConversationPreferenceService}、{@code ThreadService}、{@code WeComGroupNameRefreshService}）：
 * 调用者必须<b>绑定了这个企业</b>（{@code wecom_user_bindings}）、并且是这个群的
 * <b>已观测成员</b>（{@code wecom_parties.provider_party_id = binding.wecom_user_id}
 * 且 {@code participant_status='OBSERVED'}），群所属会话还要满足归属/团队/授权表规则。
 *
 * <p>刻意<b>不</b>在这里重写一份类似的 SQL：一份第二遍的授权实现，与第一遍的任何一次修改
 * 之间都会漂移，而漂移的表现是「页面上看不到、助手里看得到」，或者反过来 ——
 * 两种都不会有测试失败。复用同一条 SQL 让两者在结构上不可能分叉。
 *
 * <h2>{@code installationId} 只能来自授权结果</h2>
 * 这是本类与旧链路唯一但最关键的差别。它带来一个附带好处：跨部署的摘要
 * （{@code installation_id} 不同的）在查询条件里就排除了，不需要额外再判一次。
 *
 * <h2>只回投影，不回原始报文</h2>
 * {@code JobView} 里有 {@code rawRequestJson} / {@code rawResponseJson}（发给企微的请求体
 * 与企微的原始回包）。它们是排障用的内部产物，既不进模型也不需要进 ——
 * 所以本类返回的是自己的 {@link Summary}，而不是把 {@code JobView} 交出去。
 * 类型不同是刻意的：让「顺手把整个 JobView 塞进 data」这种写法连编译都过不了。
 *
 * <h2>两道条件，各表达一件事</h2>
 * 本类挂 {@link ConditionalOnWeComEnabled}（{@code app.wecom-enabled}），与
 * {@code service.wecom} 包内其余 Bean 一致：{@code WeComModuleIsolationTest} 会扫这个包，
 * 断言每个 Spring Bean 都带该注解，意图是企微模块要能<b>整块不存在</b>。
 * 本类最初只靠「注入了 {@code ObjectProvider}、装配不到就当没启用」兜底，而门禁拒了它 ——
 * 那等于把模块边界降级成运行期分支，且每多一条调用路径就要重写一遍这段判断。
 * 模块级的事实就该由模块级注解表达。
 *
 * <p>但这不让下面那道 {@code ObjectProvider} 变多余：{@code app.wecom-enabled=true} 只说明
 * 模块启用，{@code MyBatisWeComMessageSummaryRepository} 还额外要求配了 suite-id
 * （{@code @ConditionalOnExpression}）。「模块开了、摘要能力没配」是真实存在的中间态，
 * 所以两层都留 —— 只是现在各说各的：类是模块级，仓储是配置级。
 */
@Service
@ConditionalOnWeComEnabled
public class WeComSummaryReadService {

    /**
     * 一次读回的摘要条数上限。
     *
     * <p>不暴露成参数：一个群「最近聊了啥」的概览不是分页接口，让模型自己填 limit
     * 只会让它填 100，把一段无界历史搬进提示词（同 {@code ContactTimelineAssistantTools.LIMIT} 的理由）。
     */
    static final int LIMIT = 20;

    /**
     * 回看窗口的上下界，与工具 schema 上的 {@code minimum} / {@code maximum} 同值。
     *
     * <p>{@code public} 是刻意的：工具声明要拿这两个值去写 schema，而「两边各写一个 30」
     * 在有人调大一侧时就会得到一个「schema 说可以、服务层说不合法」的假配置。
     */
    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 30;

    private static final long SECONDS_PER_DAY = 86_400L;

    private final ConversationMapper conversations;
    private final ObjectProvider<WeComMessageSummaryRepository> summaries;
    private final Clock clock;

    public WeComSummaryReadService(ConversationMapper conversations,
                                   ObjectProvider<WeComMessageSummaryRepository> summaries,
                                   Clock clock) {
        this.conversations = conversations;
        this.summaries = summaries;
        this.clock = clock;
    }

    /**
     * 读一个群在最近 {@code days} 天内<b>已完成</b>的 AI 摘要。
     *
     * @throws IllegalStateException   摘要能力本身没装配（企微未启用 / 未配 suite-id）
     * @throws IllegalArgumentException 这个群对这个调用者不可见 —— 与「不存在」刻意不可区分
     */
    public SummaryWindow read(UUID userId, UUID sourceConversationId, int days) {
        if (userId == null) throw new IllegalArgumentException("userId required");
        if (sourceConversationId == null) throw new IllegalArgumentException("sourceConversationId required");
        if (days < MIN_DAYS || days > MAX_DAYS) {
            // schema 上已经声明了 minimum/maximum，正常走不到这里；留着是因为本类是 service，
            // 页面上别处也可能调它，而「参数越界」在 service 层不该靠调用方自觉。
            throw new IllegalArgumentException("days invalid");
        }

        WeComMessageSummaryRepository repository = summaries.getIfAvailable();
        if (repository == null) {
            // 能力缺席与「你没权限」是两件事，不能合并成一句：前者用户该去开配置，后者该换个群。
            // 而且这个判断必须放在授权之前 —— 否则企微整体没启用时，
            // 每个群都会回一句「不在你能查看的范围内」，而真实原因是功能没开。
            throw new IllegalStateException("WECOM_SUMMARY_UNAVAILABLE");
        }

        ConversationMapper.WeComSourceConversationAccessRow source =
                conversations.findAccessibleWeComGroup(userId, sourceConversationId);
        if (source == null) {
            throw new IllegalArgumentException("WECOM_GROUP_NOT_ACCESSIBLE");
        }

        long now = clock.instant().getEpochSecond();
        long from = now - days * SECONDS_PER_DAY;

        WeComMessageSummaryRepository.PageResult page = repository.find(
                new WeComMessageSummaryRepository.PageQuery(source.installationId(), source.sourceConversationId(),
                        "COMPLETED", from, null, 0, LIMIT));

        List<Summary> items = new ArrayList<>();
        for (WeComMessageSummaryRepository.JobView view : page.items()) {
            // 状态已经是 COMPLETED，但摘要仍可能是空串（上游给了空总结仍算完成）。
            // 空摘要对用户等于"什么都没有"，留着只会让模型以为那一条是一条空消息。
            if (view.summary() == null || view.summary().isBlank()) {
                continue;
            }
            items.add(new Summary(Instant.ofEpochSecond(view.sendTime()).toString(), view.summary().strip()));
        }

        // 截断按"查询命中数 > 本次取回数"判，而不是拿过滤后的条数去比：
        // 后者会把「命中的都是空摘要」也报成截断，而那句提示会误导模型缩小时间窗。
        boolean truncated = page.total() > page.items().size();

        // 一条可读的都没有时，再花一次很便宜的查询分辨一件事：这个窗口里究竟有没有摘要任务。
        // 有任务但一条都没完成 = 还在生成 / 生成失败（两者对用户是同一句话：再等等），
        // 一条任务都没有 = 这个时间段确实没有可总结的内容。这两种回答完全不同，
        // 而模型在只有「空列表」时只能瞎猜其中之一。
        boolean hasAnyJob = !items.isEmpty() || repository.find(
                new WeComMessageSummaryRepository.PageQuery(source.installationId(), source.sourceConversationId(),
                        null, from, null, 0, 1)).total() > 0;

        return new SummaryWindow(source.displayName(), items, truncated, hasAnyJob);
    }

    /**
     * 一个群的摘要窗口。
     *
     * @param groupName   群的权威显示名（来自那次授权判定，取不到时为 {@code null}）
     * @param items       已完成的摘要，按消息时间倒序
     * @param truncated   这个时间窗内还有更多已完成摘要，本次只显示了最近的 {@value #LIMIT} 条
     * @param hasAnyJob   这个时间窗内是否存在任何摘要任务（用于区分「还没生成」与「确实没有」）
     */
    public record SummaryWindow(String groupName, List<Summary> items, boolean truncated, boolean hasAnyJob) {

        public SummaryWindow {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * 一条已完成的摘要。
     *
     * <p>{@code occurredAt} 用 ISO-8601 字符串而不是 {@code Instant}：提示词渲染用的
     * {@code ObjectMapper} 不一定注册了 JavaTimeModule，放 {@code Instant} 会让整条 observation
     * 序列化失败，而那种失败的表现是「这一轮什么都没有」，查起来极难
     * （与 {@code ConversationCandidates.Item} 逐字相同的理由）。
     */
    public record Summary(String occurredAt, String summary) {
    }
}
