package com.crmforlogistics.wecomsaas;

import java.util.LinkedHashMap;
import java.util.Map;

public class ApiError extends RuntimeException {
    private final int status;
    private final Map<String, Object> body;

    public ApiError(int status, String error, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.body = new LinkedHashMap<>();
        this.body.put("error", error);
        this.body.put("message", message);
        if (details != null) {
            this.body.putAll(details);
        }
    }

    public int status() {
        return status;
    }

    public Map<String, Object> body() {
        return Map.copyOf(body);
    }
}
