package com.crmforlogistics.messagecenter.service.whatsapp.template;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class WhatsAppTemplateException extends RuntimeException {
    private final String code;
    private final HttpStatus statusCode;
    private final Map<String, String> fieldErrors;
    private final String providerRequestId;
    private final boolean retryable;

    public WhatsAppTemplateException(
            String code,
            HttpStatus statusCode,
            String message,
            Map<String, String> fieldErrors,
            String providerRequestId,
            boolean retryable) {
        super(message);
        this.code = code;
        this.statusCode = statusCode;
        this.fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
        this.providerRequestId = providerRequestId;
        this.retryable = retryable;
    }

    public static WhatsAppTemplateException validation(Map<String, String> fieldErrors) {
        return new WhatsAppTemplateException(
                "TEMPLATE_VALIDATION_FAILED",
                HttpStatus.BAD_REQUEST,
                "WhatsApp template validation failed",
                fieldErrors,
                null,
                false);
    }

    public String code() {
        return code;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatusCode() {
        return statusCode;
    }

    public HttpStatus statusCode() {
        return statusCode;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    public String providerRequestId() {
        return providerRequestId;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
