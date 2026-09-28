package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.service.assistant.AssistantException;
import com.crmforlogistics.messagecenter.service.assistant.AssistantTurnResult;
import com.crmforlogistics.messagecenter.service.assistant.AssistantTurnSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 把一轮对话的<b>进行过程</b>写成 SSE。
 *
 * <h2>事件协议</h2>
 * 每帧是一行 {@code data:} 加一个空行，节拍体是 JSON，靠 {@code type} 分辨：
 * <pre>
 * data: {"type":"status","state":"thinking"}
 * data: {"type":"status","state":"reading","tool":"todo.list"}
 * data: {"type":"delta","text":"你"}
 * data: {"type":"reset"}
 * data: {"type":"final","kind":"ANSWER","message":"你好"}
 * </pre>
 *
 * <p><b>为什么把事件名塞进 JSON 的 {@code type}，而不是用 SSE 自带的 {@code event:} 行：</b>
 * 这个端点走 POST，而浏览器的 {@code EventSource} 只支持 GET —— 前端注定要自己读流解析。
 * 既然如此，「读 {@code data} 行 → 解析 JSON → 看 {@code type}」只需要一条解析路径；
 * 再加一层 {@code event:} 行等于把同一个判别信息写两遍，而两处一旦不同步
 * <b>不会有任何东西报错</b>，只会让前端安静地漏掉一类事件。
 *
 * <h2>{@code final} 必发，且永远是最后一帧</h2>
 * 前端拿到它就<b>覆盖</b>已显示的片段（见 {@link AssistantTurnSink} 的契约）。
 * 这条不变量顺带给了前端区分两种失败的能力：流断在半路（始终没等到 {@code final}，
 * 事情是连接坏了）与这一轮真的失败了（{@code final} 里 {@code kind=ERROR} 且带 {@code errorCode}）——
 * 这两件事的处置相反，混成一种，用户要么对着一个永远转圈的占位等下去，要么被叫去重试一个
 * 本来就已经答完的轮次。
 *
 * <h2>响应一旦提交，就没有「HTTP 状态码」这条通道了</h2>
 * 响应头在 {@link #open} 里就刷了出去（理由见该方法）。从那之后
 * {@link GlobalExceptionHandler} 再也写不进任何东西 —— 它想去写一个 503 的 JSON 错误体，
 * 而字节可能已经发出去了。所以<b>这一层必须自己捕获一切</b>，把失败翻译成 {@code final}
 * 事件（见 {@link #run}）。代价是「供应商故障」不再表现为 5xx，运维得看日志
 * （{@code event=assistant.turn_failed}）；换来的是前端只需要一条失败通道。
 *
 * <p>反过来，<b>开流之前</b>的失败仍然走异常映射，因为那时什么都还没写出去：
 * 「功能没开」（{@code ASSISTANT_DISABLED}）就是 503，而不是一个 200 的流内错误。
 * 所以调用方必须把 {@code require(...)} 与取身份都放在 {@link #open} <b>之前</b>。
 *
 * <h2>实现必须不抛异常（{@link AssistantTurnSink} 的契约）</h2>
 * 这些写入发生在读模型响应的那条线程上。抛出去会被上层当成「供应商故障」——
 * 于是「用户关掉了页面」会被说成「AI 服务暂时不可用」。这里把写失败一律吞成
 * 「流已死」（{@code out = null}），之后的写入全部变成 no-op。
 */
final class AssistantEventStream implements AssistantTurnSink {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantEventStream.class);

    private final ObjectMapper objectMapper;

    /**
     * 为 {@code null} 表示这条流已经不可用（打开失败，或客户端已经断开）。
     *
     * <p>刻意用一个可空字段而不是「抛/捕获」来控制流程：断开不是异常情况，
     * 它是<b>正常结束</b>的一种（用户切走了、页面关了）。判空比异常便宜，也不必
     * 让每个写入点都套上 try。
     */
    private ServletOutputStream out;

    private AssistantEventStream(ObjectMapper objectMapper, ServletOutputStream out) {
        this.objectMapper = objectMapper;
        this.out = out;
    }

    /**
     * 把响应切成 SSE 并<b>立刻</b>把响应头刷出去。
     *
     * <p>为什么不等第一个事件再刷：前端 {@code fetch} 要等响应头到达才会 resolve，
     * 而「请求已经被接受」本身就是一条信息。不刷，一轮慢对话的最前面会多出一段
     * 谁也说不清长度的「完全没反应」，和「请求根本没送到」看起来一模一样。
     *
     * <p>代价是响应就此提交，之后 GlobalExceptionHandler 再也插不上手 —— 这正是
     * {@link #run} 存在的理由。这个取舍在类注释里写明。
     *
     * <p>拿不到输出流（客户端已经断了）不算失败：返回一条「死流」，后面全是 no-op。
     */
    static AssistantEventStream open(HttpServletResponse response, ObjectMapper objectMapper) {
        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            // no-transform：不许中间层压缩或改写 —— 缓冲会把「逐字」攒成一坨再一起吐出来。
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
            // nginx：没有它，反向代理会把整条流缓冲到结束，前端一个字都收不到，最后一次性看到全文。
            response.setHeader("X-Accel-Buffering", "no");
            ServletOutputStream stream = response.getOutputStream();
            stream.flush();
            return new AssistantEventStream(objectMapper, stream);
        } catch (IOException unavailable) {
            LOG.debug("event=assistant.stream_unavailable", unavailable);
            return new AssistantEventStream(objectMapper, null);
        }
    }

    /**
     * 跑一轮，并把结果写成 {@code final}。
     *
     * <p>把「跑」交给这一层，而不是让控制器自己 try/catch：「提交之后所有失败都要走 SSE」
     * 于是成为<b>结构</b>而不是纪律 —— 控制器没有机会忘掉那个 catch。
     */
    void run(Supplier<AssistantTurnResult> turn) {
        AssistantTurnResult result;
        try {
            result = turn.get();
        } catch (Throwable failure) {
            result = failureResult(failure);
        }
        // 无论如何都要有 final：没有它，前端无法把「这一轮失败了」与「连接断了」区分开。
        write("final", result);
    }

    @Override
    public void thinking() {
        write("status", Map.of("state", "thinking"));
    }

    @Override
    public void reading(String tool) {
        // 用三元而不是 Map.of(…, "tool", tool)：Map.of 不收 null，一旦 tool 为空会抛 NPE，
        // 而那个 NPE 会被 run() 捕获成「这一轮失败了」—— 一个纯装饰性的字段缺失去掉一次真实回答，
        // 是最不划算的那种失败。
        write("status", tool == null
                ? Map.of("state", "reading")
                : Map.of("state", "reading", "tool", tool));
    }

    @Override
    public void answerDelta(String text) {
        if (text == null || text.isEmpty()) {
            // 空片段不推：展示层不必处理「追加了零个字」这种事件。
            return;
        }
        write("delta", Map.of("text", text));
    }

    @Override
    public void reset() {
        write("reset", Map.of());
    }

    /**
     * 写一帧。
     *
     * <p>两种失败刻意分开记：{@link IOException} 意味着「客户端不想再听了」，是正常的
     * （关页面、切网络），只留一行 debug；序列化失败意味着我们产出了一个无法表示的值，
     * 那是 bug，要 error。把它们记成一档，前者会淹没后者。
     *
     * <p>无论哪种，都把流标记为死掉并<b>不</b>重试：写不进去就是写不进去，
     * 而抛出会被上层错认成供应商故障。
     */
    private void write(String type, Object body) {
        ServletOutputStream target = out;
        if (target == null) {
            return;
        }
        try {
            JsonNode payload = objectMapper.valueToTree(body);
            ObjectNode frame = objectMapper.createObjectNode();
            frame.put("type", type);
            if (payload.isObject()) {
                // 判别字段放最前：`type` 与载荷的键不可能撞（载荷里没有任何东西叫 type），
                // 但摆在前面能让人用肉眼 curl 一次就看懂协议。
                frame.setAll((ObjectNode) payload);
            }
            target.write(("data: " + frame + "\n\n").getBytes(StandardCharsets.UTF_8));
            target.flush();
        } catch (IOException disconnected) {
            out = null;
            LOG.debug("event=assistant.stream_write_failed type={}", type, disconnected);
        } catch (RuntimeException notSerialisable) {
            out = null;
            LOG.error("event=assistant.frame_not_serialisable type={}", type, notSerialisable);
        }
    }

    /**
     * 失败 → {@code final} 事件。
     *
     * <p>{@link AssistantException} 的 message 按定义就是「可以讲给用户听」的
     * （见其类注释，与 {@code GlobalExceptionHandler} 的做法一致），原样带出；
     * 其余异常只给一句通用话术，细节进日志 —— 内部异常信息对用户既无用又危险。
     */
    private static AssistantTurnResult failureResult(Throwable failure) {
        if (failure instanceof AssistantException expected) {
            LOG.warn("event=assistant.turn_failed code={} message={}",
                    expected.code(), expected.getMessage());
            return AssistantTurnResult.error(expected.code(), expected.getMessage());
        }
        LOG.error("event=assistant.turn_failed code=INTERNAL_ERROR", failure);
        return AssistantTurnResult.error("INTERNAL_ERROR", "这一轮我没能完成，请稍后再试");
    }
}
