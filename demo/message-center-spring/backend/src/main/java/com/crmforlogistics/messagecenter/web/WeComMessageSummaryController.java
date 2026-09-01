package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.dto.response.WeComMessageSummaryResponse;
import com.crmforlogistics.messagecenter.service.wecom.MyBatisWeComMessageSummaryRepository;
import com.crmforlogistics.messagecenter.service.wecom.WeComMessageSummaryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.Map;

@RestController
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
@RequestMapping("/api/v1/wecom/message-summaries")
public class WeComMessageSummaryController {
    private final AppConfig config;
    private final WeComInstallationService installations;
    private final WeComMessageSummaryRepository repository;

    public WeComMessageSummaryController(AppConfig config, WeComInstallationService installations,
                                         WeComMessageSummaryRepository repository) {
        this.config = config;
        this.installations = installations;
        this.repository = repository;
    }

    @GetMapping("/{msgid}")
    public ResponseEntity<WeComMessageSummaryResponse> find(@PathVariable String msgid) {
        UUID installationId = UUID.fromString(installations.resolveInstallation(
                config.wecomSuiteId(), config.wecomLoginAuthCorpId()).installationId());
        return repository.findByMsgid(installationId, msgid)
                .map(view -> ResponseEntity.ok(toResponse(view)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public Map<String, Object> search(
            @RequestParam(required = false) UUID conversationId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        UUID installationId = UUID.fromString(installations.resolveInstallation(
                config.wecomSuiteId(), config.wecomLoginAuthCorpId()).installationId());
        WeComMessageSummaryRepository.PageResult result = repository.find(
                new WeComMessageSummaryRepository.PageQuery(installationId, conversationId,
                        status, from, to, page, size));
        return Map.of("items", result.items().stream().map(WeComMessageSummaryController::toResponse).toList(),
                "total", result.total(), "page", result.page(), "size", result.size());
    }

    private static WeComMessageSummaryResponse toResponse(WeComMessageSummaryRepository.JobView view) {
        return new WeComMessageSummaryResponse(view.messageExists(), view.id(), view.installationId(),
                view.authCorpId(), view.sourceConversationId(), view.msgid(), view.sendTime(), view.status(),
                view.wecomJobId(), view.summary(), view.rawRequestJson(), view.rawResponseJson(),
                view.validationStage(), view.lastErrorCode(), view.failureState(), view.attemptCount(),
                view.nextAttemptAt(), view.createdAt(), view.updatedAt(), view.submittedAt(), view.completedAt());
    }
}
