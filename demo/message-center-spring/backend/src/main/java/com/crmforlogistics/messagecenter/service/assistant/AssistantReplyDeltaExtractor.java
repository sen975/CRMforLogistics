package com.crmforlogistics.messagecenter.service.assistant;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型输出的<b>流式片段</b>里，增量取出「可以给用户看的那段文字」。
 *
 * <h2>为什么需要它：模型吐的不是答案，是信封</h2>
 * 传输层是流式的，但模型输出的正文是一个 JSON 决策信封
 * （{@code {"decision":"reply","reply":"…"}}，见 {@link AssistantDecisionParser}）。
 * 把 {@code delta} 直接推到界面上，用户看到的会是 {@code {"decision":"reply","reply":"你} ——
 * 所以「逐字回答」这件事在服务端必须先做一次提取：只有确认了
 * {@code decision == "reply"}，{@code reply} 字段的值才是答案。
 *
 * <h2>唯一的不变量：前缀单调</h2>
 * 把每次 {@link #accept} 返回的片段按顺序拼起来，<b>必定是最终 reply 值的某个前缀</b>。
 * 这条不变量比「尽量早点吐」重要得多 —— 界面上的字一旦出现就收不回去，
 * 吐错的代价是用户读到一个从未存在过的答案。因此这里对一切拿不准的情况都选择等待：
 * <ul>
 *   <li>转义序列被切在中间（末尾一个孤立的反斜杠，或 {@code &#92;u} 后面不足 4 位十六进制）
 *       ⇒ 这一段<b>不吐</b>，等下一块；</li>
 *   <li>{@code decision} 还没写完整 ⇒ 什么都不吐（值可能正写到一半）；</li>
 *   <li>{@code decision} 是 {@code ask} / {@code call}，或信封根本不是 JSON
 *       ⇒ 永久闭嘴，界面退化成「答案整段出现」，而不是给出半个答案。</li>
 * </ul>
 *
 * <h2>状态是「一轮一次」的</h2>
 * 每个实例只服务一次模型调用，用完即弃 —— 它记着扫描位置，跨轮复用会把上一轮的位置
 * 带到下一轮。编排层每轮 {@code new} 一个。
 */
public final class AssistantReplyDeltaExtractor {

    /** 信封的分支字段。取值只可能是 {@code ask} / {@code call} / {@code reply}（与解析器同口径）。 */
    private static final Pattern DECISION = Pattern.compile("\"decision\"\\s*:\\s*\"([a-z]+)\"");

    /** {@code reply} 字段的值起点（连同开引号一起匹配掉）。 */
    private static final Pattern REPLY_KEY = Pattern.compile("\"reply\"\\s*:\\s*\"");

    private enum Phase {
        /** 还没确认这是不是 {@code reply} 分支。 */
        WAITING_DECISION,
        /** 正在逐字解出 {@code reply} 的值。 */
        REVEALING,
        /** 结束了：值已经闭合、判定为非 reply、或信封不可信。此后一律返回空串。 */
        CLOSED
    }

    private Phase phase = Phase.WAITING_DECISION;
    private int cursor;
    private final StringBuilder pending = new StringBuilder();

    /**
     * 喂入<b>到目前为止累积的完整原始输出</b>，返回本次新增的可展示片段（没有则空串）。
     *
     * <p>接口刻意收「累积全文」而不是「本次增量」：JSON 字符串可能在任何位置被切开 ——
     * 字段名中间、转义序列中间、甚至 {@code &#92;uXXXX} 的中间。只有拿着前缀才判断得出
     * 「这一块够不够安全地解出来」。
     *
     * <p><b>调用方只允许喂「同一个序列的更长的前缀」</b>。要换一段新文本就新建一个实例 ——
     * 它记着扫描游标，拿旧游标去读一个不相干的串会静默返回空串（表现为「逐字失效」）。
     */
    public String accept(String rawOutput) {
        if (phase == Phase.CLOSED || rawOutput == null) {
            return "";
        }
        if (phase == Phase.WAITING_DECISION && !locateReplyValue(rawOutput)) {
            return "";
        }
        reveal(rawOutput);
        return flush();
    }

    /** 确认分支并定位 {@code reply} 的值起点。返回 false 表示「还判断不出来」，什么都不该吐。 */
    private boolean locateReplyValue(String raw) {
        Matcher decision = DECISION.matcher(raw);
        if (!decision.find()) {
            return false;
        }
        if (!"reply".equals(decision.group(1))) {
            // ask / call 这两条分支没有「给用户看的正文」：ask 的问题是结构化字段（由服务端组装
            // 进 QUESTION），call 那一轮该说的话也由服务端说。永久闭嘴。
            phase = Phase.CLOSED;
            return false;
        }
        Matcher key = REPLY_KEY.matcher(raw);
        // 只在 decision 之后找 reply：字段顺序颠倒的信封（reply 在前）这里识别不了，
        // 那就退化成整段出现 —— 少一次逐字，好过拿错字段当答案。
        key.region(decision.end(), raw.length());
        if (!key.find()) {
            return false;
        }
        cursor = key.end();
        phase = Phase.REVEALING;
        return true;
    }

    /** 按 JSON 字符串的转义规则，从 {@code cursor} 往前解，能确定的部分进 {@code pending}。 */
    private void reveal(String raw) {
        while (cursor < raw.length()) {
            char current = raw.charAt(cursor);
            if (current == '"') {
                // 未转义的引号 = 值结束。此后模型再说什么都与这段答案无关。
                phase = Phase.CLOSED;
                cursor++;
                return;
            }
            if (current != '\\') {
                pending.append(current);
                cursor++;
                continue;
            }
            // 转义序列被切在中间：停下等下一块，绝不猜（"\" 后面是什么只有下一块知道）。
            if (cursor + 1 >= raw.length()) {
                return;
            }
            char escaped = raw.charAt(cursor + 1);
            switch (escaped) {
                case '"', '\\', '/' -> {
                    pending.append(escaped);
                    cursor += 2;
                }
                case 'b' -> {
                    pending.append('\b');
                    cursor += 2;
                }
                case 'f' -> {
                    pending.append('\f');
                    cursor += 2;
                }
                case 'n' -> {
                    pending.append('\n');
                    cursor += 2;
                }
                case 'r' -> {
                    pending.append('\r');
                    cursor += 2;
                }
                case 't' -> {
                    pending.append('\t');
                    cursor += 2;
                }
                case 'u' -> {
                    if (cursor + 6 > raw.length()) {
                        return;   // 转义序列被切开，等下一块
                    }
                    int code = hex(raw, cursor + 2);
                    if (code < 0) {
                        // 非法十六进制：这封信封已经不可信，别再往外吐任何东西。
                        phase = Phase.CLOSED;
                        cursor = raw.length();
                        return;
                    }
                    pending.append((char) code);
                    cursor += 6;
                }
                default -> {
                    phase = Phase.CLOSED;
                    cursor = raw.length();
                    return;
                }
            }
        }
    }

    private static int hex(String raw, int from) {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(raw.charAt(from + i), 16);
            if (digit < 0) {
                return -1;
            }
            value = value * 16 + digit;
        }
        return value;
    }

    /** 取出并清空本次确定要展示的文本。返回空串表示「这次没有新东西可吐」。 */
    private String flush() {
        if (pending.isEmpty()) {
            return "";
        }
        String revealed = pending.toString();
        pending.setLength(0);
        return revealed;
    }
}
