package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.dto.response.TemplateResponse;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastApplicationService;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastDetail;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastPage;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastView;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.CreateBroadcastCommand;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientInput;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientPage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chatapp/broadcasts")
public class ChatAppBroadcastController {
    private final ChatAppBroadcastApplicationService service;

    public ChatAppBroadcastController(ChatAppBroadcastApplicationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BroadcastView create(@RequestBody CreateBroadcastRequest request) {
        return service.create(request.toCommand(), SecurityUtil.currentUserId());
    }

    @GetMapping
    public BroadcastPage list(
            @RequestParam UUID channelAccountId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(channelAccountId, page, size, SecurityUtil.currentUserId());
    }

    @GetMapping("/sendable-templates")
    public List<TemplateResponse> sendableTemplates(@RequestParam UUID channelAccountId) {
        return service.sendableTemplates(channelAccountId, SecurityUtil.currentUserId());
    }

    @GetMapping("/{id}")
    public BroadcastDetail detail(@PathVariable UUID id) {
        return service.detail(id, SecurityUtil.currentUserId());
    }

    @GetMapping("/{id}/failures")
    public RecipientPage failures(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.failures(id, page, size, SecurityUtil.currentUserId());
    }

    @PostMapping("/{id}/reconcile")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BroadcastView requestReconciliation(@PathVariable UUID id) {
        return service.requestReconciliation(id, SecurityUtil.currentUserId());
    }

    @PostMapping("/{id}/retry-failures")
    @ResponseStatus(HttpStatus.CREATED)
    public BroadcastView retryFailures(
            @PathVariable UUID id,
            @RequestBody RetryFailuresRequest request) {
        return service.retryFailures(
                id, request.name(), request.clientRequestId(), SecurityUtil.currentUserId());
    }

    public record CreateBroadcastRequest(
            UUID channelAccountId,
            String name,
            String templateCode,
            String languageCode,
            String clientRequestId,
            List<RecipientRequest> recipients,
            Map<String, String> sharedTemplateParams) {

        CreateBroadcastCommand toCommand() {
            List<RecipientInput> inputs = recipients == null ? List.of() : recipients.stream()
                    .map(recipient -> recipient == null
                            ? new RecipientInput(null, Map.of()) : recipient.toInput())
                    .toList();
            return new CreateBroadcastCommand(
                    channelAccountId, name, templateCode, languageCode, clientRequestId,
                    inputs, sharedTemplateParams);
        }
    }

    public record RecipientRequest(
            UUID contactIdentityId,
            Map<String, String> templateParams) {
        RecipientInput toInput() {
            return new RecipientInput(contactIdentityId, templateParams);
        }
    }

    public record RetryFailuresRequest(String name, String clientRequestId) {
    }
}
