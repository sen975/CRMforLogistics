package com.crmforlogistics.messagecenter.service.assistant;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.SSLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AssistantModelClient#providerDiagnostic(Throwable)} 的失败分类。
 *
 * <p>这个方法是纯查表，却直接决定日志里那行 {@code diagnostic=} 写什么 —— 2026-09-30 生产上
 * 出现「读超时被记成 {@code CLIENT_ERROR}」就是它漏了一档（详见两个实现里的
 * {@code NATIVE_TIMEOUT_MESSAGE} 注释）。所以单独钉住两件事：
 *
 * <ol>
 *   <li><b>覆盖内核级读超时</b>：{@code SocketDispatcher.read0} 抛的是裸
 *       {@code java.io.IOException: Operation timed out}，不是 {@code HttpTimeoutException}。
 *       这类失败必须判成 {@code TIMEOUT}，否则排障会往「我们的请求有问题」的方向走。</li>
 *   <li><b>与孪生实现同口径</b>：本方法与 {@code OpenAiCompatibleTopicGateway} 那份是刻意
 *       平行维护的（注释里写明「便于两处日志比对」）。这里逐项比对两边的输出，
 *       任何一边将来单独改口径都会立刻红。</li>
 * </ol>
 *
 * <p>分类结果只输出枚举值，永不外带异常消息（消息可能含地址、TLS 细节、供应商原文）。
 */
class AssistantModelClientDiagnosticTest {

    @Test
    void recognisesNativeReadTimeoutAsTimeoutNotClientError() {
        // 内核 ETIMEDOUT：裸 IOException，类型层面区分不出来，靠固定串认。
        assertThat(AssistantModelClient.providerDiagnostic(new IOException("Operation timed out")))
                .isEqualTo("TIMEOUT");
        // 真实形状是外层包装异常套着它（Spring 的 ResourceAccessException 就是这种包法）。
        assertThat(AssistantModelClient.providerDiagnostic(
                new IllegalStateException("wrapped", new IOException("Operation timed out"))))
                .isEqualTo("TIMEOUT");
        // 收窄只针对那一个固定串：别的 IOException 仍落兜底。
        assertThat(AssistantModelClient.providerDiagnostic(new IOException("Connection reset by peer")))
                .isEqualTo("CLIENT_ERROR");
        // 供应商把消息伪装成超时串没用：它来自 HTTP 响应体，不是内核产出的 IOException。
        assertThat(AssistantModelClient.providerDiagnostic(new IllegalStateException("Operation timed out")))
                .isEqualTo("CLIENT_ERROR");
    }

    @Test
    void mapsKnownTransportFailuresByType() {
        assertThat(AssistantModelClient.providerDiagnostic(new UnknownHostException("secret-host")))
                .isEqualTo("DNS_ERROR");
        assertThat(AssistantModelClient.providerDiagnostic(new ConnectException("secret-host")))
                .isEqualTo("CONNECT_ERROR");
        assertThat(AssistantModelClient.providerDiagnostic(new HttpTimeoutException("secret-host")))
                .isEqualTo("TIMEOUT");
        assertThat(AssistantModelClient.providerDiagnostic(new SocketTimeoutException("secret-host")))
                .isEqualTo("TIMEOUT");
        assertThat(AssistantModelClient.providerDiagnostic(new SSLException("secret-certificate")))
                .isEqualTo("TLS_ERROR");
    }

    /**
     * 与孪生实现同口径。{@code OpenAiCompatibleTopicGateway} 那份是刻意平行维护的（注释写明
     * 「便于两处日志比对」），但它是 {@code service.aitopic} 的包内方法，跨包调不到 ——
     * 所以这里不互相调用，而是把<b>同一张期望表</b>在两个测试里各钉一遍：
     * 本表与 {@code OpenAiCompatibleTopicGatewayTest} 里那张逐项相同，任一边单独改口径都会红。
     */
    @Test
    void pinsTheSameTaxonomyTableAsTheTopicGatewayClassifier() {
        Map<Throwable, String> expected = new LinkedHashMap<>();
        expected.put(new UnknownHostException("host"), "DNS_ERROR");
        expected.put(new ConnectException("host"), "CONNECT_ERROR");
        expected.put(new HttpTimeoutException("host"), "TIMEOUT");
        expected.put(new SocketTimeoutException("host"), "TIMEOUT");
        expected.put(new SSLException("cert"), "TLS_ERROR");
        expected.put(new IOException("Operation timed out"), "TIMEOUT");
        expected.put(new IllegalStateException("wrapped", new IOException("Operation timed out")), "TIMEOUT");
        expected.put(new IOException("Connection reset by peer"), "CLIENT_ERROR");
        expected.put(new IllegalStateException("Operation timed out"), "CLIENT_ERROR");
        expected.put(new IllegalStateException("plain"), "CLIENT_ERROR");

        expected.forEach((failure, diagnostic) -> assertThat(AssistantModelClient.providerDiagnostic(failure))
                .as("样本 = %s", failure.getClass().getSimpleName())
                .isEqualTo(diagnostic));
    }
}
