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
import java.util.UUID;

@Service
public class WhatsAppTemplateMediaUploadService {
    private static final long IMAGE_MAX_BYTES = 5L * 1024 * 1024;
    private static final long VIDEO_MAX_BYTES = 16L * 1024 * 1024;
    private static final long DOCUMENT_MAX_BYTES = 64L * 1024 * 1024;
    private static final long PROCESSING_TIMEOUT_SECONDS = 90;

    private final WhatsAppTemplateApplicationService templateApplicationService;
    private final WhatsAppTemplateMediaUploadStore store;
    private final WhatsAppTemplateGateway gateway;
    private final Clock clock;

    public WhatsAppTemplateMediaUploadService(
            WhatsAppTemplateApplicationService templateApplicationService,
            WhatsAppTemplateMediaUploadStore store,
            WhatsAppTemplateGateway gateway,
            Clock clock) {
        this.templateApplicationService = Objects.requireNonNull(templateApplicationService);
        this.store = Objects.requireNonNull(store);
        this.gateway = Objects.requireNonNull(gateway);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UploadResult upload(UUID accountId, HeaderFormat format, InputStream input, long declaredSize,
                               String fileName, String contentType, String clientRequestId,
                               UUID actorUserId, String traceId) {
        templateApplicationService.validateAccount(accountId);
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
            UploadedMedia uploaded = gateway.upload(accountId, format, bytes, fileName, normalizedContentType);
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
        templateApplicationService.validateAccount(accountId);
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
        String requestId = value == null ? "" : value.trim();
        if (requestId.isEmpty() || requestId.length() > 255) {
            throw WhatsAppTemplateException.validation(
                    Map.of("clientRequestId", "must contain 1 to 255 characters"));
        }
        return requestId;
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
