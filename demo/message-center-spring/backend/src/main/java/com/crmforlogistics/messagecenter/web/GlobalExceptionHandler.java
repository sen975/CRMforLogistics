package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.response.ApiError;
import com.crmforlogistics.messagecenter.service.callrecord.CallRecordException;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppWebhookAuthenticationException;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastException;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateException;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicException;
import com.crmforlogistics.messagecenter.channel.email.EmailException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.ArrayList;
import java.util.HashSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int MAX_DIAGNOSTIC_FRAMES = 8;

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncClientDisconnect(AsyncRequestNotUsableException e) {
        // The response is already committed or disconnected; writing an ApiError would fail again.
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiError handleAuthentication(AuthenticationException e) {
        return new ApiError("UNAUTHORIZED", "INVALID_CREDENTIALS",
                UUID.randomUUID().toString(), Map.of());
    }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiError handleSecurity(SecurityException e) {
        return new ApiError("FORBIDDEN", e.getMessage(), UUID.randomUUID().toString(), Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleBadRequest(IllegalArgumentException e) {
        return new ApiError("BAD_REQUEST", e.getMessage(), UUID.randomUUID().toString(), Map.of());
    }

    @ExceptionHandler(AiTopicException.class)
    public ResponseEntity<ApiError> handleAiTopic(AiTopicException e, HttpServletRequest request) {
        HttpStatus status = switch (e.code()) {
            case "TOPIC_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "TOPIC_FORBIDDEN", "TOPIC_ADMIN_REQUIRED" -> HttpStatus.FORBIDDEN;
            case "TOPIC_VERSION_CONFLICT", "TOPIC_STORE_NOT_READY", "TOPIC_RESTORE_NOT_READY",
                    "TOPIC_OPERATION_CONFLICT", "TOPIC_STORE_REQUEST_CONFLICT" -> HttpStatus.CONFLICT;
            case "AI_PROVIDER_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ApiError(e.code(), e.getMessage(), traceId(request), Map.of()));
    }

    @ExceptionHandler(EmailException.class)
    public ResponseEntity<ApiError> handleEmail(EmailException e, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ApiError(
                e.code(), e.getMessage(), traceId(request), Map.of()));
    }

    @ExceptionHandler(WeComException.class)
    public ResponseEntity<ApiError> handleWeCom(WeComException e, HttpServletRequest request) {
        return ResponseEntity.status(e.httpStatus()).body(new ApiError(
                e.code(), e.getMessage(), traceId(request), Map.of()));
    }

    @ExceptionHandler(ChatAppWebhookAuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiError handleWebhookAuthentication(ChatAppWebhookAuthenticationException e) {
        return new ApiError("CHATAPP_WEBHOOK_UNAUTHORIZED", e.getMessage(),
                UUID.randomUUID().toString(), Map.of());
    }

    @ExceptionHandler(CallRecordException.class)
    public ResponseEntity<ApiError> handleCallRecord(CallRecordException e) {
        return ResponseEntity.status(e.httpStatus())
                .body(new ApiError(e.code(), e.getMessage(), UUID.randomUUID().toString(), Map.of()));
    }

    @ExceptionHandler(WhatsAppTemplateException.class)
    public ResponseEntity<ApiError> handleWhatsAppTemplate(WhatsAppTemplateException e,
                                                           HttpServletRequest request) {
        return ResponseEntity.status(e.statusCode())
                .body(new ApiError(e.code(), e.getMessage(), traceId(request), e.fieldErrors()));
    }

    @ExceptionHandler(ChatAppBroadcastException.class)
    public ResponseEntity<ApiError> handleChatAppBroadcast(
            ChatAppBroadcastException e, HttpServletRequest request) {
        return ResponseEntity.status(e.status())
                .body(new ApiError(e.getMessage(), e.getMessage(), traceId(request), Map.of()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            ServletRequestBindingException.class, MissingServletRequestPartException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleRequestBinding(Exception e, HttpServletRequest request) {
        return new ApiError("BAD_REQUEST", "INVALID_REQUEST", traceId(request), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleRequestValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error -> fieldErrors.putIfAbsent(
                error.getField(), error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()));
        return new ApiError("TEMPLATE_VALIDATION_FAILED", "WhatsApp template validation failed",
                traceId(request), fieldErrors);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ApiError handleUploadTooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        return new ApiError("TEMPLATE_MEDIA_INVALID", "Uploaded file exceeds allowed size",
                traceId(request), Map.of("file", "exceeds the maximum request size"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleMissingResource(NoResourceFoundException e) {
        return new ApiError("NOT_FOUND", "RESOURCE_NOT_FOUND",
                UUID.randomUUID().toString(), Map.of());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleGeneral(Exception e, HttpServletRequest request) {
        String traceId = traceId(request);
        SafeDiagnostic diagnostic = safeDiagnostic(e);
        LOG.error("event=http.unhandled_exception traceId={} method={} path={} "
                        + "errorType={} rootErrorType={} sqlState={} sqlErrorCode={} applicationFrames={}",
                traceId, request.getMethod(), request.getRequestURI(), diagnostic.errorType(),
                diagnostic.rootErrorType(), diagnostic.sqlState(), diagnostic.sqlErrorCode(),
                diagnostic.applicationFrames());
        return new ApiError("INTERNAL_ERROR", "Internal server error", traceId, Map.of());
    }

    static SafeDiagnostic safeDiagnostic(Throwable failure) {
        String errorType = failure == null ? "unknown" : failure.getClass().getName();
        Throwable root = failure;
        Set<Throwable> visited = new HashSet<>();
        while (root != null && root.getCause() != null && visited.add(root)) {
            root = root.getCause();
        }
        String rootErrorType = root == null ? "unknown" : root.getClass().getName();
        String sqlState = "none";
        int sqlErrorCode = 0;
        Throwable current = failure;
        visited.clear();
        while (current != null && visited.add(current)) {
            if (current instanceof SQLException sqlException) {
                String candidate = sqlException.getSQLState();
                sqlState = candidate != null && candidate.matches("[0-9A-Z]{5}")
                        ? candidate : "unknown";
                sqlErrorCode = sqlException.getErrorCode();
                break;
            }
            current = current.getCause();
        }

        List<String> frames = new ArrayList<>();
        current = failure;
        visited.clear();
        while (current != null && visited.add(current) && frames.size() < MAX_DIAGNOSTIC_FRAMES) {
            for (StackTraceElement frame : current.getStackTrace()) {
                if (frame.getClassName().startsWith("com.crmforlogistics.messagecenter.")) {
                    frames.add(frame.getClassName() + "." + frame.getMethodName()
                            + ":" + frame.getLineNumber());
                    if (frames.size() == MAX_DIAGNOSTIC_FRAMES) break;
                }
            }
            current = current.getCause();
        }
        return new SafeDiagnostic(errorType, rootErrorType, sqlState, sqlErrorCode,
                List.copyOf(frames));
    }

    record SafeDiagnostic(String errorType, String rootErrorType, String sqlState, int sqlErrorCode,
                          List<String> applicationFrames) {}

    private static String traceId(HttpServletRequest request) {
        Object existing = request.getAttribute(WhatsAppTemplateController.TRACE_ID_ATTRIBUTE);
        return existing instanceof String value && !value.isBlank()
                ? value : UUID.randomUUID().toString();
    }
}
