package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP 边界上的入参整理：给编排层一份**已经合规**的输入。
 *
 * <h2>为什么单独一个类，而不是写在控制器或编排服务里</h2>
 * 两处都不合适。写在控制器里，编排服务就得自己再防一遍（否则它是「只在 HTTP 下安全」的服务）；
 * 写在编排服务里，控制器测试就必须真的把编排服务跑起来才能验「超长 → 400」——
 * 而那条测试要断言的恰恰是「请求还没进业务就被挡下」。
 * 这个类把「请求形状」独立出来，两侧都能单独验。
 *
 * <h2>当前原话与历史用两套处置：一个报错，一个裁剪</h2>
 * 看起来不一致，但两者性质不同：
 *
 * <ul>
 *   <li><b>本轮原话</b>是用户刚发出的指令，是这一次请求的全部意图。超长就报 400 ——
 *       静默截断一份指令会让模型按半个诉求去执行，比拒绝危险得多。</li>
 *   <li><b>历史</b>是纯语境，只为让「那条」「改到后天」这类指代能被理解。丢最旧的几轮
 *       不影响本轮意图，而为了让语境超限而拒绝整条指令是本末倒置。</li>
 * </ul>
 *
 * <p>两者共同的效果是提示词体积有界 —— 这也是这些上限存在的真实理由：它们首先是成本与
 * 注入面的控制，其次才是用户体验。
 *
 * <h2>但裁剪必须被看见</h2>
 * 上面的分工解释了「为什么可以丢历史」，没有解释「为什么可以**悄悄**丢」。
 * 一段被静默截短的历史会让模型拿着断掉的开头自信作答，而用户在界面上看不到任何异常 ——
 * 这种失败比报错难发现得多。所以这里把「丢了多少条」当作返回值的一部分交出去，
 * 由调用方决定怎么让用户看见（见 {@link AssistantTurnResult#withTrimmedHistory}）。
 * 裁剪本身不变，变的只是它不再无声。
 */
@Component
public class AssistantRequestGuard {

    private final AssistantConfig config;

    public AssistantRequestGuard(AssistantConfig config) {
        this.config = config;
    }

    public NormalisedRequest normalise(List<AssistantMessage> history, String text) {
        if (text == null || text.isBlank()) {
            throw new AssistantException(AssistantException.REQUEST_INVALID, "请先输入你想让我做什么");
        }
        String trimmed = text.strip();
        if (trimmed.length() > config.maxMessageChars()) {
            throw new AssistantException(AssistantException.REQUEST_INVALID,
                    "这条消息太长了（上限 " + config.maxMessageChars() + " 个字符）");
        }
        Trimmed trimmedHistory = trimHistory(history);
        return new NormalisedRequest(trimmed, trimmedHistory.kept(), trimmedHistory.dropped());
    }

    /**
     * 从最新往旧保留，直到轮数或字符数触顶。保留的是**最近**的语境 ——
     * 指代（「那条」）永远指向最近说过的内容，先丢最旧的才不会破坏它。
     *
     * <p>顺带数出**丢掉了多少条**。裁剪策略一个字没变，多出来的只是「这件事被记下来」。
     */
    private Trimmed trimHistory(List<AssistantMessage> history) {
        if (history == null || history.isEmpty()) {
            return new Trimmed(List.of(), 0);
        }
        // 分母是「有效历史」：空消息既不占语义也不占额度，所以不该计进总条数 ——
        // 否则「丢了 3 条」里会混进本来就没有内容的行，那个数字会变得不可信。
        int usable = 0;
        for (AssistantMessage message : history) {
            if (message != null && message.text() != null && !message.text().isBlank()) {
                usable++;
            }
        }

        List<AssistantMessage> kept = new ArrayList<>();
        int budget = config.maxHistoryChars();
        for (int index = history.size() - 1; index >= 0; index--) {
            AssistantMessage message = history.get(index);
            if (message == null || message.text() == null || message.text().isBlank()) {
                // 空消息不占额度也不占轮数：它既没有语境价值，也不该把一条有效历史挤出窗口。
                continue;
            }
            if (kept.size() >= config.maxHistoryTurns()) {
                break;
            }
            int length = message.text().length();
            if (length > budget) {
                // 单条就超预算：丢弃它并停止。截半条历史比没有历史更容易误导模型。
                break;
            }
            budget -= length;
            kept.add(0, new AssistantMessage(message.role() == null
                    ? AssistantMessage.Role.USER : message.role(), message.text()));
        }
        return new Trimmed(List.copyOf(kept), usable - kept.size());
    }

    /**
     * 裁剪的完整结果。
     *
     * <p>{@code dropped} 恒为非负：{@code kept} 里的每一条都来自被计数的有效条数。
     */
    private record Trimmed(List<AssistantMessage> kept, int dropped) {
    }

    /**
     * 一份可以直接交给编排层的输入。
     *
     * <p>{@code droppedHistoryMessages} 是「有内容但没能带上」的历史条数，0 表示一条没丢。
     * 它与 {@code history} 一起返回而不是由调用方自己比对，是因为**只有这里知道分母**——
     * 让调用方去猜「原本有多少条有效历史」，就得把空白过滤规则复制第二份。
     */
    public record NormalisedRequest(String text, List<AssistantMessage> history, int droppedHistoryMessages) {
    }
}
