package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.response.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleGeneral(Exception e) {
        return new ApiError("INTERNAL_ERROR", e.getMessage() != null ? e.getMessage() : "Internal server error",
                UUID.randomUUID().toString(), Map.of());
    }
}
