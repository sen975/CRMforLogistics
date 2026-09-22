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

import java.io.InputStream;
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

import javax.net.ssl.SSLException;

/**
 * OpenAI 兼容的 {@code /v1/chat/completions} 客户端，服务于助手编排。
 *
 * <h2>与既有 {@code OpenAiCompatibleTopicGateway} 的关系：刻意不共用</h2>
 * 两者都是「打一个 OpenAI 兼容端点、取 {@code choices[0].message.content}」，看着可以抽一个公共类。
 * 没有抽，理由是它们的**失败语义**不同：话题聚类失败可以重试、可以进死信、可以等下一轮安静窗口；
 * 而助手是同步对用户应答的，失败必须当场变成一句用户可读的话（503「稍后再试」），
 * 且必须与「没听懂」严格区分。把它们合成一个「通用 LLM 客户端」，第一个被压平的
 * 就是这个区别。
 *
 * <h2>三个不可动摇的口径</h2>
 * <ol>
 *   <li>{@code temperature = 0.1} —— 与 {@code OpenAiCompatibleTopicGateway} /
 *       {@code OpenAiCompatibleContactMemoryGateway} 一致；结构化输出要的是稳定而不是多样性。</li>
 *   <li><b>不发 {@code response_format}</b>。阶段 0.3 实测：加与不加同为 25/25 通过、
 *       延迟无差异（0.93s vs 0.97s），零增益。少一处偏离就少一处将来要单独验证的东西。</li>
 *   <li><b>不解析 {@code tools} / 不接受模型的原生 function call</b>：本期统一走
 *       「提示词渲染 + 自己解析 JSON」（设计文档 §7.3），传输层只负责把文本取回来。</li>
 * </ol>
 *
 * <h2>响应体有界读</h2>
 * 一次应答本该只有几百字节。用 {@code readNBytes} 设上限，避免一个异常巨大的响应把堆吃掉 ——
 * 这个上限不解决任何正常路径的问题，它只在供应商出错或响应体被替换时起作用。
 */
@Component
@ConditionalOnAssistantEnabled
public class AssistantModelClient {

    private static final Logger log = LoggerFactory.getLogger(AssistantModelClient.class);

    /** 一次应答的读取上限。正常应答是几百字节量级，4 MiB 只用来兜住异常情况。 */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

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
     * 打一次模型，返回文本内容。
     *
     * @param messages OpenAI 形态的消息数组（由 {@link AssistantPromptBuilder} 生成）
     * @throws AssistantException {@code ASSISTANT_UNAVAILABLE}：不可达 / 超时 / 4xx-5xx / 响应没有内容。
     *                            一律当作「临时不可用」，由前端提示「稍后再试」，**不要**降级成
     *                            「没听懂」——那会把服务故障说成用户的表达问题。
     */
    public ModelReply complete(List<Map<String, String>> messages) {
        long startedNanos = System.nanoTime();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.model());
            body.put("temperature", 0.1);
            body.put("messages", messages);

            RestClient.RequestBodySpec request = client.post().body(body);
            if (!config.apiKey().isBlank()) {
                request = request.header("Authorization", "Bearer " + config.apiKey());
            }

            HttpResult http = request.exchange((req, res) -> new HttpResult(
                    res.getStatusCode().value(),
                    readBounded(res.getBody())));
            if (http.status() >= 400) {
                log.warn("assistant provider rejected the request: status={} diagnostic={}",
                        http.status(), providerDiagnostic(http.status()));
                throw new AssistantException(AssistantException.PROVIDER_UNAVAILABLE,
                        "AI 服务暂时不可用，请稍后再试");
            }
            JsonNode root = mapper.readTree(http.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            String model = root.path("model").asText(config.model());
            if (content.isBlank()) {
                // 有响应、但没有内容：多半是供应商侧的策略拦截或内容过滤。
                // 与「连接失败」同样处理（503），因为从用户角度看都是「这次没得到答案」。
                throw new AssistantException(AssistantException.PROVIDER_UNAVAILABLE,
                        "AI 服务没有返回内容，请稍后再试");
            }
            return new ModelReply(content, model, elapsedMillis(startedNanos));
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
    public record ModelReply(String content, String model, int latencyMs) {
    }

    private record HttpResult(int status, String body) {
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
