package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F1 的核心：那条<b>按 owner 过滤的读路径</b>。
 *
 * <h1>这个类存在的理由</h1>
 * 旧链路（{@code WeComMessageSummaryController → mapper.search}）全链路没有 {@code where user_id}，
 * {@code installationId} 来自部署常量，语义是「本部署内的全部摘要」。把它接进助手 = 给所有用户
 * 开了别人的群摘要读取（与明确拒掉 {@code Admin*} 是同一条理由）。
 *
 * <p>所以这里不是"再验一遍过滤条件写对了没有"，而是钉住一个<b>结构性</b>的事实：
 * 查询用的 {@code installationId} 只能来自那次授权判定的产物。
 * 只要这一点成立，跨部署/跨用户的摘要就不可能被查到 —— 因为那个值根本不由调用方给出。
 *
 * <h1>顺序也是被测行为</h1>
 * 「功能没装配」必须判在授权<b>之前</b>。反过来的话，企微整体没开时每个群都会回一句
 * 「这个群不在你能查看的范围内」，而真实原因是功能没开 —— 用户会去换群试，试不出结果。
 */
class WeComSummaryReadServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GROUP = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID SESSION_CONVERSATION = UUID.fromString("88888888-8888-4888-8888-888888888888");

    /** 授权判定给出的部署 id —— 与"部署常量"无关，是本用例最要紧的那个值。 */
    private static final UUID AUTHORIZED_INSTALLATION =
            UUID.fromString("44444444-4444-4444-8444-444444444444");

    private static final Instant NOW = Instant.parse("2026-09-23T04:00:00Z");

    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final WeComMessageSummaryRepository repository = mock(WeComMessageSummaryRepository.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<WeComMessageSummaryRepository> provider = mock(ObjectProvider.class);

    private final WeComSummaryReadService service = new WeComSummaryReadService(
            conversations, provider, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void anInaccessibleGroupIsRejectedBeforeASingleSummaryIsRead() {
        when(provider.getIfAvailable()).thenReturn(repository);
        when(conversations.findAccessibleWeComGroup(USER, GROUP)).thenReturn(null);

        assertThatThrownBy(() -> service.read(USER, GROUP, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WECOM_GROUP_NOT_ACCESSIBLE");

        verifyNoInteractions(repository);
    }

    @Test
    void theInstallationIdComesFromTheAuthorizedRowAndNeverFromTheCaller() {
        when(provider.getIfAvailable()).thenReturn(repository);
        when(conversations.findAccessibleWeComGroup(USER, GROUP)).thenReturn(accessible("海运客户群"));
        when(repository.find(any())).thenReturn(page(List.of(), 0));

        service.read(USER, GROUP, 7);

        verify(repository).find(new WeComMessageSummaryRepository.PageQuery(
                AUTHORIZED_INSTALLATION, GROUP, "COMPLETED", expectedFrom(7), null, 0,
                WeComSummaryReadService.LIMIT));
    }

    @Test
    void aMissingSummaryFeatureIsReportedAsUnavailableAndIsCheckedBeforeAuthorization() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.read(USER, GROUP, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WECOM_SUMMARY_UNAVAILABLE");

        // 顺序断言：否则企微没开时每个群都会被回一句"不在你能查看的范围内"，而真实原因是功能没开。
        verifyNoInteractions(conversations);
    }

    @Test
    void theWindowIsNowMinusTheRequestedDays() {
        when(provider.getIfAvailable()).thenReturn(repository);
        when(conversations.findAccessibleWeComGroup(USER, GROUP)).thenReturn(accessible("海运客户群"));
        when(repository.find(any())).thenReturn(page(List.of(), 0));

        service.read(USER, GROUP, 3);

        verify(repository).find(new WeComMessageSummaryRepository.PageQuery(
                AUTHORIZED_INSTALLATION, GROUP, "COMPLETED", expectedFrom(3), null, 0,
                WeComSummaryReadService.LIMIT));
    }

    @Test
    void aDayCountOutsideTheDeclaredRangeNeverReachesTheRepository() {
        when(provider.getIfAvailable()).thenReturn(repository);

        assertThatThrownBy(() -> service.read(USER, GROUP, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.read(USER, GROUP, WeComSummaryReadService.MAX_DAYS + 1))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository, conversations);
    }

    @Test
    void aBlankSummaryIsNotDeliveredAsAnEmptyItem() {
        when(provider.getIfAvailable()).thenReturn(repository);
        when(conversations.findAccessibleWeComGroup(USER, GROUP)).thenReturn(accessible("海运客户群"));
        when(repository.find(any())).thenReturn(page(List.of(
                view("   ", 1_700_000_000L),
                view("客户问了运价", 1_700_000_100L)), 2));

        WeComSummaryReadService.SummaryWindow window = service.read(USER, GROUP, 7);

        assertThat(window.items()).hasSize(1);
        assertThat(window.items().get(0).summary()).isEqualTo("客户问了运价");
    }

    @Test
    void truncationFollowsTheQueryTotalNotTheFilteredItemCount() {
        when(provider.getIfAvailable()).thenReturn(repository);
        when(conversations.findAccessibleWeComGroup(USER, GROUP)).thenReturn(accessible("海运客户群"));
        // 命中 30 条、本次只取回 20 条。
        when(repository.find(any())).thenReturn(page(views(WeComSummaryReadService.LIMIT), 30));

        WeComSummaryReadService.SummaryWindow window = service.read(USER, GROUP, 7);

        assertThat(window.truncated()).isTrue();

        when(repository.find(any())).thenReturn(page(views(2), 2));
        assertThat(service.read(USER, GROUP, 7).truncated()).isFalse();
    }

    @Test
    void whenNothingIsReadableTheServiceSaysWhetherAnyJobExistsAtAll() {
        when(provider.getIfAvailable()).thenReturn(repository);
        when(conversations.findAccessibleWeComGroup(USER, GROUP)).thenReturn(accessible("海运客户群"));

        // 第一次查 COMPLETED 得空，第二次（status=null）发现窗口里其实有任务。
        when(repository.find(any()))
                .thenReturn(page(List.of(), 0))
                .thenReturn(page(List.of(view("还没跑完", 1_700_000_000L)), 1));

        assertThat(service.read(USER, GROUP, 7).hasAnyJob())
                .as("有任务但没完成 = 还在生成 / 失败了，与「这个时间段没内容」是两种回答")
                .isTrue();
        // 两次：第一次查 COMPLETED 得空，才值得再花一次「窗口里有没有任务」的查询。
        verify(repository, times(2)).find(any());

        when(repository.find(any())).thenReturn(page(List.of(), 0)).thenReturn(page(List.of(), 0));

        assertThat(service.read(USER, GROUP, 7).hasAnyJob()).isFalse();
    }

    @Test
    void theExtraProbeIsSkippedWhenThereIsSomethingToShow() {
        when(provider.getIfAvailable()).thenReturn(repository);
        when(conversations.findAccessibleWeComGroup(USER, GROUP)).thenReturn(accessible("海运客户群"));
        when(repository.find(any())).thenReturn(page(views(1), 1));

        assertThat(service.read(USER, GROUP, 7).hasAnyJob()).isTrue();

        // 恰好一次：有摘要可显示时不该再花一次"窗口里有没有任务"的查询。
        verify(repository, times(1)).find(any());
    }

    // ---------- 夹具 ----------

    private static long expectedFrom(int days) {
        return NOW.getEpochSecond() - days * 86_400L;
    }

    private static ConversationMapper.WeComSourceConversationAccessRow accessible(String displayName) {
        return new ConversationMapper.WeComSourceConversationAccessRow(GROUP, AUTHORIZED_INSTALLATION,
                "group:chat-id", "EXTERNAL", displayName, null, "GROUP", SESSION_CONVERSATION);
    }

    private static WeComMessageSummaryRepository.PageResult page(
            List<WeComMessageSummaryRepository.JobView> items, long total) {
        return new WeComMessageSummaryRepository.PageResult(items, total, 0, WeComSummaryReadService.LIMIT);
    }

    private static List<WeComMessageSummaryRepository.JobView> views(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> view("摘要 " + i, 1_700_000_000L + i))
                .toList();
    }

    private static WeComMessageSummaryRepository.JobView view(String summary, long sendTime) {
        return new WeComMessageSummaryRepository.JobView(true, UUID.randomUUID(), AUTHORIZED_INSTALLATION,
                "ww-corp", GROUP, "msg-" + sendTime, sendTime, "COMPLETED", "job-1", summary,
                "{\"raw\":\"request\"}", "{\"raw\":\"response\"}", "OK", null, null, null, 1,
                null, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
    }
}
