package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicService;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GenerationProjection;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicProjection;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicTimelineResponse;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicOperationKind;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicOperationProjection;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicInboxRequestProjection;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.baomidou.mybatisplus.core.metadata.IPage;

@RestController
@RequestMapping("/api/v1")
public class AiTopicController {
    private final AiTopicService service;
    public AiTopicController(AiTopicService service) { this.service = service; }

    @GetMapping("/contacts/{contactId}/topics")
    public TopicTimelineResponse topics(@PathVariable UUID contactId) {
        return service.getTopics(SecurityUtil.currentUserId(), contactId);
    }

    @PatchMapping("/topics/{topicId}")
    public ResponseEntity<TopicOperationProjection> update(@PathVariable UUID topicId, @RequestBody UpdateRequest request,
                                                           @RequestHeader("Idempotency-Key") String idempotencyKey) {
        String payload = "{\"topicId\":\"" + topicId + "\",\"title\":" + json(request.title()) + ",\"confirmedSummary\":" + json(request.confirmedSummary()) + ",\"expectedVersion\":" + request.expectedVersion() + "}";
        return ResponseEntity.accepted().body(service.submitOperation(SecurityUtil.currentUserId(), request.contactId(), TopicOperationKind.EDIT, payload, "{}", idempotencyKey));
    }

    @PostMapping("/topics/merge")
    public ResponseEntity<TopicOperationProjection> merge(@RequestBody MergeRequest request,
                                                           @RequestHeader("Idempotency-Key") String idempotencyKey) {
        String ids = request.topicIds().stream().map(id -> "\"" + id + "\"").reduce((a,b) -> a + "," + b).orElse("");
        String versions = request.expectedVersions().entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
                .reduce((a, b) -> a + "," + b).orElse("");
        String payload = "{\"topicIds\":[" + ids + "],\"expectedVersions\":{" + versions + "}}";
        return ResponseEntity.accepted().body(service.submitOperation(SecurityUtil.currentUserId(), request.contactId(), TopicOperationKind.MERGE, payload, "{}", idempotencyKey));
    }

    @PostMapping("/topics/{topicId}/store")
    public ResponseEntity<TopicOperationProjection> store(@PathVariable UUID topicId,
                                                            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ResponseEntity.accepted().body(service.submitStore(SecurityUtil.currentUserId(), topicId, idempotencyKey));
    }

    @PostMapping("/topics/{topicId}/restore")
    public ResponseEntity<TopicOperationProjection> restore(@PathVariable UUID topicId,
                                                              @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ResponseEntity.accepted().body(service.submitRestore(SecurityUtil.currentUserId(), topicId, idempotencyKey));
    }

    @PostMapping("/topic-inbox/{requestId}/approve")
    public ResponseEntity<TopicOperationProjection> approveStore(@PathVariable UUID requestId,
                                                                   @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ResponseEntity.accepted().body(service.approveStore(SecurityUtil.currentUserId(), requestId, idempotencyKey));
    }

    @PostMapping("/topic-inbox/{requestId}/reject")
    public ResponseEntity<TopicOperationProjection> rejectStore(@PathVariable UUID requestId,
                                                                  @RequestBody(required = false) RejectStoreRequest request,
                                                                  @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ResponseEntity.accepted().body(service.rejectStore(SecurityUtil.currentUserId(), requestId,
                request == null ? null : request.reason(), idempotencyKey));
    }

    @GetMapping("/topic-inbox/requests")
    public List<TopicInboxRequestProjection> pendingStoreRequests() {
        return service.listPendingStoreRequests(SecurityUtil.currentUserId());
    }

    @GetMapping("/topic-repository")
    public IPage<TopicProjection> repository(@org.springframework.web.bind.annotation.RequestParam(required = false) String search,
                                             @org.springframework.web.bind.annotation.RequestParam(required = false) String ownerType,
                                             @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int page,
                                             @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return service.listStored(SecurityUtil.currentUserId(), search, ownerType, page, size);
    }

    @PostMapping("/contacts/{contactId}/topics/retry")
    public ResponseEntity<GenerationProjection> retry(@PathVariable UUID contactId) {
        return ResponseEntity.accepted().body(service.retryGeneration(SecurityUtil.currentUserId(), contactId));
    }

    @GetMapping("/contacts/{contactId}/topics/attempts")
    public List<com.crmforlogistics.messagecenter.entity.AiTopicGenerationAttemptEntity> attempts(
            @PathVariable UUID contactId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) {
        return service.listGenerationAttempts(SecurityUtil.currentUserId(), contactId, limit);
    }

    public record UpdateRequest(UUID contactId, String title, String confirmedSummary, long expectedVersion) {}
    public record MergeRequest(UUID contactId, List<UUID> topicIds, Map<UUID, Long> expectedVersions) {}
    public record RejectStoreRequest(String reason) {}

    private static String json(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
