package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.dto.response.WeComGroupNameRefreshResponse;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.wecom.WeComGroupNameRefreshService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
@RequestMapping("/api/v1/wecom/groups")
public class WeComGroupNameRefreshController {
    private final WeComGroupNameRefreshService service;

    public WeComGroupNameRefreshController(WeComGroupNameRefreshService service) {
        this.service = service;
    }

    @PostMapping("/{sourceConversationId}/name-refresh")
    public ResponseEntity<WeComGroupNameRefreshResponse> refresh(@PathVariable UUID sourceConversationId) {
        return ResponseEntity.accepted().body(WeComGroupNameRefreshResponse.from(
                service.requestManual(SecurityUtil.currentUserId(), sourceConversationId)));
    }
}
