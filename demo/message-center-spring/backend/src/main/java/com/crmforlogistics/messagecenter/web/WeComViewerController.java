package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.dto.request.WeComViewerEventRequest;
import com.crmforlogistics.messagecenter.dto.request.WeComViewerSessionRequest;
import com.crmforlogistics.messagecenter.dto.response.ApiError;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.wecom.LocalWeComDevelopmentService;
import com.crmforlogistics.messagecenter.service.wecom.WeComChatDataSyncService;
import com.crmforlogistics.messagecenter.service.wecom.WeComUserBindingService;
import com.crmforlogistics.messagecenter.service.wecom.WeComViewerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;

@RestController
@Validated
@ConditionalOnWeComEnabled
@RequestMapping("/api/v1/wecom")
public class WeComViewerController {
    private static final String VIEWER_TOKEN_HEADER = "X-WeCom-Viewer-Token";

    private final AppConfig config;
    private final ObjectProvider<WeComViewerService> viewerProvider;
    private final ObjectProvider<LocalWeComDevelopmentService> localProvider;
    private final ObjectProvider<WeComChatDataSyncService> syncProvider;
    private final ObjectProvider<WeComUserBindingService> bindingProvider;

    public WeComViewerController(AppConfig config,
                                 ObjectProvider<WeComViewerService> viewerProvider,
                                 ObjectProvider<LocalWeComDevelopmentService> localProvider,
                                 ObjectProvider<WeComChatDataSyncService> syncProvider,
                                 ObjectProvider<WeComUserBindingService> bindingProvider) {
        this.config = config;
        this.viewerProvider = viewerProvider;
        this.localProvider = localProvider;
        this.syncProvider = syncProvider;
        this.bindingProvider = bindingProvider;
    }

    @ModelAttribute
    void rejectViewerTokensInQuery(HttpServletRequest request) {
        request.getParameterMap().keySet().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .filter(name -> name.contains("token"))
                .findAny()
                .ifPresent(name -> {
                    throw new WeComException("WECOM_VIEWER_REQUEST_INVALID", 400,
                            "企业微信展示凭证只能通过专用请求头传递");
                });
    }

    @PostMapping("/conversation-view/bootstrap")
    public WeComViewerService.LoginExchangeResponse bootstrap() {
        WeComUserBindingService.BoundIdentity binding = requireBinding();
        if (config.localDevMode()) {
            return requireLocal().issueViewerAuth(binding.wecomUserId(), binding.installationBinding());
        }
        return requireViewer().issueViewerAuth(binding.wecomUserId(), binding.installationBinding());
    }

    @PostMapping("/conversation-view/sync")
    public WeComChatDataSyncService.SyncResult sync(
            @RequestHeader(VIEWER_TOKEN_HEADER) String viewerToken) {
        requireBoundActor(viewerToken);
        if (config.localDevMode()) {
            return requireLocal().sync(viewerToken);
        }
        WeComChatDataSyncService sync = syncProvider.getIfAvailable();
        if (sync == null) throw runtimeUnavailable();
        return sync.sync(requireViewer().viewerSyncContext(viewerToken));
    }

    @GetMapping("/js-sdk-config")
    public Object jsSdkConfig(@RequestParam("url") @Size(max = 2048) String url,
                              @RequestHeader(VIEWER_TOKEN_HEADER) String viewerToken) {
        requireBoundActor(viewerToken);
        return config.localDevMode() ? requireLocal().jsSdkConfig(url, viewerToken)
                : requireViewer().jsSdkConfig(url, viewerToken);
    }

    @PostMapping("/conversation-view/sessions")
    public WeComViewerService.ViewerSessionResponse createSession(
            @RequestHeader(VIEWER_TOKEN_HEADER) String viewerToken,
            @Valid @RequestBody WeComViewerSessionRequest request) {
        requireBoundActor(viewerToken);
        if (request.targetType() == null || request.targetType().isBlank() || request.targetId() == null) {
            if (request.contactPointId() == null || request.contactPointId().isBlank()) {
                throw new WeComException("WECOM_VIEWER_REQUEST_INVALID", 400, "企业微信展示目标不能为空");
            }
            return config.localDevMode()
                    ? requireLocal().createSession(request.contactPointId(), viewerToken, request.messageIds())
                    : requireViewer().createViewerSession(request.contactPointId(), viewerToken, request.messageIds());
        }
        String targetType = request.targetType().trim().toUpperCase(Locale.ROOT);
        if (!targetType.equals("CONTACT") && !targetType.equals("WECOM_GROUP")) {
            throw new WeComException("WECOM_VIEWER_REQUEST_INVALID", 400, "企业微信展示目标类型无效");
        }
        return config.localDevMode()
                ? requireLocal().createSession("wecom:" + request.targetId(), viewerToken, request.messageIds())
                : requireViewer().createViewerSession(SecurityUtil.currentUserId(), targetType,
                request.targetId(), viewerToken, request.messageIds());
    }

    @GetMapping("/conversation-view/sessions/{viewerSessionId}")
    public WeComViewerService.ViewerSessionDetail readSession(
            @PathVariable @Size(max = 64) String viewerSessionId,
            @RequestHeader(VIEWER_TOKEN_HEADER) String viewerToken) {
        requireBoundActor(viewerToken);
        return config.localDevMode() ? requireLocal().readSession(viewerSessionId, viewerToken)
                : requireViewer().viewerSession(viewerSessionId, viewerToken);
    }

    @PostMapping("/conversation-view/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordEvent(@RequestHeader(VIEWER_TOKEN_HEADER) String viewerToken,
                            @Valid @RequestBody WeComViewerEventRequest request) {
        requireBoundActor(viewerToken);
        if (config.localDevMode()) {
            requireLocal().recordClientEvent(request.eventType(), request.viewerSessionId(), viewerToken);
        } else {
            if (request.eventKey() == null) {
                requireViewer().recordClientEvent(request.eventType(), request.viewerSessionId(), viewerToken);
            } else {
                requireViewer().recordClientEvent(request.eventType(), request.eventKey(), request.stage(),
                        request.generation(), request.viewerSessionId(), request.errorCategory(), viewerToken);
            }
        }
    }

    private WeComUserBindingService.BoundIdentity requireBinding() {
        WeComUserBindingService bindings = bindingProvider.getIfAvailable();
        if (bindings == null) {
            throw new WeComException("WECOM_USER_NOT_BOUND", 403, "账号尚未绑定企业微信");
        }
        return bindings.requireByUserId(SecurityUtil.currentUserId());
    }

    private void requireBoundActor(String viewerToken) {
        WeComUserBindingService.BoundIdentity binding = requireBinding();
        String viewerActor = config.localDevMode() ? requireLocal().requireViewerActor(viewerToken)
                : requireViewer().requireViewerActor(viewerToken);
        if (!binding.wecomUserId().equals(viewerActor)) {
            throw new WeComException("WECOM_VIEWER_ACTOR_MISMATCH", 403, "企业微信展示身份不匹配");
        }
    }

    private LocalWeComDevelopmentService requireLocal() {
        LocalWeComDevelopmentService local = localProvider.getIfAvailable();
        if (local == null) throw runtimeUnavailable();
        return local;
    }

    private WeComViewerService requireViewer() {
        WeComViewerService viewer = viewerProvider.getIfAvailable();
        if (viewer == null) throw runtimeUnavailable();
        return viewer;
    }

    private static WeComException runtimeUnavailable() {
        return new WeComException("WECOM_VIEWER_UNAVAILABLE", 503, "企业微信展示服务不可用");
    }

    @ExceptionHandler(WeComException.class)
    public ResponseEntity<ApiError> handleWeCom(WeComException exception) {
        return ResponseEntity.status(exception.httpStatus()).body(
                new ApiError(exception.code(), exception.getMessage(), UUID.randomUUID().toString(), Map.of()));
    }

    @ExceptionHandler(WeComChatDataException.class)
    public ResponseEntity<ApiError> handleChatData(WeComChatDataException exception) {
        return ResponseEntity.status(exception.httpStatus()).body(
                new ApiError(exception.code(), exception.getMessage(), UUID.randomUUID().toString(), Map.of()));
    }

    @ExceptionHandler(WeComViewerService.RateLimitException.class)
    public ResponseEntity<ApiError> handleRateLimit(WeComViewerService.RateLimitException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ApiError(
                "WECOM_VIEWER_RATE_LIMITED", "企业微信展示请求过于频繁",
                UUID.randomUUID().toString(), Map.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalidViewerRequest(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> fieldErrors.putIfAbsent(
                error.getField(), error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError(
                "WECOM_VIEWER_REQUEST_INVALID", "企业微信展示请求格式无效",
                UUID.randomUUID().toString(), fieldErrors));
    }
}
