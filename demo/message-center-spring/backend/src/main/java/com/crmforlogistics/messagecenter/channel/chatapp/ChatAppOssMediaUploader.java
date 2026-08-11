package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.sdk.service.cams20200606.models.GetChatappUploadAuthorizationResponseBody;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

@Service
public class ChatAppOssMediaUploader {
    private final Function<URL, HttpURLConnection> connectionFactory;

    public ChatAppOssMediaUploader() {
        this(url -> {
            try {
                return (HttpURLConnection) url.openConnection();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    ChatAppOssMediaUploader(Function<URL, HttpURLConnection> connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
    }

    public UploadedObject upload(GetChatappUploadAuthorizationResponseBody.Data authorization,
                                 byte[] bytes, String fileName, String contentType) throws Exception {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(bytes, "bytes");
        String bucket = required(authorization.getBucketName(), "bucketName");
        String mimeType = required(contentType, "contentType");
        String objectKey = objectKey(authorization.getDir(), fileName);
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));
        String resource = "/" + bucket + "/" + objectKey;
        String securityToken = authorization.getSecurityToken();
        String canonicalHeaders = securityToken == null || securityToken.isBlank()
                ? "" : "x-oss-security-token:" + securityToken + "\n";
        String stringToSign = "PUT\n\n" + mimeType + "\n" + date + "\n" + canonicalHeaders + resource;
        String signature = hmacSha1Base64(required(authorization.getAccessKeySecret(), "accessKeySecret"), stringToSign);
        String authorizationHeader = "OSS " + required(authorization.getAccessKeyId(), "accessKeyId") + ":" + signature;

        HttpURLConnection connection = connectionFactory.apply(new URL(
                httpsObjectUrl(authorization.getEndPoint(), bucket, objectKey)));
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("Date", date);
        connection.setRequestProperty("Content-Type", mimeType);
        connection.setRequestProperty("Authorization", authorizationHeader);
        if (securityToken != null && !securityToken.isBlank()) {
            connection.setRequestProperty("x-oss-security-token", securityToken);
        }
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("OSS upload failed: HTTP " + status);
        }
        return new UploadedObject(objectKey, httpsObjectUrl(authorization.getEndPoint(), bucket, objectKey));
    }

    static String objectKey(String dir, String fileName) {
        String prefix = dir == null ? "" : dir.trim().replace('\\', '/');
        while (prefix.startsWith("/")) prefix = prefix.substring(1);
        if (!prefix.isBlank() && !prefix.endsWith("/")) prefix += "/";
        return prefix + UUID.randomUUID().toString().replace("-", "") + safeExtension(fileName);
    }

    static String httpsObjectUrl(String endpoint, String bucketName, String objectKey) {
        String host = required(endpoint, "endpoint").trim()
                .replaceFirst("(?i)^https?://", "").replaceAll("/+$", "");
        if (!host.startsWith(bucketName + ".")) host = bucketName + "." + host;
        return "https://" + host + "/" + objectKey;
    }

    private static String safeExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,12}") ? extension : "";
    }

    private static String hmacSha1Base64(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    public record UploadedObject(String objectKey, String url) {
    }
}
