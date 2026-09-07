package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.request.BindPhoneContactRequest;
import com.crmforlogistics.messagecenter.dto.request.RetryCallRecordRequest;
import com.crmforlogistics.messagecenter.dto.request.ReviseNoteRequest;
import com.crmforlogistics.messagecenter.dto.request.ReviseTranscriptRequest;
import com.crmforlogistics.messagecenter.dto.response.CallRecordResponse;
import com.crmforlogistics.messagecenter.dto.response.PhoneContactBindingResponse;
import com.crmforlogistics.messagecenter.dto.response.PhoneRecordResponse;
import com.crmforlogistics.messagecenter.dto.response.TimelineResponse;
import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.entity.CallTranscriptRevisionEntity;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.mapper.CallTranscriptRevisionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.service.callrecord.CallAudioSessionService;
import com.crmforlogistics.messagecenter.service.callrecord.CallRecordService;
import com.crmforlogistics.messagecenter.service.callrecord.ContactTimelineService;
import com.crmforlogistics.messagecenter.service.callrecord.MinioAudioStore;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class CallRecordController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CallRecordService callRecordService;
    private final ContactTimelineService timelineService;
    private final CallAudioSessionService sessionService;
    private final MinioAudioStore audioStore;
    private final CallRecordMapper callRecordMapper;
    private final CallTranscriptRevisionMapper revisionMapper;
    private final ContactIdentityMapper contactIdentityMapper;
    private final ContactService contactService;

    public CallRecordController(CallRecordService callRecordService,
                                 ContactTimelineService timelineService,
                                 CallAudioSessionService sessionService,
                                 MinioAudioStore audioStore,
                                 CallRecordMapper callRecordMapper,
                                 CallTranscriptRevisionMapper revisionMapper,
                                 ContactIdentityMapper contactIdentityMapper,
                                 ContactService contactService) {
        this.callRecordService = callRecordService;
        this.timelineService = timelineService;
        this.sessionService = sessionService;
        this.audioStore = audioStore;
        this.callRecordMapper = callRecordMapper;
        this.revisionMapper = revisionMapper;
        this.contactIdentityMapper = contactIdentityMapper;
        this.contactService = contactService;
    }

    @GetMapping("/api/v1/contacts/{contactId}/timeline")
    public ResponseEntity<TimelineResponse> timeline(
            @PathVariable UUID contactId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(timelineService.timeline(
                SecurityUtil.currentUserId(), contactId, cursor, limit));
    }

    @PostMapping("/api/v1/contacts/{contactId}/call-records")
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable String contactId,
            @RequestParam("phonePointId") String phonePointId,
            @RequestParam("direction") String direction,
            @RequestParam("occurredAt") Instant occurredAt,
            @RequestParam("clientRequestId") String clientRequestId,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam("file") MultipartFile file) throws IOException {
        UUID userId = SecurityUtil.currentUserId();
        String originalFileName = file.getOriginalFilename();
        String contentType = file.getContentType();
        CallRecordService.CreateCallRecordCommand command = new CallRecordService.CreateCallRecordCommand(
                contactId, phonePointId, direction, occurredAt,
                clientRequestId, originalFileName != null ? originalFileName : "recording.mp3",
                contentType != null ? contentType : "audio/mpeg",
                userId.toString(), note != null ? note : "");
        try (InputStream input = file.getInputStream()) {
            CallRecordEntity entity = callRecordService.create(userId, command, input);
            return ResponseEntity.status(202).body(Map.of(
                    "callRecordId", entity.getId().toString(),
                    "state", entity.getTranscriptionState()));
        }
    }

    @GetMapping("/api/v1/call-records/{callRecordId}")
    public ResponseEntity<CallRecordResponse> detail(@PathVariable UUID callRecordId) {
        CallRecordEntity entity = callRecordService.detail(SecurityUtil.currentUserId(), callRecordId);
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping("/api/v1/call-records/{callRecordId}/audio-sessions")
    public ResponseEntity<Void> createAudioSession(
            @PathVariable UUID callRecordId) {
        UUID userId = SecurityUtil.currentUserId();
        callRecordService.detail(userId, callRecordId);
        CallAudioSessionService.AudioSessionCookie cookie = sessionService.create(
                userId.toString(), callRecordId);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie.headerValue())
                .build();
    }

    @GetMapping("/api/v1/call-records/{callRecordId}/audio")
    public void streamAudio(
            @PathVariable UUID callRecordId,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String cookieValue = extractAudioCookie(request);
        CallAudioSessionService.AudioAuthorization authorization =
                sessionService.authorize(cookieValue, callRecordId);
        CallRecordEntity entity = callRecordService.detail(
                UUID.fromString(authorization.actor()), callRecordId);
        MinioAudioStore.AudioAsset asset = new MinioAudioStore.AudioAsset(
                entity.getAudioRelativePath(),
                entity.getAudioOriginalFileName(),
                entity.getAudioSizeBytes(),
                entity.getAudioSha256(),
                entity.getAudioContentType(),
                entity.getAudioDurationSeconds(),
                entity.getAudioObjectKey());
        try (InputStream input = audioStore.open(asset)) {
            response.setContentType(asset.contentType());
            response.setContentLengthLong(asset.sizeBytes());
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            try (OutputStream output = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
        }
    }

    @PostMapping("/api/v1/call-records/{callRecordId}/retry")
    public ResponseEntity<CallRecordResponse> retry(
            @PathVariable UUID callRecordId,
            @RequestBody RetryCallRecordRequest request) {
        UUID userId = SecurityUtil.currentUserId();
        CallRecordEntity entity = callRecordService.retry(
                userId, callRecordId, userId.toString(), request.clientRequestId());
        return ResponseEntity.ok(toResponse(entity));
    }

    @PatchMapping("/api/v1/call-records/{callRecordId}/transcript")
    public ResponseEntity<CallRecordResponse> reviseTranscript(
            @PathVariable UUID callRecordId,
            @RequestBody ReviseTranscriptRequest request) {
        UUID userId = SecurityUtil.currentUserId();
        CallRecordEntity entity = callRecordService.revise(
                userId, callRecordId, request.text(), userId.toString(), request.expectedVersion());
        return ResponseEntity.ok(toResponse(entity));
    }

    @PatchMapping("/api/v1/call-records/{callRecordId}/note")
    public ResponseEntity<CallRecordResponse> reviseNote(
            @PathVariable UUID callRecordId,
            @RequestBody ReviseNoteRequest request) {
        CallRecordEntity entity = callRecordService.reviseNote(
                SecurityUtil.currentUserId(), callRecordId, request.note(), request.expectedVersion());
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping("/api/v1/phone-contacts")
    public ResponseEntity<Map<String, String>> bindPhoneContact(
            @RequestBody BindPhoneContactRequest request) {
        PhoneContactBindingResponse binding = contactService.bindPhone(
                SecurityUtil.currentUserId(), request.contactId(), request.contactName(), request.phoneNumber());
        return ResponseEntity.ok(Map.of(
                "contactId", binding.contactId(),
                "phonePointId", binding.phonePointId(),
                "displayName", binding.displayName()));
    }

    @GetMapping("/api/v1/phone-repository")
    public ResponseEntity<Map<String, Object>> phoneRepository(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "query", required = false) String query) {
        int safeLimit = Math.min(100, Math.max(1, limit <= 0 ? 20 : limit));
        UUID ownerId = SecurityUtil.currentUserId();
        List<CallRecordEntity> all = callRecordMapper.searchPhoneRepositoryByOwner(ownerId, query);
        List<CallRecordEntity> entities = all.size() > safeLimit
                ? all.subList(0, safeLimit) : all;
        int totalCount = entities.size();
        List<PhoneRecordResponse> items = new ArrayList<>();
        for (CallRecordEntity e : entities) {
            String contactDisplayName = "";
            String contactId = "";
            if (e.getContactAnchorPointId() != null) {
                try {
                    var identities = e.getContactId() == null ? List.<com.crmforlogistics.messagecenter.entity.ContactIdentityEntity>of()
                            : contactIdentityMapper.findByContactIdAndOwner(e.getContactId(), ownerId);
                    var identity = identities.stream().filter(item -> "phone".equals(item.getChannelType())).findFirst();
                    if (identity.isPresent()) {
                        contactDisplayName = identity.get().getDisplayName() != null
                                ? identity.get().getDisplayName() : "";
                        contactId = identity.get().getContactId() != null
                                ? identity.get().getContactId().toString() : "";
                    }
                } catch (Exception ignored) {
                }
            }
            items.add(new PhoneRecordResponse(
                    e.getId().toString(),
                    contactId,
                    e.getContactAnchorPointId() != null ? e.getContactAnchorPointId() : "",
                    contactDisplayName,
                    e.getPhonePointId() != null ? e.getPhonePointId() : "",
                    e.getOccurredAt(),
                    e.getDirection(),
                    e.getAudioDurationSeconds() != null ? e.getAudioDurationSeconds() : 0,
                    e.getNote() != null ? e.getNote() : "",
                    e.getTranscriptionState() != null ? e.getTranscriptionState() : "queued",
                    e.getTranscriptionErrorCode() != null ? e.getTranscriptionErrorCode() : "",
                    e.getTranscriptionErrorMessage() != null ? e.getTranscriptionErrorMessage() : "",
                    e.getTranscriptionErrorRetryable() != null && e.getTranscriptionErrorRetryable(),
                    e.getTranscriptionAttempts() != null ? e.getTranscriptionAttempts() : 0,
                    e.getClientRequestId() != null ? e.getClientRequestId() : "",
                    e.getVersion() != null ? e.getVersion() : 1));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("totalCount", totalCount);
        body.put("nextCursor", null);
        return ResponseEntity.ok(body);
    }

    private CallRecordResponse toResponse(CallRecordEntity e) {
        List<CallTranscriptRevisionEntity> revisions = revisionMapper.listByCallRecordId(e.getId());
        List<CallRecordResponse.RevisionInfo> revisionInfos = new ArrayList<>();
        for (CallTranscriptRevisionEntity r : revisions) {
            revisionInfos.add(new CallRecordResponse.RevisionInfo(
                    r.getId().toString(), r.getText(), r.getEditedAt(), r.getEditedBy()));
        }
        CallRecordResponse.TranscriptionInfo transcription = new CallRecordResponse.TranscriptionInfo(
                e.getTranscriptionState(),
                e.getTranscriptionModel(),
                e.getTranscriptionAttempts() != null ? e.getTranscriptionAttempts() : 0,
                e.getTranscriptionNextAttemptAt(),
                "completed".equals(e.getTranscriptionState()) && e.getTranscriptionResultModel() != null
                        ? new CallRecordResponse.ResultInfo(
                                e.getTranscriptionResultModel(),
                                e.getTranscriptionResultDurationSeconds() != null ? e.getTranscriptionResultDurationSeconds() : 0,
                                e.getTranscriptionResultOriginalText(),
                                parseSegments(e.getTranscriptionResultSegments()),
                                e.getTranscriptionResultCompletedAt())
                        : null,
                e.getTranscriptionErrorCode() != null
                        ? new CallRecordResponse.ErrorInfo(
                                e.getTranscriptionErrorCode(),
                                e.getTranscriptionErrorMessage() != null ? e.getTranscriptionErrorMessage() : "",
                                e.getTranscriptionErrorRetryable() != null && e.getTranscriptionErrorRetryable())
                        : null);
        return new CallRecordResponse(
                e.getId().toString(),
                e.getContactAnchorPointId(),
                e.getPhonePointId(),
                e.getDirection(),
                e.getOccurredAt(),
                e.getCreatedAt(),
                e.getCreatedBy(),
                e.getClientRequestId(),
                e.getNote() != null ? e.getNote() : "",
                new CallRecordResponse.AudioInfo(
                        e.getAudioOriginalFileName(),
                        e.getAudioSizeBytes() != null ? e.getAudioSizeBytes() : 0,
                        e.getAudioSha256(),
                        e.getAudioContentType(),
                        e.getAudioDurationSeconds() != null ? e.getAudioDurationSeconds() : 0),
                transcription,
                revisionInfos,
                e.getCurrentRevisionId() != null ? e.getCurrentRevisionId().toString() : null,
                e.getVersion() != null ? e.getVersion() : 1);
    }

    private List<CallRecordResponse.SegmentInfo> parseSegments(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> raw = OBJECT_MAPPER.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            List<CallRecordResponse.SegmentInfo> segments = new ArrayList<>();
            for (Map<String, Object> seg : raw) {
                double start = seg.get("startSeconds") instanceof Number n ? n.doubleValue() : 0;
                double end = seg.get("endSeconds") instanceof Number n ? n.doubleValue() : 0;
                String text = seg.get("text") instanceof String s ? s : "";
                segments.add(new CallRecordResponse.SegmentInfo(start, end, text));
            }
            return segments;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String extractAudioCookie(HttpServletRequest request) {
        String cookies = request.getHeader(HttpHeaders.COOKIE);
        if (cookies == null) return null;
        for (String cookie : cookies.split(";")) {
            String trimmed = cookie.trim();
            if (trimmed.startsWith("mc_call_audio=")) {
                return trimmed.substring("mc_call_audio=".length());
            }
        }
        return null;
    }
}
