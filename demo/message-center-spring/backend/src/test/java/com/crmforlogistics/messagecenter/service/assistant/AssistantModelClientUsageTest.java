package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AssistantModelClient} 的传输层行为。
 *
 * <p>2026-09-24 改成流式之后，这里守住五件事：逐块拼接、usage 的来源（末帧）、请求体里的
 * {@code stream} / {@code stream_options.include_usage}、错误与空流的分类，以及
 * 「首块早到 ⇒ 之后的块不再受 timeout 约束」—— 最后一条是这个改动的**收益本身**，
 * 它在非流式实现下必然失败。
 */
class AssistantModelClientUsageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void usageIsOptionalAndMissingUsageStaysUnavailable() {
        AssistantModelClient.ModelReply missing = new AssistantModelClient.ModelReply("ok", "test", 4);
        AssistantModelClient.ModelReply present = new AssistantModelClient.ModelReply(
                "ok", "test", 4, new AssistantModelClient.Usage(12, 5));

        assertThat(missing.usage()).isNull();
        assertThat(present.usage().promptTokens()).isEqualTo(12);
        assertThat(present.usage().completionTokens()).isEqualTo(5);
    }

    @Test
    void assemblesStreamedChunksAndAsksForUsage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = start(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sse(exchange, List.of(
                    // 首块：只有 role，content 是空串（DeepSeek 实测的真实形状）。
                    chunk("", null),
                    chunk("{\"decision\":\"reply\",", null),
                    chunk("\"reply\":\"收到\"}", null),
                    // 末块：content 也是空串，usage 挂在这一帧上。
                    chunk("", Map.of("prompt_tokens", 17, "completion_tokens", 4))));
        });
        try {
            AssistantModelClient client = new AssistantModelClient(config(server, 3), MAPPER);

            AssistantModelClient.ModelReply reply = client.complete(
                    List.of(Map.of("role", "user", "content", "hi")), 123);

            assertThat(reply.content()).isEqualTo("{\"decision\":\"reply\",\"reply\":\"收到\"}");
            // 供应商可能把请求的 deepseek-chat 路由成 deepseek-flash：审计要记真实值。
            assertThat(reply.model()).isEqualTo("deepseek-flash");
            assertThat(reply.usage().promptTokens()).isEqualTo(17);
            assertThat(reply.usage().completionTokens()).isEqualTo(4);
            assertThat(requestBody.get())
                    .contains("\"max_tokens\":123")
                    .contains("\"stream\":true")
                    .contains("\"include_usage\":true");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamWithoutUsageLeavesItUnavailable() throws Exception {
        HttpServer server = start(exchange -> sse(exchange, List.of(chunk("ok", null))));
        try {
            AssistantModelClient client = new AssistantModelClient(config(server, 3), MAPPER);

            assertThat(client.complete(List.of(Map.of("role", "user", "content", "hi"))).usage()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void emptyStreamIsReportedAsUnavailable() throws Exception {
        HttpServer server = start(exchange -> sse(exchange, List.<String>of()));
        try {
            AssistantModelClient client = new AssistantModelClient(config(server, 3), MAPPER);

            assertThatThrownBy(() -> client.complete(List.of(Map.of("role", "user", "content", "hi"))))
                    .isInstanceOf(AssistantException.class)
                    .hasMessageContaining("没有返回内容");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpErrorIsReportedAsUnavailableWithoutTouchingTheStream() throws Exception {
        HttpServer server = start(exchange -> {
            byte[] body = "{\"error\":{\"message\":\"rate limited\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
        });
        try {
            AssistantModelClient client = new AssistantModelClient(config(server, 3), MAPPER);

            assertThatThrownBy(() -> client.complete(List.of(Map.of("role", "user", "content", "hi"))))
                    .isInstanceOf(AssistantException.class)
                    .hasMessageContaining("暂时不可用");
        } finally {
            server.stop(0);
        }
    }

    /**
     * 流式**不能**规避 {@code readTimeout} —— 把这条证据钉在这里，免得后来人（包括我）
     * 再得出「响应头早到就不再计时」这个错结论。
     *
     * <p>Spring 6.2.6 的 {@code JdkClientHttpRequest} 刻意<b>没有</b>把 readTimeout 交给
     * {@code HttpRequest.Builder.timeout()}（源码注释明写是 JDK-8258397 的 workaround），
     * 而是自己挂了一个 {@code TimeoutHandler}：从**请求发出**起计时，到点直接
     * {@code close()} 掉响应流；那个定时器**只在流被 close 时才取消**。
     * 所以这个窗口覆盖整个响应读取过程，与是不是流式无关：首块立即到达并不能停下计时器。
     *
     * <p>对照组见 {@link #theSameStreamCompletesWhenTheWindowCoversTheWholeResponse()}：
     * 同一个流，窗口放大到 5 秒（&gt; 总时长 1.5 秒）⇒ 正常读完。两条一起说明
     * {@code assistant.timeout-seconds} 是**总时长**，不是「等首字节的时间」。
     */
    @Test
    void readTimeoutCoversTheWholeResponseEvenWhenStreamed() throws Exception {
        HttpServer server = start(AssistantModelClientUsageTest::writeTwoChunksOneAndAHalfSecondsApart);
        try {
            AssistantModelClient client = new AssistantModelClient(config(server, 1), MAPPER);

            assertThatThrownBy(() -> client.complete(List.of(Map.of("role", "user", "content", "hi"))))
                    .isInstanceOf(AssistantException.class)
                    .hasMessageContaining("暂时不可用");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theSameStreamCompletesWhenTheWindowCoversTheWholeResponse() throws Exception {
        HttpServer server = start(AssistantModelClientUsageTest::writeTwoChunksOneAndAHalfSecondsApart);
        try {
            AssistantModelClient client = new AssistantModelClient(config(server, 5), MAPPER);

            AssistantModelClient.ModelReply reply = client.complete(
                    List.of(Map.of("role", "user", "content", "hi")));

            assertThat(reply.content()).isEqualTo("first-second");
        } finally {
            server.stop(0);
        }
    }

    /** 首块立即写、停 1.5 秒、再写完剩下的 —— 总时长刻意超过 1 秒的窗口。 */
    private static void writeTwoChunksOneAndAHalfSecondsApart(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(("data: " + chunk("first", null) + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            sleep(1500);
            out.write(("data: " + chunk("-second", null) + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
        } catch (IOException ignored) {
            // 客户端可能已按超时断开 —— 那正是前一个用例要观察的结果，写失败在这里不是失败的原因。
        }
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static HttpServer start(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static AssistantConfig config(HttpServer server, int timeoutSeconds) {
        return new AssistantConfig(true,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "key",
                "test", timeoutSeconds, 2000, 8, 8000, 600, 3);
    }

    /** 逐帧写 SSE，末帧补 {@code data: [DONE]} —— 与 DeepSeek 实测的响应形状一致。 */
    private static void sse(HttpExchange exchange, List<String> frames) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        // 长度 0 = chunked：必须让客户端在响应头之后就拿到第一块，这个用例才测得到流式行为。
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        for (String frame : frames) {
            out.write(("data: " + frame + "\n\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.close();
    }

    /** 造一帧 {@code chat.completion.chunk}。用 ObjectMapper 拼，避免手写 JSON 的转义错误。 */
    private static String chunk(String deltaContent, Object usage) throws IOException {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("content", deltaContent);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "deepseek-flash");
        body.put("choices", List.of(choice));
        body.put("usage", usage);
        return MAPPER.writeValueAsString(body);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
