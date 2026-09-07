package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.wecom.WeComAvatarAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Set;

@RestController
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
@RequestMapping("/api")
public class WeComAvatarAuthorizationController {
    private static final String CALLBACK_PATH = "/public/wecom-avatar/oauth/callback";
    private static final Set<String> CALLBACK_PARAMETERS = Set.of("code", "state");
    private static final String SUCCESS_HTML = page("授权完成，可返回原页面");
    private static final String FAILURE_HTML = page("授权未完成，请返回原页面重试");

    private final WeComAvatarAuthorizationService authorizations;

    public WeComAvatarAuthorizationController(WeComAvatarAuthorizationService authorizations) {
        this.authorizations = authorizations;
    }

    @PostMapping("/account/wecom-avatar/authorizations")
    public WeComAvatarAuthorizationService.AuthorizationAttempt create(HttpServletRequest request) {
        return authorizations.create(SecurityUtil.currentUserId(), request.getRemoteAddr());
    }

    @GetMapping("/account/wecom-avatar/authorizations/{authorizationId}")
    public WeComAvatarAuthorizationService.AuthorizationStatus status(
            @PathVariable String authorizationId) {
        return authorizations.status(SecurityUtil.currentUserId(), authorizationId);
    }

    @GetMapping(CALLBACK_PATH)
    public ResponseEntity<String> callback(@RequestParam String code,
                                           @RequestParam String state,
                                           HttpServletRequest request) {
        validateCallbackRequest(code, state, request);
        WeComAvatarAuthorizationService.CallbackResult result = authorizations.complete(code, state);
        String body = result.status() == WeComAvatarAuthorizationService.Status.SUCCEEDED
                ? SUCCESS_HTML : FAILURE_HTML;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(body);
    }

    private static void validateCallbackRequest(String code, String state, HttpServletRequest request) {
        if (!CALLBACK_PARAMETERS.equals(request.getParameterMap().keySet())
                || request.getParameterValues("code").length != 1
                || request.getParameterValues("state").length != 1
                || invalid(code) || invalid(state)) {
            throw new WeComException("WECOM_AVATAR_AUTH_REQUEST_INVALID", 400,
                    "企业微信头像授权回调参数无效");
        }
    }

    private static boolean invalid(String value) {
        return value == null || value.isBlank() || value.length() > 512;
    }

    private static String page(String message) {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>企业微信头像授权</title><style>body{margin:0;font-family:system-ui,sans-serif;"
                + "display:grid;place-items:center;min-height:100vh;color:#1f2329;background:#f5f6f7}"
                + "main{padding:24px;text-align:center}p{margin:0;font-size:16px}</style></head>"
                + "<body><main><p>" + message + "</p></main></body></html>";
    }
}
