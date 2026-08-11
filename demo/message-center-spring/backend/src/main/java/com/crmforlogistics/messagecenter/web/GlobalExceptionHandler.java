package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.response.ApiError;
import com.crmforlogistics.messagecenter.service.callrecord.CallRecordException;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppWebhookAuthenticationException;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateException;
import jakarta.servlet.http.HttpServletRequest;
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

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

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
        return new ApiError("INTERNAL_ERROR", "Internal server error", traceId(request), Map.of());
    }

    private static String traceId(HttpServletRequest request) {
        Object existing = request.getAttribute(WhatsAppTemplateController.TRACE_ID_ATTRIBUTE);
        return existing instanceof String value && !value.isBlank()
                ? value : UUID.randomUUID().toString();
    }
}
