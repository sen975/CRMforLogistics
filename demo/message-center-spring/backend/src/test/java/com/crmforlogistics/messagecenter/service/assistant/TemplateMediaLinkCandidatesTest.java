package com.crmforlogistics.messagecenter.service.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第八组候选：用户原话里的图片地址。
 *
 * <p>这里钉的是「模型能不能编出这个 id」这件事的<b>边界</b>。候选 id 里带着明文地址，
 * 所以形状判断必须严到「前缀对、且后面确实有东西」—— 松一格，一个空地址就会通过形状检查，
 * 然后在下载器里变成一句用户看不懂的失败。
 */
class TemplateMediaLinkCandidatesTest {

    private static final String URL = "https://cdn.example.com/quote.png";

    @Test
    void theIdCarriesTheAddressAndTheAddressCanBeRecovered() {
        String id = TemplateMediaLinkCandidates.idOf(URL);

        assertThat(id).isEqualTo("TEMPLATE_MEDIA_LINK:" + URL);
        assertThat(TemplateMediaLinkCandidates.urlOf(id)).isEqualTo(URL);
        assertThat(TemplateMediaLinkCandidates.looksLikeReference(id)).isTrue();
    }

    /**
     * 两组 id 的前缀不同，服务层处理也不同（一个查库、一个去下载）。
     *
     * <p>混起来会造出一个很隐蔽的错：素材 id 被送进上传，或者反过来。
     * 那时错误会出现在<对不上的那一层>，而原因在这一层。
     */
    @Test
    void anIdFromAnotherCandidateSetIsNotThisShape() {
        assertThat(TemplateMediaLinkCandidates.looksLikeReference(
                TemplateMediaCandidates.idOf(UUID.randomUUID()))).isFalse();
        assertThat(TemplateMediaLinkCandidates.urlOf("TEMPLATE_MEDIA:" + UUID.randomUUID())).isNull();
        assertThat(TemplateMediaLinkCandidates.looksLikeReference(null)).isFalse();
        assertThat(TemplateMediaLinkCandidates.looksLikeReference("")).isFalse();
    }

    @Test
    void anEmptyOrTruncatedReferenceIsRejectedRatherThanRecovered() {
        assertThat(TemplateMediaLinkCandidates.urlOf("TEMPLATE_MEDIA_LINK:")).isNull();
        assertThat(TemplateMediaLinkCandidates.urlOf("TEMPLATE_MEDIA_LINK:   ")).isNull();
        // 只有前缀、没有分隔符 —— 它看起来「像」，但不是本组的形状。
        assertThat(TemplateMediaLinkCandidates.looksLikeReference("TEMPLATE_MEDIA_LINK")).isFalse();
    }

    /** {@code contains} 只认精确相等：编排层就是用它把模型编的 id 拦在调用之前。 */
    @Test
    void containsMatchesOnlyTheExactIdOfAListedAddress() {
        TemplateMediaLinkCandidates set = new TemplateMediaLinkCandidates(
                TemplateMediaLinkCandidates.LIMIT,
                List.of(new TemplateMediaLinkCandidates.Item(TemplateMediaLinkCandidates.idOf(URL))));

        assertThat(set.contains(TemplateMediaLinkCandidates.idOf(URL))).isTrue();
        assertThat(set.contains(TemplateMediaLinkCandidates.idOf(URL + "x"))).isFalse();
        assertThat(set.contains(TemplateMediaLinkCandidates.idOf("https://evil.example/x.png"))).isFalse();
        assertThat(set.contains(null)).isFalse();
    }

    /** 空集合也要是个可用的集合：解析器对「没有这组候选」与「候选是空的」按同一个结果处理。 */
    @Test
    void anEmptySetIsStillAValidSet() {
        TemplateMediaLinkCandidates set = new TemplateMediaLinkCandidates(0, null);

        assertThat(set.items()).isEmpty();
        assertThat(set.contains(TemplateMediaLinkCandidates.idOf(URL))).isFalse();
        assertThat(set.name()).isEqualTo("template_media_link");
        assertThat(set.heading()).contains("地址");
    }
}
