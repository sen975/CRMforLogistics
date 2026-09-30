package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadStore.Reservation;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.MediaAssetStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.UploadedMedia;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.UUID;

@Service
public class WhatsAppTemplateMediaUploadService {
    /**
     * 图片素材的大小上限（平台规格）。
     *
     * <p>它是 {@code public} 的，因为 {@code TemplateMediaLinkFetcher} 要用<b>同一个数</b>
     * 决定「读到多大就掐断网络流」：从外链抓图时必须在<b>下载途中</b>停，
     * 而不是先把 200MB 读完再拿给这里判超限。抄第二份数字的后果是两条路的上限悄悄分叉，
     * 而分叉的方向通常是「抓取侧比上传侧宽」—— 白白吃满内存之后再报一句「文件太大」。
     */
    public static final long IMAGE_MAX_BYTES = 5L * 1024 * 1024;
    private static final long VIDEO_MAX_BYTES = 16L * 1024 * 1024;
    private static final long DOCUMENT_MAX_BYTES = 64L * 1024 * 1024;
    private static final long PROCESSING_TIMEOUT_SECONDS = 90;
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9._~:-]{1,255}");

    private final WhatsAppTemplateMediaUploadStore store;
    private final WhatsAppTemplateGateway gateway;
    private final Clock clock;
    private final WhatsAppProviderScopeService providerScopeService;

    WhatsAppTemplateMediaUploadService(WhatsAppTemplateMediaUploadStore store,
            WhatsAppTemplateGateway gateway,
            Clock clock,
            WhatsAppProviderScopeService providerScopeService) {
        this.store = Objects.requireNonNull(store);
        this.gateway = Objects.requireNonNull(gateway);
        this.clock = Objects.requireNonNull(clock);
        this.providerScopeService = providerScopeService;
    }

    /** The space to upload into is resolved by the caller, so the media lands beside its template. */
    public UploadResult uploadForUser(UUID actorUserId, WhatsAppProviderScopeService.ScopeAccount scopeAccount,
                                      HeaderFormat format, InputStream input, long declaredSize,
                                      String fileName, String contentType, String clientRequestId, String traceId) {
        return upload(scopeAccount.account().getId(), format, input, declaredSize, fileName, contentType,
                clientRequestId, actorUserId, traceId);
    }

    public MediaAssetView findForUser(WhatsAppProviderScopeService.ScopeAccount scopeAccount, String clientRequestId) {
        return find(scopeAccount.account().getId(), clientRequestId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UploadResult upload(UUID accountId, HeaderFormat format, InputStream input, long declaredSize,
                               String fileName, String contentType, String clientRequestId,
                               UUID actorUserId, String traceId) {
        WhatsAppProviderScopeService.ScopeAccount scopeAccount = providerScopeService.requireAccount(accountId);
        TemplateCredentialSource source = TemplateCredentialSource.space(scopeAccount.scope().getId());
        String normalizedContentType = normalizeContentType(contentType);
        byte[] bytes = readBoundedAndValidate(format, input, declaredSize, normalizedContentType);
        String digest = sha256(bytes);
        Instant now = clock.instant();
        TemplateMediaAssetEntity candidate = processingAsset(accountId, format, normalizedContentType,
                bytes.length, digest, clientRequestId, actorUserId, traceId, now);
        Reservation reservation = store.reserve(candidate);
        if (!sameFingerprint(reservation.asset(), candidate)) {
            throw new WhatsAppTemplateException("IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT,
                    "clientRequestId is already bound to different media", Map.of(), null, false);
        }
        if (!reservation.created()) {
            return replay(reservation.asset());
        }
        try {
            UploadedMedia uploaded = gateway.upload(source, format, bytes, fileName, normalizedContentType);
            return new UploadResult(view(store.markUploaded(candidate.getId(), uploaded, clock.instant())), true);
        } catch (WhatsAppTemplateException error) {
            TemplateMediaAssetEntity terminal = error.retryable()
                    ? store.markUnknown(candidate.getId(), error, clock.instant())
                    : store.markFailed(candidate.getId(), error, clock.instant());
            if (!error.retryable()) {
                throw error;
            }
            return new UploadResult(view(terminal), true);
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MediaAssetView find(UUID accountId, String clientRequestId) {
        providerScopeService.requireAccount(accountId);
        TemplateMediaAssetEntity asset = store.find(accountId, requireRequestId(clientRequestId));
        Instant now = clock.instant();
        if (MediaAssetStatus.PROCESSING.name().equals(asset.getAssetStatus())
                && !asset.getStartedAt().isAfter(now.minusSeconds(PROCESSING_TIMEOUT_SECONDS))) {
            asset = store.expireIfStale(asset.getId(), now.minusSeconds(PROCESSING_TIMEOUT_SECONDS), now);
        }
        return view(asset);
    }

    private TemplateMediaAssetEntity processingAsset(
            UUID accountId, HeaderFormat format, String contentType, long sizeBytes, String sha256,
            String clientRequestId, UUID actorUserId, String traceId, Instant now) {
        TemplateMediaAssetEntity asset = new TemplateMediaAssetEntity();
        asset.setId(UUID.randomUUID());
        asset.setChannelAccountId(accountId);
        asset.setClientRequestId(requireRequestId(clientRequestId));
        asset.setMediaFormat(format.name());
        asset.setContentType(contentType);
        asset.setSizeBytes(sizeBytes);
        asset.setSha256(sha256);
        asset.setAssetStatus(MediaAssetStatus.PROCESSING.name());
        asset.setCreatedByUserId(actorUserId);
        asset.setTraceId(traceId);
        asset.setStartedAt(now);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        return asset;
    }

    private static boolean sameFingerprint(TemplateMediaAssetEntity left, TemplateMediaAssetEntity right) {
        return Objects.equals(left.getMediaFormat(), right.getMediaFormat())
                && Objects.equals(left.getContentType(), right.getContentType())
                && Objects.equals(left.getSizeBytes(), right.getSizeBytes())
                && MessageDigest.isEqual(left.getSha256().getBytes(StandardCharsets.US_ASCII),
                        right.getSha256().getBytes(StandardCharsets.US_ASCII));
    }

    private UploadResult replay(TemplateMediaAssetEntity asset) {
        if (MediaAssetStatus.FAILED.name().equals(asset.getAssetStatus())) {
            throw storedFailure(asset.getErrorCode(), asset.getErrorMessage());
        }
        return new UploadResult(view(asset), false);
    }

    private static MediaAssetView view(TemplateMediaAssetEntity asset) {
        return new MediaAssetView(asset.getId(), asset.getClientRequestId(),
                HeaderFormat.valueOf(asset.getMediaFormat()), asset.getContentType(), asset.getSizeBytes(),
                asset.getSha256(), asset.getProviderUrl(), MediaAssetStatus.valueOf(asset.getAssetStatus()),
                asset.getErrorCode(), asset.getErrorMessage(), asset.getTraceId());
    }

    private static String requireRequestId(String value) {
        if (value == null || !REQUEST_ID_PATTERN.matcher(value).matches()) {
            throw WhatsAppTemplateException.validation(
                    Map.of("clientRequestId", "must match [A-Za-z0-9._~:-]{1,255}"));
        }
        return value;
    }

    private static WhatsAppTemplateException storedFailure(String code, String message) {
        HttpStatus status = switch (code == null ? "" : code) {
            case "PROVIDER_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "PROVIDER_PERMISSION_DENIED" -> HttpStatus.FORBIDDEN;
            case "PROVIDER_AUTH_FAILED" -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return new WhatsAppTemplateException(code == null ? "TEMPLATE_MEDIA_UPLOAD_FAILED" : code,
                status, message == null ? "Media upload failed" : message, Map.of(), null, false);
    }

    private static String normalizeContentType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        int parameter = normalized.indexOf(';');
        return parameter < 0 ? normalized : normalized.substring(0, parameter).trim();
    }

    private static byte[] readBoundedAndValidate(
            HeaderFormat format, InputStream input, long declaredSize, String contentType) {
        long maxBytes = mediaLimit(format, contentType);
        if (declaredSize <= 0) {
            throw validation("file", "is required");
        }
        if (declaredSize > maxBytes) {
            throw validation("file", "exceeds the maximum size for " + format);
        }
        if (input == null) {
            throw validation("file", "is required");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        long total = 0;
        try {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw validation("file", "exceeds the maximum size for the selected format");
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException error) {
            throw new WhatsAppTemplateException("TEMPLATE_MEDIA_INVALID", HttpStatus.BAD_REQUEST,
                    "Unable to read uploaded media", Map.of("file", "could not be read"), null, false);
        }
        if (total == 0) {
            throw validation("file", "is required");
        }
        return output.toByteArray();
    }

    private static long mediaLimit(HeaderFormat format, String contentType) {
        if (format == HeaderFormat.IMAGE) {
            if (!"image/jpeg".equals(contentType) && !"image/png".equals(contentType)) {
                throw validation("contentType", "is not allowed for " + format);
            }
            return IMAGE_MAX_BYTES;
        }
        if (format == HeaderFormat.VIDEO) {
            if (!"video/mp4".equals(contentType)) {
                throw validation("contentType", "is not allowed for " + format);
            }
            return VIDEO_MAX_BYTES;
        }
        if (format == HeaderFormat.DOCUMENT) {
            if (!"application/pdf".equals(contentType)) {
                throw validation("contentType", "is not allowed for " + format);
            }
            return DOCUMENT_MAX_BYTES;
        }
        throw validation("format", "must be IMAGE, VIDEO or DOCUMENT");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static WhatsAppTemplateException validation(String field, String message) {
        return WhatsAppTemplateException.validation(Map.of(field, message));
    }

    public record MediaAssetView(
            UUID id, String clientRequestId, HeaderFormat format, String contentType,
            long sizeBytes, String sha256, String providerUrl, MediaAssetStatus assetStatus,
            String errorCode, String errorMessage, String traceId) {
    }

    public record UploadResult(MediaAssetView asset, boolean created) {
    }
}
