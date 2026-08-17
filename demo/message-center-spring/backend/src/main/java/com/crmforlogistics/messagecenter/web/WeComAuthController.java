package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.response.WeComBindingResponse;
import com.crmforlogistics.messagecenter.dto.response.WeComLoginResponse;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.wecom.WeComLoginApplicationService;
import com.crmforlogistics.messagecenter.service.wecom.WeComLoginAttemptService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WeComAuthController {
    private final WeComLoginAttemptService attempts;
    private final WeComLoginApplicationService login;
    private final ObjectMapper objectMapper;

    public WeComAuthController(WeComLoginAttemptService attempts,
                               WeComLoginApplicationService login,
                               ObjectMapper objectMapper) {
        this.attempts = attempts;
        this.login = login;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/auth/wecom/attempts")
    public WeComLoginAttemptService.LoginAttemptResponse createLoginAttempt(HttpServletRequest request) {
        return attempts.createLoginAttempt(request.getRemoteAddr());
    }

    @PostMapping("/auth/wecom/exchange")
    public WeComLoginResponse exchange(@RequestBody String rawBody,
                                       HttpServletRequest request) {
        ExchangeBody body = parse(rawBody);
        return login.exchange(new WeComLoginApplicationService.ExchangeRequest(body.code(), body.state()),
                metadata(request));
    }

    @GetMapping("/account/wecom-binding")
    public WeComBindingResponse bindingStatus() {
        return login.bindingStatus(SecurityUtil.currentUserId());
    }

    @PostMapping("/account/wecom-binding/attempts")
    public WeComLoginAttemptService.LoginAttemptResponse createBindingAttempt(HttpServletRequest request) {
        return attempts.createBindingAttempt(SecurityUtil.currentUserId(), request.getRemoteAddr());
    }

    @PostMapping("/account/wecom-binding/exchange")
    public WeComBindingResponse exchangeBinding(@RequestBody String rawBody,
                                                HttpServletRequest request) {
        ExchangeBody body = parse(rawBody);
        UUID userId = SecurityUtil.currentUserId();
        return login.exchangeBinding(userId,
                new WeComLoginApplicationService.ExchangeRequest(body.code(), body.state()),
                metadata(request));
    }

    @DeleteMapping("/account/wecom-binding")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind() {
        login.unbind(SecurityUtil.currentUserId());
    }

    private static WeComLoginApplicationService.RequestMetadata metadata(HttpServletRequest request) {
        return new WeComLoginApplicationService.RequestMetadata(request.getRemoteAddr(),
                request.getHeader("User-Agent"));
    }

    private ExchangeBody parse(String rawBody) {
        try {
            if (rawBody == null || rawBody.length() > 16_384) {
                throw new IllegalArgumentException("request body is too large");
            }
            JsonNode root = objectMapper.readTree(rawBody);
            if (root == null || !root.isObject() || root.size() != 2
                    || !root.has("code") || !root.has("state")
                    || !root.get("code").isTextual() || !root.get("state").isTextual()) {
                throw new IllegalArgumentException("request must contain exactly code and state");
            }
            return new ExchangeBody(root.get("code").asText(), root.get("state").asText());
        } catch (Exception e) {
            throw new com.crmforlogistics.messagecenter.channel.wecom.WeComException(
                    "WECOM_LOGIN_REQUEST_INVALID", 400, "企业微信登录请求格式无效", e);
        }
    }

    @ExceptionHandler(com.crmforlogistics.messagecenter.channel.wecom.WeComException.class)
    public ResponseEntity<com.crmforlogistics.messagecenter.dto.response.ApiError> handleWeCom(
            com.crmforlogistics.messagecenter.channel.wecom.WeComException exception) {
        return ResponseEntity.status(exception.httpStatus()).body(
                new com.crmforlogistics.messagecenter.dto.response.ApiError(exception.code(),
                        exception.getMessage(), UUID.randomUUID().toString(), Map.of()));
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    public record ExchangeBody(String code, String state) {}
}
