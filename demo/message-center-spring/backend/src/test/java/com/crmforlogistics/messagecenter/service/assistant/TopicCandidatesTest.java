package com.crmforlogistics.messagecenter.service.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 话题候选集：第四组候选，也是唯一一组<b>没有预置窗口</b>的候选。
 *
 * <h2>这组用例防的是「两个 id 空间串味」</h2>
 * 联系人候选与会话候选共用 {@code CONTACT:<uuid>}（它们本来就是同一个 id 空间），
 * 而话题是<b>另一回事</b>：话题 id 是 {@code ai_topics.id}，跟任何 {@code contacts.id} 无关。
 * 如果解析只按「{@code 前缀:uuid} 的形状对不对」来判，一个 {@code CONTACT:<uuid>}
 * 就能被当成合法话题标识收下，然后在服务层以「话题不存在」失败 ——
 * 那时用户看到的原因（话题没了）与真实原因（这不是一个话题）完全不同。
 *
 * <p>所以这里逐条钉住「前缀必须是 TOPIC，别的域的前缀一律不认」。
 */
class TopicCandidatesTest {

    private static final UUID TOPIC = UUID.fromString("88888888-8888-4888-8888-888888888888");

    @Test
    void theIdRoundTrips() {
        String id = TopicCandidates.idOf(TOPIC);

        assertThat(id).isEqualTo("TOPIC:" + TOPIC);
        assertThat(TopicCandidates.targetOf(id)).isEqualTo(TOPIC);
    }

    /**
     * 别域的候选 id 不是话题 id。
     *
     * <p>对照组用的是 {@link ContactCandidates#idOf} 而不是一个字面量：
     * 值本身没意义，有意义的是「同一个 uuid 换成联系人前缀之后必须不认」。
     */
    @Test
    void anotherDomainsReferenceIsNotATopicReference() {
        assertThat(TopicCandidates.targetOf(ContactCandidates.idOf(TOPIC))).isNull();
        assertThat(TopicCandidates.targetOf("WECOM_GROUP:" + TOPIC)).isNull();
    }

    @Test
    void malformedReferencesAreRejectedRatherThanThrowing() {
        assertThat(TopicCandidates.targetOf(null)).isNull();
        assertThat(TopicCandidates.targetOf("")).isNull();
        assertThat(TopicCandidates.targetOf("TOPIC")).isNull();
        assertThat(TopicCandidates.targetOf(":not-a-uuid")).isNull();
        assertThat(TopicCandidates.targetOf("TOPIC:not-a-uuid")).isNull();
    }

    @Test
    void membershipIsDecidedByTheExactId() {
        TopicCandidates candidates = new TopicCandidates(TopicCandidates.LIMIT, List.of(
                new TopicCandidates.Item(TopicCandidates.idOf(TOPIC), "运价谈判", 3L)));

        assertThat(candidates.contains(TopicCandidates.idOf(TOPIC))).isTrue();
        assertThat(candidates.contains(ContactCandidates.idOf(TOPIC))).isFalse();
        assertThat(candidates.contains(null)).isFalse();
        assertThat(candidates.find(TopicCandidates.idOf(TOPIC)).title()).isEqualTo("运价谈判");
        assertThat(candidates.find(TopicCandidates.idOf(TOPIC)).version()).isEqualTo(3L);
        assertThat(candidates.find("TOPIC:" + UUID.randomUUID())).isNull();
    }

    @Test
    void anEmptyWindowIsStillAWindow() {
        // 空候选与「没有这组候选」是两种状态：前者说明读过了、确实没有话题；
        // 后者说明还没读过。解析器对两者的处置相同（都拒），但工具回话要说得出来差别。
        TopicCandidates empty = new TopicCandidates(TopicCandidates.LIMIT, List.of());

        assertThat(empty.items()).isEmpty();
        assertThat(empty.name()).isEqualTo(TopicCandidates.NAME);
        assertThat(empty.heading()).contains("contact.topics_read");
        assertThat(empty.contains(TopicCandidates.idOf(TOPIC))).isFalse();
    }

    @Test
    void aNullItemListBecomesEmptyRatherThanNull() {
        assertThat(new TopicCandidates(TopicCandidates.LIMIT, null).items()).isEmpty();
    }
}
