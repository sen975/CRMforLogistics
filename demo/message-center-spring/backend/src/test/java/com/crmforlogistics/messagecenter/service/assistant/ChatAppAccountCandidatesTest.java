package com.crmforlogistics.messagecenter.service.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code chatapp_account} 这组候选的形状。
 *
 * <p>它没有业务逻辑，但它承担了一个<b>安全前提</b>：申请模板的 {@code accountRef}
 * 是这个系统里少数几个「模型能写出来的 uuid」之一。所以本类钉住的是形状层面的两件事 ——
 * 前缀必须能认出来（否则错配的引用会走到服务层才被发现），
 * 以及形状不对时必须返回 {@code null} 而不是抛（抛出去的异常在工具层会被翻译成一个
 * 与「引用不存在」无关的错误码，模型据此改不动任何东西）。
 */
class ChatAppAccountCandidatesTest {

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void theReferenceCarriesItsTypePrefix() {
        assertThat(ChatAppAccountCandidates.idOf(ACCOUNT))
                .isEqualTo("CHATAPP_ACCOUNT:" + ACCOUNT);
    }

    @Test
    void theTargetComesBackOutOfAWellFormedReference() {
        assertThat(ChatAppAccountCandidates.targetOf(ChatAppAccountCandidates.idOf(ACCOUNT)))
                .isEqualTo(ACCOUNT);
    }

    /**
     * 形状不对一律返回 {@code null}。
     *
     * <p>逐条列反例而不是只测一个：这几条正是模型最可能写出来的形状，
     * 而「返回 null」与「抛异常」在工具层的后果完全不同 —— 前者被翻译成
     * 「引用格式不正确，请先取候选」，后者会变成一个模型无从处置的内部错误。
     */
    @Test
    void malformedReferencesReturnNullInsteadOfThrowing() {
        assertThat(ChatAppAccountCandidates.targetOf(null)).isNull();
        assertThat(ChatAppAccountCandidates.targetOf("")).isNull();
        assertThat(ChatAppAccountCandidates.targetOf("no-colon-here")).isNull();   // 没有前缀
        assertThat(ChatAppAccountCandidates.targetOf(":abc")).isNull();            // 空前缀
        assertThat(ChatAppAccountCandidates.targetOf("CHATAPP_ACCOUNT:not-a-uuid")).isNull();
        assertThat(ChatAppAccountCandidates.targetOf("CHATAPP_ACCOUNT:")).isNull();
    }

    /** 前缀能认出来 ≠ 这条引用存在。两个判据分开，正是为了让工具层能分开报错。 */
    @Test
    void looksLikeReferenceJudgesShapeOnly() {
        assertThat(ChatAppAccountCandidates.looksLikeReference(ChatAppAccountCandidates.idOf(ACCOUNT))).isTrue();
        assertThat(ChatAppAccountCandidates.looksLikeReference("CHATAPP_ACCOUNT:not-a-uuid")).isTrue();
        assertThat(ChatAppAccountCandidates.looksLikeReference("CONTACT:" + ACCOUNT)).isFalse();
        assertThat(ChatAppAccountCandidates.looksLikeReference(null)).isFalse();
    }

    @Test
    void containsOnlyAnswersForItemsThatAreActuallyInTheSet() {
        ChatAppAccountCandidates set = new ChatAppAccountCandidates(
                ChatAppAccountCandidates.LIMIT,
                List.of(new ChatAppAccountCandidates.Item(
                        ChatAppAccountCandidates.idOf(ACCOUNT), "悦为", true)));

        assertThat(set.name()).isEqualTo("chatapp_account");
        assertThat(set.contains(ChatAppAccountCandidates.idOf(ACCOUNT))).isTrue();
        assertThat(set.contains(ChatAppAccountCandidates.idOf(UUID.randomUUID()))).isFalse();
        // 拿别的集合的引用进来必须落空 —— 这是「错配在提问路径就断开」那条设计的最小验证。
        assertThat(set.contains("CONTACT:" + ACCOUNT)).isFalse();
        assertThat(set.find(ChatAppAccountCandidates.idOf(ACCOUNT)).name()).isEqualTo("悦为");
        assertThat(set.find("CONTACT:" + ACCOUNT)).isNull();
    }
}
