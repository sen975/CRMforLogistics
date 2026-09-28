package com.crmforlogistics.messagecenter.service.assistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AssistantReplyDeltaExtractor} 的行为。
 *
 * <p>最重要的两条：<b>前缀单调</b>（吐出去的字必须永远是最终答案的前缀 —— 界面上的字收不回来）
 * 和<b>拿不准就闭嘴</b>（宁可晚，不可错）。
 */
class AssistantReplyDeltaExtractorTest {

    /** 一段真实形状的信封：答案是「你好，世界！」。 */
    private static final String ENVELOPE = "{\"decision\":\"reply\",\"reply\":\"你好，世界！\"}";
    private static final String ANSWER = "你好，世界！";

    @Test
    void everyPrefixOfTheStreamStillOnlyEverRevealsAPrefixOfTheAnswer() {
        AssistantReplyDeltaExtractor extractor = new AssistantReplyDeltaExtractor();
        StringBuilder revealed = new StringBuilder();

        // 逐字符喂 —— 最坏的分块方式：字段名、转义、值全都可能在中间被切开。
        for (int i = 1; i <= ENVELOPE.length(); i++) {
            revealed.append(extractor.accept(ENVELOPE.substring(0, i)));
            assertThat(ANSWER).startsWith(revealed.toString());
        }

        assertThat(revealed.toString()).isEqualTo(ANSWER);
    }

    @Test
    void revealsOnlyTheNewPartOnEachCall() {
        AssistantReplyDeltaExtractor extractor = new AssistantReplyDeltaExtractor();

        assertThat(extractor.accept("{\"decision\":\"reply\",\"rep")).isEmpty();
        assertThat(extractor.accept("{\"decision\":\"reply\",\"reply\":\"第一")).isEqualTo("第一");
        assertThat(extractor.accept("{\"decision\":\"reply\",\"reply\":\"第一段")).isEqualTo("段");
        assertThat(extractor.accept("{\"decision\":\"reply\",\"reply\":\"第一段，世界！\"}"))
                .isEqualTo("，世界！");
    }

    @Test
    void decodesJsonEscapes() {
        AssistantReplyDeltaExtractor extractor = new AssistantReplyDeltaExtractor();

        String envelope = "{\"decision\":\"reply\",\"reply\":\"行1\\n行2\\t制表\\\"引号\\\" 反斜杠\\\\ 斜杠\\/\"}";

        assertThat(extractor.accept(envelope)).isEqualTo("行1\n行2\t制表\"引号\" 反斜杠\\ 斜杠/");
    }

    @Test
    void holdsBackEscapesThatAreSplitAcrossChunks() {
        // 两个场景各用一个实例：契约是「喂同一路累积的更长前缀」，拿旧游标去读一段
        // 不相干的新文本会静默返回空串 —— 那是测试写错，不是实现的问题，所以这里不混用。
        AssistantReplyDeltaExtractor newline = new AssistantReplyDeltaExtractor();
        // 块边界刚好切在反斜杠之后：那一位还不能翻译，但前面的 "a" 是确定的。
        String head = "{\"decision\":\"reply\",\"reply\":\"a\\";
        assertThat(newline.accept(head)).isEqualTo("a");
        assertThat(newline.accept(head + "n")).isEqualTo("\n");

        AssistantReplyDeltaExtractor unicode = new AssistantReplyDeltaExtractor();
        // 转义序列在中间被切开：不足 4 位十六进制时一位都不吐。
        String unicodeHead = "{\"decision\":\"reply\",\"reply\":\"x\\u4f6";
        assertThat(unicode.accept(unicodeHead)).isEqualTo("x");
        assertThat(unicode.accept(unicodeHead + "0")).isEqualTo("你");
    }

    @Test
    void decodesUnicodeEscapesIncludingSurrogatePairs() {
        AssistantReplyDeltaExtractor extractor = new AssistantReplyDeltaExtractor();

        // 代理对（surrogate pair）：两个 4 位十六进制的 u 转义拼出一个 emoji。
        String envelope = "{\"decision\":\"reply\",\"reply\":\"\\ud83d\\ude00 ok\"}";

        assertThat(extractor.accept(envelope)).isEqualTo("\uD83D\uDE00 ok");
    }

    @Test
    void staysSilentForAskAndCallDecisions() {
        // 这两条分支没有「给用户看的正文」：question 由服务端从结构化字段组装，
        // call 那一轮该说的话也由服务端说。全程闭嘴。
        assertThat(new AssistantReplyDeltaExtractor()
                .accept("{\"decision\":\"ask\",\"question\":\"哪一条？\",\"missing\":[]}")).isEmpty();
        assertThat(new AssistantReplyDeltaExtractor()
                .accept("{\"decision\":\"call\",\"tool\":\"todo.create\",\"arguments\":{}}")).isEmpty();
    }

    @Test
    void staysSilentWhenTheOutputIsNotAnEnvelopeAtAll() {
        AssistantReplyDeltaExtractor extractor = new AssistantReplyDeltaExtractor();

        assertThat(extractor.accept("我帮你查一下，马上回来。")).isEmpty();
        assertThat(extractor.accept("我帮你查一下，马上回来。顺便把备注也改了")).isEmpty();
    }

    @Test
    void stopsAtTheClosingQuoteOfTheValue() {
        AssistantReplyDeltaExtractor extractor = new AssistantReplyDeltaExtractor();

        assertThat(extractor.accept(ENVELOPE)).isEqualTo(ANSWER);
        // 值闭合之后模型再吐什么都到不了界面 —— 哪怕它后面又写了个别的字段。
        assertThat(extractor.accept("{\"decision\":\"reply\",\"reply\":\"你好，世界！\",\"note\":\"不该出现\"}"))
                .isEmpty();
    }

    @Test
    void staysSilentWhenTheDecisionFieldComesAfterTheReplyField() {
        // 已知边界：只在 decision 之后找 reply。顺序颠倒的信封识别不了 ⇒ 退化成「整段出现」。
        // 这里断言的是「不吐错东西」，不是「能吐」。
        AssistantReplyDeltaExtractor extractor = new AssistantReplyDeltaExtractor();

        assertThat(extractor.accept("{\"reply\":\"答案在前\",\"decision\":\"reply\"}")).isEmpty();
    }

    @Test
    void stopsTrustingTheEnvelopeOnAnUnknownEscape() {
        AssistantReplyDeltaExtractor extractor = new AssistantReplyDeltaExtractor();

        // \x 不是合法 JSON 转义：它之前的部分已经确定，吐了；之后一律闭嘴。
        String bad = "{\"decision\":\"reply\",\"reply\":\"ok\\x\"}";
        assertThat(extractor.accept(bad)).isEqualTo("ok");
        assertThat(extractor.accept(bad + "更多内容")).isEmpty();
    }
}
