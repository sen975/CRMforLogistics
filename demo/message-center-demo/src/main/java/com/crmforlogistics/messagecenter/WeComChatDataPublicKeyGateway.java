package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded adapter for the official chatdata/set_public_key endpoint. */
public final class WeComChatDataPublicKeyGateway {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_ACCESS_TOKEN_CHARS = 4096;
    private static final int MAX_PUBLIC_KEY_BYTES = 16_384;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final HttpClient client;
    private final URI apiBase;
    private final Duration timeout;

    public WeComChatDataPublicKeyGateway(Config config) {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
                URI.create(config.value("WECOM_API_BASE_URL", "https://qyapi.weixin.qq.com")), TIMEOUT);
    }

    private WeComChatDataPublicKeyGateway(HttpClient client, URI apiBase, Duration timeout) {
        this.client = client;
        this.apiBase = apiBase;
        this.timeout = timeout;
    }

    static WeComChatDataPublicKeyGateway forTests(HttpClient client, URI apiBase) {
        return new WeComChatDataPublicKeyGateway(client, apiBase, TIMEOUT);
    }

    static WeComChatDataPublicKeyGateway forTests(HttpClient client, URI apiBase, Duration timeout) {
        return new WeComChatDataPublicKeyGateway(client, apiBase, timeout);
    }

    public void register(String accessToken, WeComChatDataCrypto.PublicKeyMaterial material)
            throws WeComChatDataException {
        try {
            require(accessToken != null && !accessToken.isBlank()
                    && accessToken.length() <= MAX_ACCESS_TOKEN_CHARS);
            require(material != null && material.version() > 0 && material.bitLength() == 2048);
            require(material.pem() != null
                    && material.pem().getBytes(StandardCharsets.US_ASCII).length <= MAX_PUBLIC_KEY_BYTES
                    && material.pem().startsWith("-----BEGIN PUBLIC KEY-----\n")
                    && material.pem().endsWith("-----END PUBLIC KEY-----\n"));

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("public_key", material.pem());
            requestBody.addProperty("public_key_ver", material.version());
            URI uri = apiBase.resolve("/cgi-bin/chatdata/set_public_key?access_token="
                    + URLEncoder.encode(accessToken, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                    .build();
            CompletableFuture<HttpResponse<byte[]>> responseFuture = client.sendAsync(
                    request, ignored -> new BoundedBodySubscriber(MAX_RESPONSE_BYTES));
            final HttpResponse<byte[]> response;
            try {
                response = responseFuture.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                responseFuture.cancel(true);
                throw failed(exception);
            } catch (InterruptedException exception) {
                responseFuture.cancel(true);
                Thread.currentThread().interrupt();
                throw failed(exception);
            }
            byte[] bytes = response.body();
            require(bytes != null && response.statusCode() >= 200 && response.statusCode() < 300);
            JsonObject body = JsonParser.parseString(
                    new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            require(body.has("errcode") && body.get("errcode").isJsonPrimitive());
            int errcode = body.get("errcode").getAsInt();
            if (errcode != 0) throw failed(errcode, null);
        } catch (WeComChatDataException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failed(exception);
        }
    }

    private static void require(boolean condition) throws WeComChatDataException {
        if (!condition) throw failed(null);
    }

    private static WeComChatDataException failed(Throwable cause) {
        return failed(null, cause);
    }

    private static WeComChatDataException failed(Integer upstreamErrcode, Throwable cause) {
        return new WeComChatDataException("WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FAILED", 503,
                "企业微信会话存档公钥注册失败", upstreamErrcode, cause);
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximumBytes;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private int size;

        private BoundedBodySubscriber(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            try {
                for (ByteBuffer item : items) {
                    int length = item.remaining();
                    if ((long) size + length > maximumBytes) {
                        subscription.cancel();
                        body.completeExceptionally(new IOException("upstream response too large"));
                        return;
                    }
                    byte[] chunk = new byte[length];
                    item.get(chunk);
                    bytes.writeBytes(chunk);
                    size += length;
                }
                subscription.request(1);
            } catch (RuntimeException exception) {
                subscription.cancel();
                body.completeExceptionally(exception);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
