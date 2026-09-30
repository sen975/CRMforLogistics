package com.crmforlogistics.messagecenter.service.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code template_media} 这组候选的形状。
 *
 * <p>它和 {@link ChatAppAccountCandidatesTest} 钉的是同一类东西，但多担了一件事：
 * 这组候选的值<b>是用户直接递进请求体的 uuid</b>，模型会照着它往 {@code mediaRef} 里写。
 * 所以前缀认不出来 / 形状不对却抛异常，在这里的后果比账号那组更直接 ——
 * 素材 id 走的是 {@code prepareMedia}，那边一旦拿到一个「像 uuid 但不存在」的值，
 * 报出来的错与「引用格式不对」完全无关，模型据此改不动任何东西。
 */
class TemplateMediaCandidatesTest {

    private static final UUID ASSET = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void theReferenceCarriesItsTypePrefix() {
        assertThat(TemplateMediaCandidates.idOf(ASSET))
                .isEqualTo("TEMPLATE_MEDIA:" + ASSET);
    }

    @Test
    void theTargetComesBackOutOfAWellFormedReference() {
        assertThat(TemplateMediaCandidates.targetOf(TemplateMediaCandidates.idOf(ASSET)))
                .isEqualTo(ASSET);
    }

    /**
     * 形状不对一律返回 {@code null}，不抛。
     *
     * <p>逐条列反例：这几条正是模型最容易写出来的形状（漏前缀、前缀是空的、
     * 把 uuid 换成别的 id、只剩前缀）。
     */
    @Test
    void malformedReferencesReturnNullInsteadOfThrowing() {
        assertThat(TemplateMediaCandidates.targetOf(null)).isNull();
        assertThat(TemplateMediaCandidates.targetOf("")).isNull();
        assertThat(TemplateMediaCandidates.targetOf("no-colon-here")).isNull();      // 没有前缀
        assertThat(TemplateMediaCandidates.targetOf(":abc")).isNull();               // 空前缀
        assertThat(TemplateMediaCandidates.targetOf("TEMPLATE_MEDIA:not-a-uuid")).isNull();
        assertThat(TemplateMediaCandidates.targetOf("TEMPLATE_MEDIA:")).isNull();
    }

    /**
     * {@code targetOf} <b>不</b>校验前缀属于哪一组，也不校验这条引用存在。
     *
     * <p>三个方法的职责是分开的，写清楚是因为很容易以为 {@code targetOf} 会兜住它们
     * （我自己第一版断言就写错了）：
     *
     * <ul>
     *   <li>{@code targetOf} —— 只要求「非空前缀 + 后半是 uuid」。所以别的组的引用
     *       （{@code CHATAPP_ACCOUNT:<uuid>}）照样能取出 uuid。</li>
     *   <li>{@code looksLikeReference} —— 前缀必须是本组的 {@code TEMPLATE_MEDIA}。</li>
     *   <li>{@link #contains} —— 这条引用是否真在本轮候选里。</li>
     * </ul>
     *
     * <p>也就是说「跨组错配」由后两者拦，不是由 {@code targetOf} 拦 ——
     * 这个分工与 {@code ChatAppAccountCandidates} 完全一致（那边也同样不判前缀），
     * 所以这里不该单方面收紧，否则两组行为会不一致。
     */
    @Test
    void targetOfIsPrefixAgnosticByDesign() {
        assertThat(TemplateMediaCandidates.targetOf(ChatAppAccountCandidates.idOf(ASSET)))
                .isEqualTo(ASSET);
        assertThat(TemplateMediaCandidates.looksLikeReference(ChatAppAccountCandidates.idOf(ASSET)))
                .isFalse();
    }

    /** 前缀能认出来 ≠ 这条素材在候选里。两个判据分开，工具层才能分开报错。 */
    @Test
    void looksLikeReferenceJudgesShapeOnly() {
        assertThat(TemplateMediaCandidates.looksLikeReference(TemplateMediaCandidates.idOf(ASSET))).isTrue();
        assertThat(TemplateMediaCandidates.looksLikeReference("TEMPLATE_MEDIA:not-a-uuid")).isTrue();
        assertThat(TemplateMediaCandidates.looksLikeReference(ChatAppAccountCandidates.idOf(ASSET))).isFalse();
        assertThat(TemplateMediaCandidates.looksLikeReference(null)).isFalse();
    }

    @Test
    void containsOnlyAnswersForItemsThatAreActuallyInTheSet() {
        TemplateMediaCandidates set = new TemplateMediaCandidates(
                TemplateMediaCandidates.LIMIT,
                List.of(item("IMAGE")));

        assertThat(set.name()).isEqualTo("template_media");
        assertThat(set.contains(TemplateMediaCandidates.idOf(ASSET))).isTrue();
        assertThat(set.contains(TemplateMediaCandidates.idOf(UUID.randomUUID()))).isFalse();
        // 错配的引用（别的组的前缀）必须落空，否则解析器那道比对就成了摆设。
        assertThat(set.contains(ChatAppAccountCandidates.idOf(ASSET))).isFalse();
        assertThat(set.find(TemplateMediaCandidates.idOf(ASSET)).format()).isEqualTo("IMAGE");
        assertThat(set.find(ChatAppAccountCandidates.idOf(ASSET))).isNull();
        assertThat(set.find(null)).isNull();
    }

    /**
     * 空集合是<b>常态</b>而不是异常：绝大多数消息没有附件。
     *
     * <p>所以它在「用户没发图」时必须是「一个空清单」，工具侧据此判定
     * 「不能给 mediaRef」；而不是一个缺席的清单 —— 缺席会让解析器 fail-closed，
     * 把每个不带图的模板申请都拒掉。
     */
    @Test
    void anEmptySetIsStillASetAndNotNullsDoNotBlowUp() {
        TemplateMediaCandidates empty = new TemplateMediaCandidates(TemplateMediaCandidates.LIMIT, List.of());

        assertThat(empty.items()).isEmpty();
        assertThat(empty.contains(TemplateMediaCandidates.idOf(ASSET))).isFalse();
        assertThat(empty.name()).isEqualTo("template_media");
        // 构造器把 null 归一成空清单：provider 的「没有附件」那条短路路径也返回这个形状。
        assertThat(new TemplateMediaCandidates(TemplateMediaCandidates.LIMIT, null).items()).isEmpty();
    }

    /**
     * {@code format} 的取值必须与服务层的 {@code HeaderFormat} 同名。
     *
     * <p>模型要拿候选里的 {@code format} 去填工具的 {@code headerFormat}，
     * 这里自造一个词（比如 {@code "PICTURE"}）就会让模型写出一个 schema 校验不过的值，
     * 而错误还会被归到「参数不合法」而不是「候选说错了」。
     */
    @Test
    void theFormatIsNamedAfterTheServiceLevelEnum() {
        assertThat(item("IMAGE").format()).isEqualTo("IMAGE");
        assertThat(item("VIDEO").format()).isEqualTo("VIDEO");
        assertThat(item("DOCUMENT").format()).isEqualTo("DOCUMENT");
    }

    private static TemplateMediaCandidates.Item item(String format) {
        return new TemplateMediaCandidates.Item(
                TemplateMediaCandidates.idOf(ASSET), format, "image/png", 2048L);
    }

    // ---------- 合并：附件那张 + 库里那批 ----------

    /**
     * 两条来源都要留着。
     *
     * <p>{@code AssistantContext#withCandidateSet} 对其它候选是「同名替换」，素材这里不行：
     * 同一轮里用户贴了一张图、又问了「库里有什么」，替换会让前一张在模型手上突然失效 ——
     * 而它得到的不是「引用已失效」，是「这个 id 不在候选里」。
     */
    @Test
    void mergingKeepsBothSources() {
        TemplateMediaCandidates.Item attached = named("attachment");
        TemplateMediaCandidates.Item fromLibrary = named("library");
        TemplateMediaCandidates attachment = new TemplateMediaCandidates(
                TemplateMediaCandidates.LIMIT, List.of(attached));
        TemplateMediaCandidates library = new TemplateMediaCandidates(
                TemplateMediaCandidates.LIBRARY_LIMIT, List.of(fromLibrary));

        TemplateMediaCandidates merged = attachment.mergedWith(library);

        assertThat(merged.items()).extracting(TemplateMediaCandidates.Item::id)
                .containsExactly(attached.id(), fromLibrary.id());
        assertThat(merged.contains(attached.id())).isTrue();
        assertThat(merged.contains(fromLibrary.id())).isTrue();
        assertThat(merged.limit())
                .isEqualTo(TemplateMediaCandidates.LIMIT + TemplateMediaCandidates.LIBRARY_LIMIT);
    }

    /** 同一张素材既可能是附件、也可能在库里翻到：重复一条会让模型以为有两张图。 */
    @Test
    void mergingDeduplicatesTheSameAsset() {
        TemplateMediaCandidates.Item same = named("same");
        TemplateMediaCandidates.Item other = named("other");
        TemplateMediaCandidates attachment = new TemplateMediaCandidates(
                TemplateMediaCandidates.LIMIT, List.of(same));
        TemplateMediaCandidates library = new TemplateMediaCandidates(
                TemplateMediaCandidates.LIBRARY_LIMIT, List.of(same, other));

        assertThat(attachment.mergedWith(library).items())
                .extracting(TemplateMediaCandidates.Item::id)
                .containsExactly(same.id(), other.id());
    }

    /** 一边为空时合并必须是零成本的：加载失败、没有附件都走这里。 */
    @Test
    void mergingWithAnEmptySideReturnsTheOtherSide() {
        TemplateMediaCandidates only = new TemplateMediaCandidates(
                TemplateMediaCandidates.LIMIT, List.of(named("only")));
        TemplateMediaCandidates empty = new TemplateMediaCandidates(
                TemplateMediaCandidates.LIBRARY_LIMIT, List.of());

        assertThat(only.mergedWith(empty)).isSameAs(only);
        assertThat(empty.mergedWith(only)).isSameAs(only);
        assertThat(only.mergedWith(null)).isSameAs(only);
    }

    /** 一个 id 稳定、可预测的候选条目：断言里要按 id 比对顺序。 */
    private static TemplateMediaCandidates.Item named(String tag) {
        return new TemplateMediaCandidates.Item(
                TemplateMediaCandidates.idOf(UUID.nameUUIDFromBytes(
                        tag.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                "IMAGE", "image/png", 1024L);
    }
}
