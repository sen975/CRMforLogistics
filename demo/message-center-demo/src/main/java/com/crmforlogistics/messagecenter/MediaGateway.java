package com.crmforlogistics.messagecenter;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.DefaultCredentialProvider;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.GeneratePresignedUrlRequest;
import com.aliyun.sdk.service.cams20200606.models.GeneratePresignedUrlResponse;
import com.aliyun.sdk.service.cams20200606.models.GeneratePresignedUrlResponseBody;
import darabonba.core.client.ClientOverrideConfiguration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class MediaGateway {
    private final Config config;
    private final Downloader downloader;
    private final PresignedUrlProvider presignedUrlProvider;

    MediaGateway(Config config) {
        this(config, new HttpDownloader(config), new CamsPresignedUrlProvider(config));
    }

    MediaGateway(Config config, Downloader downloader) {
        this(config, downloader, filePath -> "");
    }

    MediaGateway(Config config, Downloader downloader, PresignedUrlProvider presignedUrlProvider) {
        this.config = config;
        this.downloader = downloader;
        this.presignedUrlProvider = presignedUrlProvider;
    }

    MediaResponse fetch(UnifiedMessage message) throws Exception {
        if (message == null) {
            throw new MediaUnavailableException("消息不存在");
        }
        MediaResponse cached = readCache(message);
        if (cached != null) {
            return cached;
        }
        UrlCandidates candidates = candidateUrls(message);
        if (candidates.urls.isEmpty()) {
            throw new MediaUnavailableException("这条消息没有可下载的附件地址");
        }
        List<String> failures = new ArrayList<>();
        if (candidates.presignedError != null) {
            failures.add(candidates.presignedError.getMessage());
        }
        for (String url : candidates.urls) {
            try {
                MediaResponse downloaded = downloader.download(url);
                MediaResponse response = new MediaResponse(downloaded.bytes,
                        ContactPointUtil.firstNonBlank(downloaded.contentType, contentTypeFor(message)),
                        ContactPointUtil.firstNonBlank(message.fileName, downloaded.fileName));
                if (response != null) {
                    writeCache(message, response);
                    return response;
                }
            } catch (MediaUnavailableException ex) {
                failures.add(downloadLabel(url) + "：" + ex.getMessage());
            } catch (Exception ex) {
                failures.add(downloadLabel(url) + "：附件暂不可用：" + ex.getMessage());
            }
        }
        throw failures.isEmpty()
                ? new MediaUnavailableException("附件暂不可用")
                : new MediaUnavailableException(String.join("；", failures));
    }

    private UrlCandidates candidateUrls(UnifiedMessage message) throws Exception {
        List<String> urls = new ArrayList<>();
        MediaUnavailableException presignedError = null;
        String filePath = ContactPointUtil.firstNonBlank(message.objectKey, objectKeyFromUrl(message.mediaUrl));
        if (!filePath.isBlank() && Boolean.parseBoolean(config.value("CHATAPP_MEDIA_USE_CAMS_PRESIGNED", "true"))) {
            try {
                String generated = generatePresignedUrl(filePath);
                if (!generated.isBlank()) {
                    addCandidate(urls, generated);
                }
            } catch (Exception ex) {
                presignedError = new MediaUnavailableException("CAMS GeneratePresignedUrl 失败：" + ex.getMessage());
            }
        }
        String signed = signedOssUrl(config, message, Instant.now().plusSeconds(signedUrlTtlSeconds()).getEpochSecond());
        addCandidate(urls, signed);
        addCandidate(urls, message.mediaUrl);
        return new UrlCandidates(urls, presignedError);
    }

    private String generatePresignedUrl(String filePath) throws Exception {
        long timeoutSeconds = Math.max(1, Long.parseLong(config.value("CHATAPP_MEDIA_PRESIGNED_TIMEOUT_SECONDS",
                config.value("MEDIA_PROXY_TIMEOUT_SECONDS", "15"))));
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chatapp-media-presign");
            thread.setDaemon(true);
            return thread;
        });
        Future<String> future = executor.submit(() -> presignedUrlProvider.generate(filePath));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new MediaUnavailableException("CAMS 生成下载链接超时");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MediaUnavailableException("CAMS 生成下载链接被中断");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new MediaUnavailableException("CAMS 生成下载链接失败：" + cause.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void addCandidate(List<String> urls, String url) {
        String normalized = normalizeCandidateUrl(url);
        if (!normalized.isBlank() && urls.stream().noneMatch(normalized::equals)) {
            urls.add(normalized);
        }
    }

    private static String normalizeCandidateUrl(String url) {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        if (!trimmed.matches("(?i)^[a-z][a-z0-9+.-]*://.*")
                && trimmed.matches("^[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(/.*)?$")) {
            return "https://" + trimmed;
        }
        return trimmed;
    }

    private long signedUrlTtlSeconds() {
        return Math.max(60, Long.parseLong(config.value("CHATAPP_MEDIA_SIGNED_URL_TTL_SECONDS", "900")));
    }

    private MediaResponse readCache(UnifiedMessage message) throws IOException {
        Path path = cacheFile(message);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(path);
        String contentType = contentTypeFor(message);
        Path typePath = typeFile(path);
        if (Files.isRegularFile(typePath)) {
            contentType = ContactPointUtil.firstNonBlank(Files.readString(typePath, StandardCharsets.UTF_8).trim(), contentType);
        }
        return new MediaResponse(bytes, contentType, ContactPointUtil.firstNonBlank(message.fileName, path.getFileName().toString()));
    }

    private void writeCache(UnifiedMessage message, MediaResponse response) throws IOException {
        Path path = cacheFile(message);
        Files.createDirectories(path.getParent());
        Files.write(path, response.bytes);
        Files.writeString(typeFile(path), response.contentType, StandardCharsets.UTF_8);
    }

    private Path cacheFile(UnifiedMessage message) {
        return config.mediaCacheDir().resolve(cacheKey(message) + extensionFor(message));
    }

    private static Path typeFile(Path file) {
        return file.resolveSibling(file.getFileName().toString() + ".type");
    }

    private static String cacheKey(UnifiedMessage message) {
        String seed = ContactPointUtil.firstNonBlank(message.id, message.sourceId, "message")
                + "|" + ContactPointUtil.firstNonBlank(message.objectKey, "")
                + "|" + ContactPointUtil.firstNonBlank(message.mediaUrl, "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed).substring(0, 32);
        } catch (Exception ex) {
            return Integer.toHexString(seed.hashCode());
        }
    }

    private static String extensionFor(UnifiedMessage message) {
        String name = ContactPointUtil.firstNonBlank(message.fileName, message.objectKey, objectKeyFromUrl(message.mediaUrl));
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String extension = name.substring(dot).toLowerCase(Locale.ROOT);
            if (extension.matches("\\.[a-z0-9]{1,12}")) {
                return extension;
            }
        }
        String mimeType = message.mimeType == null ? "" : message.mimeType.toLowerCase(Locale.ROOT);
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "video/mp4" -> ".mp4";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }

    interface Downloader {
        MediaResponse download(String url) throws Exception;
    }

    interface PresignedUrlProvider {
        String generate(String filePath) throws Exception;
    }

    private static class CamsPresignedUrlProvider implements PresignedUrlProvider {
        private final Config config;

        private CamsPresignedUrlProvider(Config config) {
            this.config = config;
        }

        @Override
        public String generate(String filePath) throws Exception {
            if (filePath == null || filePath.isBlank()) {
                return "";
            }
            try (AsyncClient client = createClient(config)) {
                long timeoutSeconds = Math.max(1, Long.parseLong(config.value("CHATAPP_MEDIA_PRESIGNED_TIMEOUT_SECONDS",
                        config.value("MEDIA_PROXY_TIMEOUT_SECONDS", "15"))));
                GeneratePresignedUrlResponse response = client.generatePresignedUrl(GeneratePresignedUrlRequest.builder()
                        .filePath(filePath)
                        .build()).get(timeoutSeconds, TimeUnit.SECONDS);
                GeneratePresignedUrlResponseBody body = response.getBody();
                if (body == null) {
                    throw new MediaUnavailableException("CAMS 返回空响应");
                }
                assertOk("GeneratePresignedUrl", body.getCode(), body.getMessage());
                if (Boolean.FALSE.equals(body.getSuccess())) {
                    throw new MediaUnavailableException(ContactPointUtil.firstNonBlank(body.getMessage(), "CAMS 返回失败"));
                }
                if (body.getData() == null || body.getData().getUrl() == null || body.getData().getUrl().isBlank()) {
                    throw new MediaUnavailableException("CAMS 未返回下载链接");
                }
                return body.getData().getUrl();
            } catch (TimeoutException ex) {
                throw new MediaUnavailableException("CAMS 生成下载链接超时");
            }
        }
    }

    private static class HttpDownloader implements Downloader {
        private final Config config;
        private final HttpClient client;

        private HttpDownloader(Config config) {
            this.config = config;
            this.client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(1, Long.parseLong(config.value("MEDIA_PROXY_TIMEOUT_SECONDS", "15")))))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }

        @Override
        public MediaResponse download(String url) throws Exception {
            HttpRequest request;
            try {
                request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(Math.max(1, Long.parseLong(config.value("MEDIA_PROXY_TIMEOUT_SECONDS", "15")))))
                        .GET()
                        .build();
            } catch (IllegalArgumentException ex) {
                throw new MediaUnavailableException("附件地址格式无效");
            }
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    byte[] bytes = readBounded(body, config.mediaMaxBytes());
                    String contentType = response.headers().firstValue("content-type").orElse("");
                    return new MediaResponse(bytes, contentType, fileNameFromUrl(url));
                }
                String upstream = readLimitedText(body, 4096);
                throw new MediaUnavailableException(unavailableMessage(response.statusCode(), upstream));
            }
        }
    }

    private static String fileNameFromUrl(String url) {
        String path = objectKeyFromUrl(url);
        if (path.isBlank()) {
            return "attachment";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String downloadLabel(String url) {
        if (url == null || url.isBlank()) {
            return "附件地址";
        }
        try {
            URI uri = URI.create(url);
            String host = ContactPointUtil.firstNonBlank(uri.getHost(), "附件地址");
            String path = objectKeyFromUrl(url);
            return path.isBlank() ? host : host + "/" + path;
        } catch (IllegalArgumentException ex) {
            return "附件地址";
        }
    }

    static String unavailableMessage(int statusCode, String upstream) {
        String lower = upstream == null ? "" : upstream.toLowerCase(Locale.ROOT);
        String errorCode = ossErrorCode(upstream);
        String suffix = errorCode.isBlank() ? "" : "，错误码 " + errorCode;
        if (lower.contains("invalidaccesskeyid") || lower.contains("security-token") || lower.contains("expired")) {
            return "附件链接不可用，OSS 临时凭据已失效或签名无效" + suffix + "，请重新同步或确认服务端 AK 有该 bucket 的读取权限";
        }
        return "附件暂不可用，OSS 返回 HTTP " + statusCode + suffix;
    }

    private static String ossErrorCode(String upstream) {
        if (upstream == null || upstream.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?is)<Code>\\s*([^<\\s]+)\\s*</Code>")
                .matcher(upstream);
        if (!matcher.find()) {
            return "";
        }
        String code = matcher.group(1).trim();
        return code.matches("[A-Za-z0-9._-]{1,80}") ? code : "";
    }

    static String signedOssUrl(Config config, UnifiedMessage message, long expiresEpoch) throws Exception {
        String accessKeyId = config.value("ALIYUN_ACCESS_KEY_ID", "");
        String accessKeySecret = config.value("ALIYUN_ACCESS_KEY_SECRET", "");
        if (accessKeyId.isBlank() || accessKeySecret.isBlank() || message == null) {
            return "";
        }
        Optional<OssObject> located = locateOssObject(config, message);
        if (located.isEmpty()) {
            return "";
        }
        OssObject object = located.get();
        String resource = "/" + object.bucket + "/" + object.objectKey;
        String stringToSign = "GET\n\n\n" + expiresEpoch + "\n" + resource;
        String signature = hmacSha1Base64(accessKeySecret, stringToSign);
        return "https://" + object.host + "/" + encodePath(object.objectKey)
                + "?OSSAccessKeyId=" + encodeQuery(accessKeyId)
                + "&Expires=" + expiresEpoch
                + "&Signature=" + encodeQuery(signature);
    }

    private static Optional<OssObject> locateOssObject(Config config, UnifiedMessage message) {
        String objectKey = ContactPointUtil.firstNonBlank(message.objectKey, objectKeyFromUrl(message.mediaUrl));
        if (objectKey.isBlank()) {
            return Optional.empty();
        }
        String bucket = config.value("CHATAPP_MEDIA_BUCKET", "");
        String endpoint = normalizeEndpoint(config.value("CHATAPP_MEDIA_ENDPOINT", ""));
        String host = "";
        if (message.mediaUrl != null && !message.mediaUrl.isBlank()) {
            try {
                host = URI.create(message.mediaUrl).getHost();
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (bucket.isBlank() && host != null && !host.isBlank()) {
            int dot = host.indexOf('.');
            if (dot > 0) {
                bucket = host.substring(0, dot);
                if (endpoint.isBlank()) {
                    endpoint = host.substring(dot + 1);
                }
            }
        }
        if (endpoint.isBlank() && host != null && !host.isBlank() && bucket != null && !bucket.isBlank()) {
            String prefix = bucket + ".";
            endpoint = host.startsWith(prefix) ? host.substring(prefix.length()) : host;
        }
        if (bucket == null || bucket.isBlank() || endpoint == null || endpoint.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new OssObject(bucket, bucket + "." + endpoint, objectKey));
    }

    private static String normalizeEndpoint(String endpoint) {
        return endpoint == null ? "" : endpoint.trim()
                .replaceFirst("(?i)^https?://", "")
                .replaceAll("/+$", "");
    }

    private static String objectKeyFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String rawPath = URI.create(url).getRawPath();
            if (rawPath == null || rawPath.isBlank()) {
                return "";
            }
            String path = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
            return URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static String contentTypeFor(UnifiedMessage message) {
        if (message != null && message.mimeType != null && !message.mimeType.isBlank()) {
            return message.mimeType;
        }
        String type = message == null || message.mediaType == null ? "" : message.mediaType.toLowerCase(Locale.ROOT);
        if ("image".equals(type)) {
            return "image/*";
        }
        if ("video".equals(type)) {
            return "video/*";
        }
        return "application/octet-stream";
    }

    private static byte[] readBounded(InputStream input, long maxBytes) throws IOException {
        long limit = Math.max(1, maxBytes);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) {
                throw new MediaUnavailableException("附件超过 MEDIA_MAX_BYTES 限制");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String readLimitedText(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int remaining = Math.max(1, maxBytes);
        int read;
        while (remaining > 0 && (read = input.read(buffer, 0, Math.min(buffer.length, remaining))) >= 0) {
            output.write(buffer, 0, read);
            remaining -= read;
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String encodePath(String value) {
        String[] parts = value.split("/", -1);
        List<String> encoded = new ArrayList<>();
        for (String part : parts) {
            encoded.add(encodeQuery(part).replace("+", "%20"));
        }
        return String.join("/", encoded);
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String hmacSha1Base64(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static AsyncClient createClient(Config config) {
        return AsyncClient.builder()
                .region(config.value("CAMS_REGION", "ap-southeast-1"))
                .credentialsProvider(createCredentialsProvider(config))
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(config.value("CAMS_ENDPOINT", "cams.ap-southeast-1.aliyuncs.com")))
                .build();
    }

    private static ICredentialProvider createCredentialsProvider(Config config) {
        String accessKeyId = config.value("ALIYUN_ACCESS_KEY_ID", "");
        String accessKeySecret = config.value("ALIYUN_ACCESS_KEY_SECRET", "");
        if (!accessKeyId.isBlank() && !accessKeySecret.isBlank()) {
            return StaticCredentialProvider.create(Credential.builder()
                    .accessKeyId(accessKeyId)
                    .accessKeySecret(accessKeySecret)
                    .build());
        }
        return DefaultCredentialProvider.builder().build();
    }

    private static void assertOk(String apiName, String code, String message) throws MediaUnavailableException {
        if (code != null && !code.isBlank() && !"OK".equalsIgnoreCase(code)) {
            throw new MediaUnavailableException(apiName + " failed: " + code + " " + message);
        }
    }

    static class MediaResponse {
        final byte[] bytes;
        final String contentType;
        final String fileName;

        MediaResponse(byte[] bytes, String contentType, String fileName) {
            this.bytes = bytes;
            this.contentType = ContactPointUtil.firstNonBlank(contentType, "application/octet-stream");
            this.fileName = ContactPointUtil.firstNonBlank(fileName, "attachment");
        }
    }

    static class MediaUnavailableException extends IOException {
        MediaUnavailableException(String message) {
            super(message);
        }
    }

    private record OssObject(String bucket, String host, String objectKey) {
    }

    private record UrlCandidates(List<String> urls, MediaUnavailableException presignedError) {
    }
}
