package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastJobEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastRecipientEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.dto.response.TemplateResponse;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastJobMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastRecipientMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppAccountResolver;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppTemplateService;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastStatus;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastDetail;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastPage;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastView;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.CreateBroadcastCommand;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientCandidate;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientInput;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientPage;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientView;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChatAppBroadcastApplicationService {
    private static final Pattern CLIENT_REQUEST_ID =
            Pattern.compile("^[A-Za-z0-9._~:-]{1,255}$");
    private static final int TEMPLATE_PARAM_KEY_MAX_LENGTH = 128;
    private static final int TEMPLATE_PARAM_VALUE_MAX_LENGTH = 1024;

    private final ChatAppBroadcastMapper broadcastMapper;
    private final ChatAppBroadcastRecipientMapper recipientMapper;
    private final ChatAppBroadcastJobMapper jobMapper;
    private final ContactIdentityMapper identityMapper;
    private final TemplateMapper templateMapper;
    private final ChatAppAccountResolver accountResolver;
    private final ChatAppTemplateService chatAppTemplateService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ChatAppBroadcastApplicationService(
            ChatAppBroadcastMapper broadcastMapper,
            ChatAppBroadcastRecipientMapper recipientMapper,
            ChatAppBroadcastJobMapper jobMapper,
            ContactIdentityMapper identityMapper,
            TemplateMapper templateMapper,
            ChatAppAccountResolver accountResolver,
            ChatAppTemplateService chatAppTemplateService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.broadcastMapper = Objects.requireNonNull(broadcastMapper);
        this.recipientMapper = Objects.requireNonNull(recipientMapper);
        this.jobMapper = Objects.requireNonNull(jobMapper);
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.templateMapper = Objects.requireNonNull(templateMapper);
        this.accountResolver = Objects.requireNonNull(accountResolver);
        this.chatAppTemplateService = Objects.requireNonNull(chatAppTemplateService);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public BroadcastView create(CreateBroadcastCommand command, UUID actorUserId) {
        return createInternal(command, actorUserId, null);
    }

    @Transactional(readOnly = true)
    public List<TemplateResponse> sendableTemplates(UUID channelAccountId, UUID actorUserId) {
        requireAccountAccess(channelAccountId, actorUserId, false);
        return chatAppTemplateService.listForAccount(channelAccountId);
    }

    private BroadcastView createInternal(
            CreateBroadcastCommand command, UUID actorUserId, UUID retriesBroadcastId) {
        validateCommand(command, actorUserId);
        String requestFingerprint = fingerprint(command, objectMapper, retriesBroadcastId);
        var existing = broadcastMapper.findByIdempotency(
                command.channelAccountId(), command.clientRequestId());
        if (existing.isPresent()) {
            return idempotentResult(existing.orElseThrow(), requestFingerprint, actorUserId);
        }

        requireAccountAccess(command.channelAccountId(), actorUserId, false);
        TemplateEntity template = templateMapper.findForSend(
                        command.channelAccountId(), command.templateCode(), command.languageCode())
                .orElseThrow(() -> error(
                        "CHATAPP_BROADCAST_TEMPLATE_NOT_SENDABLE", HttpStatus.CONFLICT));
        if (template.getBody() == null || template.getBody().isBlank()) {
            throw error("CHATAPP_BROADCAST_TEMPLATE_NOT_SENDABLE", HttpStatus.CONFLICT);
        }

        List<UUID> identityIds = command.recipients().stream()
                .map(RecipientInput::contactIdentityId)
                .toList();
        if (new LinkedHashSet<>(identityIds).size() != identityIds.size()) {
            throw error("CHATAPP_BROADCAST_RECIPIENT_DUPLICATED", HttpStatus.BAD_REQUEST);
        }
        List<RecipientCandidate> candidates = identityMapper.findEligibleChatAppBroadcastRecipients(
                command.channelAccountId(), identityIds, actorUserId);
        Map<UUID, RecipientCandidate> candidatesById = candidates.stream().collect(Collectors.toMap(
                RecipientCandidate::contactIdentityId, candidate -> candidate));
        if (candidatesById.size() != identityIds.size()
                || !candidatesById.keySet().containsAll(identityIds)) {
            throw error("CHATAPP_BROADCAST_RECIPIENT_INACCESSIBLE", HttpStatus.FORBIDDEN);
        }
        Map<UUID, Map<String, String>> paramsByIdentity = validatedParams(command, template);

        Instant now = clock.instant();
        ChatAppBroadcastEntity broadcast = new ChatAppBroadcastEntity();
        broadcast.setId(UUID.randomUUID());
        broadcast.setChannelAccountId(command.channelAccountId());
        broadcast.setName(command.name().trim());
        broadcast.setTemplateCode(command.templateCode().trim());
        broadcast.setTemplateName(template.getName());
        broadcast.setTemplateBodySnapshot(template.getBody());
        broadcast.setLanguageCode(command.languageCode().trim());
        broadcast.setRecipientCount(identityIds.size());
        broadcast.setSuccessCount(0);
        broadcast.setFailedCount(0);
        broadcast.setProcessingCount(identityIds.size());
        broadcast.setStatus(BroadcastStatus.QUEUED.name());
        broadcast.setClientRequestId(command.clientRequestId());
        broadcast.setRequestFingerprint(requestFingerprint);
        broadcast.setRetriesBroadcastId(retriesBroadcastId);
        broadcast.setCreatedByUserId(actorUserId);
        broadcast.setCreatedAt(now);
        broadcast.setUpdatedAt(now);
        broadcast.setVersion(0L);
        if (broadcastMapper.insertIfAbsent(broadcast) != 1) {
            ChatAppBroadcastEntity concurrent = broadcastMapper.findByIdempotency(
                            command.channelAccountId(), command.clientRequestId())
                    .orElseThrow(() -> error(
                            "CHATAPP_BROADCAST_IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT));
            return idempotentResult(concurrent, requestFingerprint, actorUserId);
        }

        for (RecipientInput input : command.recipients()) {
            RecipientCandidate candidate = candidatesById.get(input.contactIdentityId());
            ChatAppBroadcastRecipientEntity recipient = new ChatAppBroadcastRecipientEntity();
            recipient.setId(UUID.randomUUID());
            recipient.setBroadcastId(broadcast.getId());
            recipient.setContactId(candidate.contactId());
            recipient.setContactIdentityId(candidate.contactIdentityId());
            recipient.setRecipientNameSnapshot(candidate.recipientName());
            recipient.setRecipientNumberSnapshot(candidate.normalizedNumber());
            recipient.setTemplateParamsJsonb(json(paramsByIdentity.get(input.contactIdentityId())));
            recipient.setStatus(ChatAppBroadcastModels.RecipientStatus.QUEUED.name());
            recipient.setCreatedAt(now);
            recipient.setUpdatedAt(now);
            recipient.setVersion(0L);
            recipientMapper.insert(recipient);
        }

        ChatAppBroadcastJobEntity job = new ChatAppBroadcastJobEntity();
        job.setId(UUID.randomUUID());
        job.setBroadcastId(broadcast.getId());
        job.setJobType(ChatAppBroadcastModels.JobType.SUBMIT.name());
        job.setStatus(ChatAppBroadcastModels.JobStatus.PENDING.name());
        job.setAttemptCount(0);
        job.setMaxAttempts(1);
        job.setNextAttemptAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        jobMapper.insert(job);

        return view(broadcast);
    }

    static String fingerprint(CreateBroadcastCommand command, ObjectMapper objectMapper) {
        return fingerprint(command, objectMapper, null);
    }

    private static String fingerprint(
            CreateBroadcastCommand command, ObjectMapper objectMapper, UUID retriesBroadcastId) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("channelAccountId", value(command.channelAccountId()));
            canonical.put("name", trimmed(command.name()));
            canonical.put("templateCode", trimmed(command.templateCode()));
            canonical.put("languageCode", trimmed(command.languageCode()));
            canonical.put("retriesBroadcastId", value(retriesBroadcastId));
            canonical.put("sharedTemplateParams", new TreeMap<>(command.sharedTemplateParams()));
            List<Map<String, Object>> recipients = new ArrayList<>();
            command.recipients().stream()
                    .sorted(Comparator.comparing(input -> input.contactIdentityId().toString()))
                    .forEach(input -> recipients.add(Map.of(
                            "contactIdentityId", input.contactIdentityId().toString(),
                            "templateParams", new TreeMap<>(input.templateParams()))));
            canonical.put("recipients", recipients);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    objectMapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("CHATAPP_BROADCAST_FINGERPRINT_FAILED", e);
        }
    }

    @Transactional(readOnly = true)
    public BroadcastPage list(UUID channelAccountId, int page, int size, UUID actorUserId) {
        requireAccountAccess(channelAccountId, actorUserId, true);
        boolean isAdmin = broadcastMapper.isAdmin(actorUserId);
        int safePage = Math.max(page, 1);
        if (size < 1 || size > 100) {
            throw error("CHATAPP_BROADCAST_PAGE_INVALID", HttpStatus.BAD_REQUEST);
        }
        QueryWrapper<ChatAppBroadcastEntity> countQuery = new QueryWrapper<ChatAppBroadcastEntity>()
                .eq("channel_account_id", channelAccountId)
                .eq(!isAdmin, "created_by_user_id", actorUserId);
        long total = broadcastMapper.selectCount(countQuery);
        List<BroadcastView> records = List.of();
        if (total > 0) {
            long offset = (long) (safePage - 1) * size;
            QueryWrapper<ChatAppBroadcastEntity> pageQuery = new QueryWrapper<ChatAppBroadcastEntity>()
                    .eq("channel_account_id", channelAccountId)
                    .eq(!isAdmin, "created_by_user_id", actorUserId)
                    .orderByDesc("created_at").orderByDesc("id")
                    .last("LIMIT " + size + " OFFSET " + offset);
            records = broadcastMapper.selectList(pageQuery).stream().map(ChatAppBroadcastApplicationService::view)
                    .toList();
        }
        return new BroadcastPage(records, total, safePage, size);
    }

    @Transactional(readOnly = true)
    public BroadcastDetail detail(UUID broadcastId, UUID actorUserId) {
        ChatAppBroadcastEntity broadcast = requireBroadcast(broadcastId);
        requireBroadcastAccess(broadcast, actorUserId);
        accountResolver.requireCurrentAccount(broadcast.getChannelAccountId());
        List<RecipientView> recipients = recipientMapper.findByBroadcastId(broadcastId).stream()
                .map(this::recipientView).toList();
        return new BroadcastDetail(view(broadcast), recipients);
    }

    @Transactional(readOnly = true)
    public RecipientPage failures(UUID broadcastId, int page, int size, UUID actorUserId) {
        ChatAppBroadcastEntity broadcast = requireBroadcast(broadcastId);
        requireBroadcastAccess(broadcast, actorUserId);
        accountResolver.requireCurrentAccount(broadcast.getChannelAccountId());
        int safePage = Math.max(page, 1);
        if (size < 1 || size > 100) {
            throw error("CHATAPP_BROADCAST_PAGE_INVALID", HttpStatus.BAD_REQUEST);
        }
        long total = recipientMapper.countFailures(broadcastId);
        long offset = (long) (safePage - 1) * size;
        List<RecipientView> records = total == 0 ? List.of()
                : recipientMapper.findFailures(broadcastId, size, offset).stream()
                .map(this::recipientView).toList();
        return new RecipientPage(records, total, safePage, size);
    }

    @Transactional
    public BroadcastView retryFailures(
            UUID broadcastId, String name, String clientRequestId, UUID actorUserId) {
        ChatAppBroadcastEntity original = broadcastMapper.findByIdForUpdate(broadcastId)
                .orElseThrow(() -> error("CHATAPP_BROADCAST_NOT_FOUND", HttpStatus.NOT_FOUND));
        requireBroadcastAccess(original, actorUserId);
        accountResolver.requireCurrentAccount(original.getChannelAccountId());
        List<ChatAppBroadcastRecipientEntity> failed =
                recipientMapper.findFailures(broadcastId, 1000, 0);
        if (failed.isEmpty()) {
            throw error("CHATAPP_BROADCAST_NO_FAILED_RECIPIENTS", HttpStatus.CONFLICT);
        }
        List<RecipientInput> inputs = failed.stream()
                .map(recipient -> new RecipientInput(
                        recipient.getContactIdentityId(), parseParams(recipient.getTemplateParamsJsonb())))
                .toList();
        CreateBroadcastCommand command = new CreateBroadcastCommand(
                original.getChannelAccountId(), name, original.getTemplateCode(),
                original.getLanguageCode(), clientRequestId, inputs, Map.of());
        return createInternal(command, actorUserId, original.getId());
    }

    private ChatAppBroadcastEntity requireBroadcast(UUID broadcastId) {
        ChatAppBroadcastEntity found = broadcastId == null ? null : broadcastMapper.selectById(broadcastId);
        if (found == null) {
            throw error("CHATAPP_BROADCAST_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        return found;
    }

    private RecipientView recipientView(ChatAppBroadcastRecipientEntity recipient) {
        return new RecipientView(
                recipient.getId(), recipient.getContactId(), recipient.getContactIdentityId(),
                recipient.getRecipientNameSnapshot(), mask(recipient.getRecipientNumberSnapshot()),
                parseParams(recipient.getTemplateParamsJsonb()), recipient.getProviderMessageId(),
                recipient.getProviderUniqueMessageId(), RecipientStatus.valueOf(recipient.getStatus()),
                recipient.getFailureReason(), recipient.getProviderSentAt(), recipient.getLastReconciledAt());
    }

    private Map<String, String> parseParams(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("CHATAPP_BROADCAST_PARAMS_INVALID", e);
        }
    }

    private static String mask(String number) {
        String value = trimmed(number);
        if (value.length() <= 4) return value;
        return "*".repeat(Math.max(4, value.length() - 4)) + value.substring(value.length() - 4);
    }

    private BroadcastView idempotentResult(
            ChatAppBroadcastEntity existing, String requestFingerprint, UUID actorUserId) {
        requireBroadcastAccess(existing, actorUserId);
        if (!requestFingerprint.equals(existing.getRequestFingerprint())) {
            throw error("CHATAPP_BROADCAST_IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT);
        }
        return view(existing);
    }

    private void requireAccountAccess(
            UUID channelAccountId, UUID actorUserId, boolean allowHistoricalCreator) {
        if (channelAccountId == null || actorUserId == null) {
            throw error("CHATAPP_BROADCAST_REQUEST_INVALID", HttpStatus.BAD_REQUEST);
        }
        accountResolver.requireCurrentAccount(channelAccountId);
        boolean accessible = identityMapper.canAccessChatAppAccount(channelAccountId, actorUserId);
        if (!accessible && allowHistoricalCreator) {
            accessible = broadcastMapper.existsCreatedByAccount(channelAccountId, actorUserId);
        }
        if (!accessible) {
            throw error("CHATAPP_BROADCAST_ACCOUNT_FORBIDDEN", HttpStatus.FORBIDDEN);
        }
    }

    private void requireBroadcastAccess(ChatAppBroadcastEntity broadcast, UUID actorUserId) {
        if (actorUserId == null) {
            throw error("CHATAPP_BROADCAST_REQUEST_INVALID", HttpStatus.BAD_REQUEST);
        }
        if (!actorUserId.equals(broadcast.getCreatedByUserId()) && !broadcastMapper.isAdmin(actorUserId)) {
            throw error("CHATAPP_BROADCAST_FORBIDDEN", HttpStatus.FORBIDDEN);
        }
    }

    private void validateCommand(CreateBroadcastCommand command, UUID actorUserId) {
        if (command == null || command.channelAccountId() == null || actorUserId == null) {
            throw error("CHATAPP_BROADCAST_REQUEST_INVALID", HttpStatus.BAD_REQUEST);
        }
        if (command.recipients().isEmpty() || command.recipients().size() > 1000) {
            throw error("CHATAPP_BROADCAST_RECIPIENT_COUNT_INVALID", HttpStatus.BAD_REQUEST);
        }
        if (trimmed(command.name()).isBlank() || trimmed(command.name()).length() > 120
                || trimmed(command.templateCode()).isBlank()
                || trimmed(command.languageCode()).isBlank()
                || !CLIENT_REQUEST_ID.matcher(trimmed(command.clientRequestId())).matches()
                || command.recipients().stream().anyMatch(
                        input -> input == null || input.contactIdentityId() == null)) {
            throw error("CHATAPP_BROADCAST_REQUEST_INVALID", HttpStatus.BAD_REQUEST);
        }
        validateParameterShape(command.sharedTemplateParams());
        command.recipients().forEach(input -> validateParameterShape(input.templateParams()));
    }

    private Map<UUID, Map<String, String>> validatedParams(
            CreateBroadcastCommand command, TemplateEntity template) {
        LinkedHashSet<String> expected = new LinkedHashSet<>(
                chatAppTemplateService.requiredPlaceholders(template));
        Map<UUID, Map<String, String>> result = new LinkedHashMap<>();
        for (RecipientInput input : command.recipients()) {
            Map<String, String> merged = mergedParams(
                    command.sharedTemplateParams(), input.templateParams());
            if (!expected.equals(new LinkedHashSet<>(merged.keySet()))) {
                throw error("CHATAPP_BROADCAST_VARIABLES_INVALID", HttpStatus.BAD_REQUEST);
            }
            result.put(input.contactIdentityId(), merged);
        }
        return result;
    }

    private static void validateParameterShape(Map<String, String> parameters) {
        if (parameters == null) return;
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String key = entry.getKey();
            String parameterValue = entry.getValue();
            if (key == null || key.isBlank() || key.length() > TEMPLATE_PARAM_KEY_MAX_LENGTH
                    || parameterValue == null || parameterValue.isBlank()
                    || parameterValue.length() > TEMPLATE_PARAM_VALUE_MAX_LENGTH) {
                throw error("CHATAPP_BROADCAST_VARIABLES_INVALID", HttpStatus.BAD_REQUEST);
            }
        }
    }

    private static Map<String, String> mergedParams(
            Map<String, String> shared, Map<String, String> recipient) {
        Map<String, String> merged = new TreeMap<>();
        if (shared != null) merged.putAll(shared);
        if (recipient != null) merged.putAll(recipient);
        return merged;
    }

    private String json(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("CHATAPP_BROADCAST_PARAMS_INVALID", e);
        }
    }

    private static BroadcastView view(ChatAppBroadcastEntity entity) {
        return new BroadcastView(
                entity.getId(), entity.getChannelAccountId(), entity.getName(),
                entity.getTemplateCode(), entity.getTemplateName(), entity.getLanguageCode(),
                number(entity.getRecipientCount()), number(entity.getSuccessCount()),
                number(entity.getFailedCount()), number(entity.getProcessingCount()),
                BroadcastStatus.valueOf(entity.getStatus()), entity.getProviderGroupMessageId(),
                entity.getErrorCode(), entity.getErrorMessage(), entity.getRetriesBroadcastId(),
                entity.getCreatedByUserId(), entity.getSubmittedAt(), entity.getReconciledAt(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static int number(Integer value) {
        return value == null ? 0 : value;
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static ChatAppBroadcastException error(String code, HttpStatus status) {
        return new ChatAppBroadcastException(code, status);
    }
}
