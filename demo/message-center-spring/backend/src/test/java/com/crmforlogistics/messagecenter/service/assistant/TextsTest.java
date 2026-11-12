package com.crmforlogistics.messagecenter.service.assistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code Texts} 的三条契约：不切出半个字符、给模型看的截断要自报家门、
 * 标记计入上限（不因为多了句说明就突破预算）。
 *
 * <p>三条都属于「删掉实现也不会有别的测试变红」的性质 —— 截断本身永远不会抛异常，
 * 只会静默给出半份数据。所以必须在这里正面钉住。
 */
class TextsTest {

    /** 「…（已截断）」= 6 个字符（UTF-16 码元）。断言里刻意不写死这个数，用实际长度推。 */
    private static final String MARKER = "…（已截断）";

    @Test
    void nullStaysNull() {
        // 上游大量使用「取不到就是 null」。返回空串会让「没有这个值」与「值是空的」混成一类。
        assertThat(Texts.truncate(null, 10)).isNull();
        assertThat(Texts.truncateForModel(null, 10)).isNull();
    }

    @Test
    void aValueWithinTheLimitIsReturnedUnchanged() {
        assertThat(Texts.truncate("短文本", 10)).isEqualTo("短文本");
        assertThat(Texts.truncate("刚好到界", 4)).as("恰好等于上限时不该截断").isEqualTo("刚好到界");
        assertThat(Texts.truncateForModel("短文本", 10))
                .as("没超长就不该出现标记 —— 否则模型会以为自己看到的是残片")
                .isEqualTo("短文本");
    }

    @Test
    void thePlainTruncationJustCuts() {
        assertThat(Texts.truncate("0123456789", 4)).isEqualTo("0123");
        assertThat(Texts.truncate("0123456789", 0)).isEmpty();
    }

    /** 主用例：模型必须能看出「这不是全部」。 */
    @Test
    void theModelFacingTruncationSaysSoAndStaysWithinTheLimit() {
        String truncated = Texts.truncateForModel("画".repeat(1000), 400);

        assertThat(truncated).as("没有这句话，模型会把半份数据当成全部").endsWith(MARKER);
        assertThat(truncated.length())
                .as("标记计入上限：上限是给模型的预算，不该因为多了句说明就突破")
                .isEqualTo(400);
        assertThat(truncated).startsWith("画".repeat(50));
    }

    /**
     * 上限连标记都放不下时降级为裸截断。
     *
     * <p>正常配置走不到这里（各节上限都在 20 以上），但真到了这一步，
     * 「拼出比上限还长的结果」比「这里没有标记」更坏：上限一旦可以突破，
     * 所有关于提示词体积的推算就都不成立了。
     */
    @Test
    void whenTheLimitCannotFitTheMarkerItDegradesToPlainTruncation() {
        String value = "x".repeat(100);

        assertThat(Texts.truncateForModel(value, MARKER.length()))
                .as("body 为 0 时降级，而不是返回「标记超长」的怪结果")
                .hasSize(MARKER.length());
        assertThat(Texts.truncateForModel(value, 3)).hasSize(3);
    }

    /** 代理对不能被切开：切出半个 emoji 会在序列化后变成替换字符。 */
    @Test
    void aSurrogatePairIsNeverCutInHalf() {
        // "🐾" 是一个码点、两个 char。切在第 1 个 char 之后会留下一个孤立的高代理项。
        String paws = "🐾🐾🐾🐾";

        String cut = Texts.truncate(paws, 3);

        assertThat(cut).as("宁可少一个字，也不产生半个字符").isEqualTo("🐾");
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1)))
                .as("结尾不该停在一个孤立的高代理项上")
                .isFalse();
    }

    @Test
    void theMarkerTruncationIsAlsoSurrogateSafe() {
        String cut = Texts.truncateForModel("🐾".repeat(50), 20);

        assertThat(cut).hasSize(20).endsWith(MARKER);
        // 标记前一个字符是正文的最后一个码元：4 个字符的标记空间放不下整数个占 2 位的 emoji，
        // 所以正文要么是整颗 emoji、要么在退位后更短 —— 总之不能是半颗。
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - MARKER.length() - 1)))
                .as("标记与正文的接缝处不能是半个字符")
                .isFalse();
    }

    /** 自定义标记：observation 那处带着「下一步怎么办」的指引，不能被降级成通用措辞。 */
    @Test
    void aCustomMarkerIsSupported() {
        String custom = "…（结果过长，请缩小范围）";

        String cut = Texts.truncateForModel("长".repeat(100), 30, custom);

        assertThat(cut).hasSize(30).endsWith(custom);
        assertThat(cut).startsWith("长".repeat(30 - custom.length()));
    }
}
