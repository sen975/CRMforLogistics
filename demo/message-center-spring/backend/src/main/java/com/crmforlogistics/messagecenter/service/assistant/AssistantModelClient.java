package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnAssistantEnabled;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.net.ssl.SSLException;

/**
 * OpenAI 兼容的 {@code /v1/chat/completions} 客户端（**流式**），服务于助手编排。
 *
 * <h2>与既有 {@code OpenAiCompatibleTopicGateway} 的关系：刻意不共用</h2>
 * 两者都是「打一个 OpenAI 兼容端点、取文本内容」，看着可以抽一个公共类。
 * 没有抽，理由是它们的**失败语义**不同：话题聚类失败可以重试、可以进死信、可以等下一轮安静窗口；
 * 而助手是同步对用户应答的，失败必须当场变成一句用户可读的话（503「稍后再试」），
 * 且必须与「没听懂」严格区分。把它们合成一个「通用 LLM 客户端」，第一个被压平的
 * 就是这个区别。
 *
 * <h2>为什么是流式 —— 以及它<b>不</b>解决什么</h2>
 * <p><b>它不解决 {@code timeout-seconds}。</b> 2026-09-24 两次实测的结论：
 * <ol>
 *   <li><b>纯 JDK</b>：{@code HttpRequest.timeout(1s)} 下，「服务端 2.5 秒后才发响应头」⇒ 1.0 秒抛
 *       {@code HttpTimeoutException}；而「首块立即到、第二块 2.5 秒后才到」（总时长 2.5 秒）
 *       ⇒ <b>正常读完</b>。JDK 自己这个 timeout 确实只覆盖「等到响应头」。</li>
 *   <li><b>但 Spring 没有用它</b>：{@code JdkClientHttpRequest} 的源码注释明写，这是 JDK-8258397 的
 *       workaround —— 它自己挂一个 {@code TimeoutHandler}，从<b>请求发出</b>起计时，
 *       到点直接 {@code close()} 掉响应流，且那个定时器<b>只在流被 close 时才取消</b>。
 *       于是 {@code setReadTimeout} 的实际语义是<b>整个响应读取过程的总时长</b>，
 *       响应头早到也停不下计时器（两条对照用例见 {@code AssistantModelClientUsageTest}）。</li>
 * </ol>
 * 所以：要放开长回答，<b>只有放大这个窗口一条路</b>，流式帮不上忙。
 *
 * <p>流式换来的是<b>不再有整段静默</b>。非流式下，请求发出后到模型算完为止一个字节都不来；
 * 而生产上那条 {@code java.io.IOException: Operation timed out}（来自 {@code SocketDispatcher.read0}，
 * <b>不是</b> Spring 的 {@code HttpTimeoutException}）正是长静默期的形状。流式下持续有数据。
 *
 * <p>上层拿到的仍是<b>一次完整应答</b>：逐块拼出来的文本直接交给 {@link AssistantDecisionParser} ——
 * 那是 JSON 决策信封，不是给用户看的答案，所以这里既不回调、也不外泄片段。
 *
 * <p>代价是解析要自己做，且请求体必须带 {@code stream_options.include_usage}：
 * 流式下 usage <b>默认不返回</b>，不显式要它，审计里的 token 数会静默变成 {@code null} ——
 * 从「有值」退成「没有」，而没有任何报错说明原因。
 *
 * <h2>三个不可动摇的口径</h2>
 * <ol>
 *   <li>{@code temperature = 0.1} —— 与 {@code OpenAiCompatibleTopicGateway} /
 *       {@code OpenAiCompatibleContactMemoryGateway} 一致；结构化输出要的是稳定而不是多样性。</li>
 *   <li><b>不发 {@code response_format}</b>。阶段 0.3 实测：加与不加同为 25/25 通过、
 *       延迟无差异（0.93s vs 0.97s），零增益。少一处偏离就少一处将来要单独验证的东西。</li>
 *   <li><b>不解析 {@code tools} / 不接受模型的原生 function call</b>：本期统一走
 *       「提示词渲染 + 自己解析 JSON」（设计文档 §7.3），传输层只负责把文本取回来。
 *       <b>流式不改变这一点</b> —— 逐块拼出来的仍然是那一段完整文本，不是给人看的答案片段。</li>
 * </ol>
 *
 * <h2>响应体有界读（流式下换了形态，目的没变）</h2>
 * 一次应答本该只有几百字节。这里对**累计读到的字节**设上限，避免一个异常巨大的流把堆吃掉 ——
 * 这个上限不解决任何正常路径的问题，它只在供应商出错或响应体被替换时起作用。
 */
@Component
@ConditionalOnAssistantEnabled
public class AssistantModelClient {

    private static final Logger log = LoggerFactory.getLogger(AssistantModelClient.class);

    /** 一次应答的读取上限（流式下是**累计**字节数）。正常应答是几百字节量级，4 MiB 只用来兜住异常情况。 */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    /**
     * SSE 帧的形状：逐行 {@code data: {json}}，末帧 {@code data: [DONE]}。
     * OpenAI 兼容端点（含 DeepSeek，2026-09-24 实测）都是这个格式。
     */
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE = "[DONE]";

    private final AssistantConfig config;
    private final ObjectMapper mapper;
    private final RestClient client;

    public AssistantModelClient(AssistantConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()));
        // 端点在这里就解析掉、作为 baseUrl 交给 RestClient，而不是每次调用 .uri(绝对地址) ——
        // 后者在 baseUrl 与绝对 URI 同时存在时行为依赖 UriBuilderFactory 的细节（绝对 URL 是否
        // 真的覆盖 base），不值得为一行代码赌。与 OpenAiCompatibleTopicGateway 取同一写法。
        this.client = RestClient.builder()
                .baseUrl(config.baseUrl().isBlank()
                        ? "http://127.0.0.1"
                        : resolveEndpoint(config.baseUrl()).toString())
                .requestFactory(factory)
                .build();
    }

    /**
     * 打一次模型，返回拼好的文本内容。
     *
     * <p>流式只体现在**传输**上：上层拿到的仍是一次完整应答。逐块拼出的文本交给
     * {@link AssistantDecisionParser} 照旧解析 —— 那是 JSON 决策信封，不是给用户看的答案，
     * 所以这里既不回调、也不外泄片段。
     *
     * @param messages OpenAI 形态的消息数组（由 {@link AssistantPromptBuilder} 生成）
     * @throws AssistantException {@code ASSISTANT_UNAVAILABLE}：不可达 / 超时 / 4xx-5xx / 响应没有内容。
     *                            一律当作「临时不可用」，由前端提示「稍后再试」，**不要**降级成
     *                            「没听懂」——那会把服务故障说成用户的表达问题。
     */
    public ModelReply complete(List<Map<String, String>> messages) {
        return complete(messages, null, null);
    }

    public ModelReply complete(List<Map<String, String>> messages, Integer maxOutputTokens) {
        return complete(messages, maxOutputTokens, null);
    }

    /**
     * 带增量回调的一版。每收到一块非空的 {@code delta.content} 就回调一次，参数是
     * <b>累积到目前为止的原始文本</b>（不是本块增量）—— 「这一块能不能安全地翻译成人话」
     * 取决于它前面的上下文（转义序列、字段名都可能跨块被切开），只有拿着前缀才判得出来，
     * 见 {@link AssistantReplyDeltaExtractor}。
     *
     * <p><b>回调发生在读流的线程上，且必须不抛异常</b>：抛出去会和「供应商故障」混成同一个
     * 503，把「用户关了页面」说成「AI 服务不可用」。写 SSE 时的 {@code IOException}
     * 请自行处理掉。
     */
    public ModelReply complete(List<Map<String, String>> messages, Integer maxOutputTokens,
                               Consumer<String> onRawDelta) {
        long startedNanos = System.nanoTime();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.model());
            body.put("temperature", 0.1);
            body.put("messages", messages);
            body.put("stream", true);
            // 流式下 usage 默认不返回。不加这一项，usage 会静默变成 null ——
            // 审计里的 token 数从「有值」退成「没有」，且没有任何报错来说明原因。
            body.put("stream_options", Map.of("include_usage", true));
            if (maxOutputTokens != null) {
                body.put("max_tokens", maxOutputTokens);
            }

            RestClient.RequestBodySpec request = client.post().body(body);
            if (!config.apiKey().isBlank()) {
                request = request.header("Authorization", "Bearer " + config.apiKey());
            }

            HttpResult http = request.exchange((req, res) -> {
                int status = res.getStatusCode().value();
                // 4xx/5xx 的响应体是普通 JSON 错误对象，不是 SSE —— 照旧整体读回来记日志。
                // 按状态码分流（而不是「试着当 SSE 解一遍」）：供应商出错时响应体形状完全不可控。
                return status >= 400
                        ? new HttpResult(status, readBounded(res.getBody()), null)
                        : new HttpResult(status, null, readStream(res.getBody(), onRawDelta));
            });
            Streamed streamed = http.streamed();
            if (streamed == null) {
                log.warn("assistant provider rejected the request: status={} diagnostic={}",
                        http.status(), providerDiagnostic(http.status()));
                throw new AssistantException(AssistantException.PROVIDER_UNAVAILABLE,
                        "AI 服务暂时不可用，请稍后再试");
            }
            if (streamed.content().isBlank()) {
                // 有响应、但没有内容：多半是供应商侧的策略拦截或内容过滤。
                // 与「连接失败」同样处理（503），因为从用户角度看都是「这次没得到答案」。
                throw new AssistantException(AssistantException.PROVIDER_UNAVAILABLE,
                        "AI 服务没有返回内容，请稍后再试");
            }
            // latencyMs 在这里算才对：exchange 返回时流已经读完，覆盖的是真实的整段生成耗时。
            return new ModelReply(streamed.content(), streamed.model(), elapsedMillis(startedNanos),
                    streamed.usage());
        } catch (AssistantException e) {
            throw e;
        } catch (Exception e) {
            // 不把底层异常消息透给用户（可能含地址、TLS 细节）；只把它记进日志。
            log.warn("assistant provider call failed: diagnostic={}", providerDiagnostic(e), e);
            throw new AssistantException(AssistantException.PROVIDER_UNAVAILABLE,
                    "AI 服务暂时不可用，请稍后再试", e);
        }
    }

    /** 一次模型应答。{@code latencyMs} 是排障用的，会写进审计。 */
    public record ModelReply(String content, String model, int latencyMs, Usage usage) {
        public ModelReply(String content, String model, int latencyMs) {
            this(content, model, latencyMs, null);
        }
    }

    public record Usage(int promptTokens, int completionTokens) {
    }

    /** 非 2xx 时装错误体；2xx 时装拼好的流。两者恰有一个非空。 */
    private record HttpResult(int status, String errorBody, Streamed streamed) {
    }

    /** 逐块拼出来的完整应答。 */
    private record Streamed(String content, String model, Usage usage) {
    }

    /**
     * 读 SSE 流，把 {@code choices[0].delta.content} 一块块拼起来。
     *
     * <p>三件必须容忍的事，都来自实测的真实响应（2026-09-24）：
     * <ul>
     *   <li><b>空块</b>：首块的 {@code delta} 只有 {@code role}、{@code content} 为空串，
     *       末块的 {@code content} 也是空串（那里只带 {@code finish_reason}）。追加空串无害。</li>
     *   <li><b>usage 只在最后一块</b>：它挂在 {@code choices} 为空的那一帧上，
     *       所以不能从首块取；这里每块都试一次，取到即覆盖。</li>
     *   <li><b>非 {@code data:} 行</b>：空行是帧分隔符，代理插入的注释行（以 {@code :} 开头）
     *       也可能出现 —— 一律跳过，不视为错误。</li>
     * </ul>
     *
     * <p>{@code model} 取自响应里的真实值（供应商可能路由到别的模型，实测 DeepSeek 会把
     * 请求的 {@code deepseek-chat} 报成 {@code deepseek-flash}）；拿不到才回退配置值 ——
     * 这个字段会写进审计，空着比写错更糟。
     */
    private Streamed readStream(InputStream body, Consumer<String> onRawDelta) throws IOException {
        StringBuilder content = new StringBuilder();
        String model = null;
        Usage usage = null;
        int consumed = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                consumed += line.length() + 1;
                if (consumed > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("provider stream exceeds " + MAX_RESPONSE_BYTES + " bytes");
                }
                if (!line.startsWith(SSE_DATA_PREFIX)) continue;
                String payload = line.substring(SSE_DATA_PREFIX.length()).trim();
                if (payload.isEmpty()) continue;
                if (SSE_DONE.equals(payload)) break;

                JsonNode chunk = mapper.readTree(payload);
                if (model == null) {
                    String reported = chunk.path("model").asText("");
                    if (!reported.isBlank()) model = reported;
                }
                JsonNode usageNode = chunk.path("usage");
                if (usageNode.isObject()
                        && usageNode.path("prompt_tokens").canConvertToInt()
                        && usageNode.path("completion_tokens").canConvertToInt()) {
                    usage = new Usage(usageNode.path("prompt_tokens").intValue(),
                            usageNode.path("completion_tokens").intValue());
                }
                JsonNode delta = chunk.path("choices").path(0).path("delta").path("content");
                if (delta.isTextual() && !delta.asText().isEmpty()) {
                    content.append(delta.asText());
                    if (onRawDelta != null) {
                        onRawDelta.accept(content.toString());
                    }
                }
            }
        }
        return new Streamed(content.toString(), model == null ? config.model() : model, usage);
    }

    /**
     * {@code base-url} 的三种常见写法都要能用：带 {@code /v1}、带完整路径、或只给域名。
     * 与 {@code OpenAiCompatibleTopicGateway#resolveEndpoint} 同一口径 —— 部署时两个变量
     * 往往填同一个值，行为必须一致。
     */
    static URI resolveEndpoint(String configuredBaseUrl) {
        String value = configuredBaseUrl.trim();
        if (value.endsWith("/v1/chat/completions")) return URI.create(value);
        if (value.endsWith("/v1") || value.endsWith("/v1/")) {
            return URI.create(value.replaceAll("/+$", "") + "/chat/completions");
        }
        return URI.create(value.replaceAll("/+$", "") + "/v1/chat/completions");
    }

    /** 错误体（非 SSE）的有界读。正常错误体只有几百字节。 */
    private static String readBounded(InputStream stream) throws java.io.IOException {
        byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new IllegalStateException("provider response exceeds " + MAX_RESPONSE_BYTES + " bytes");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int elapsedMillis(long startedNanos) {
        long millis = (System.nanoTime() - startedNanos) / 1_000_000L;
        return (int) Math.min(millis, Integer.MAX_VALUE);
    }

    private static String providerDiagnostic(int status) {
        if (status == 401 || status == 403) return "AUTH_FAILED";
        if (status == 404) return "ENDPOINT_OR_MODEL_NOT_FOUND";
        if (status == 408 || status == 429) return "THROTTLED";
        return "HTTP_" + status;
    }

    /** 与 {@code OpenAiCompatibleTopicGateway#providerDiagnostic} 同一套分类，便于两处日志比对。 */
    static String providerDiagnostic(Throwable failure) {
        Throwable current = failure;
        java.util.Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof UnknownHostException) return "DNS_ERROR";
            if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException) return "TIMEOUT";
            if (current instanceof ConnectException) return "CONNECT_ERROR";
            if (current instanceof SSLException) return "TLS_ERROR";
            current = current.getCause();
        }
        return "CLIENT_ERROR";
    }
}
