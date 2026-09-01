package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComMessageSummaryJobEntity;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import com.crmforlogistics.messagecenter.mapper.WeComMessageSummaryJobMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class MyBatisWeComMessageSummaryRepository implements WeComMessageSummaryRepository {
    private final WeComMessageSummaryJobMapper mapper;
    private final WeComChatDataMessageMapper messageMapper;

    @Autowired
    public MyBatisWeComMessageSummaryRepository(WeComMessageSummaryJobMapper mapper,
                                               WeComChatDataMessageMapper messageMapper) {
        this.mapper = mapper;
        this.messageMapper = messageMapper;
    }

    public MyBatisWeComMessageSummaryRepository(WeComMessageSummaryJobMapper mapper) {
        this(mapper, null);
    }

    @Override
    @Transactional
    public boolean enqueueIfAbsent(EnqueueCommand command) {
        Objects.requireNonNull(command, "command");
        requireUuid(command.installationId(), "installationId");
        requireText(command.authCorpId(), 128, "authCorpId");
        requireText(command.msgid(), 256, "msgid");
        requireBytes(command.rawRequestJson(), 16_384, "rawRequestJson");
        if (command.sendTime() < 0) throw new IllegalArgumentException("sendTime invalid");
        Objects.requireNonNull(command.now(), "now");
        WeComMessageSummaryJobEntity entity = new WeComMessageSummaryJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setInstallationId(command.installationId());
        entity.setAuthCorpId(command.authCorpId());
        entity.setSourceConversationId(command.sourceConversationId());
        entity.setMsgid(command.msgid());
        entity.setSendTime(command.sendTime());
        entity.setRawRequestJson(command.rawRequestJson());
        entity.setAttemptCount(0);
        entity.setNextAttemptAt(command.now());
        entity.setCreatedAt(command.now());
        entity.setUpdatedAt(command.now());
        return mapper.insertIfAbsent(entity) == 1;
    }

    @Override
    @Transactional
    public Optional<LeasedJob> leaseNext(String owner, Instant now, Duration lease) {
        requireText(owner, 100, "owner");
        Objects.requireNonNull(now, "now");
        if (lease == null || lease.isZero() || lease.isNegative() || lease.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("lease invalid");
        }
        UUID jobId = mapper.leaseCandidate(now);
        if (jobId == null || mapper.updateLease(jobId, owner, now.plus(lease), now) != 1) return Optional.empty();
        WeComMessageSummaryJobEntity entity = mapper.selectById(jobId);
        if (entity == null) throw new IllegalStateException("leased summary job missing");
        return Optional.of(new LeasedJob(entity.getId(), entity.getInstallationId(), entity.getAuthCorpId(),
                entity.getSourceConversationId(), entity.getMsgid(), entity.getSendTime(), entity.getStatus(),
                entity.getWecomJobId(), entity.getAttemptCount(), entity.getNextAttemptAt()));
    }

    @Override
    @Transactional
    public void markSubmitted(UUID jobId, String wecomJobId, Instant nextPollAt) {
        Objects.requireNonNull(jobId, "jobId");
        requireText(wecomJobId, 256, "wecomJobId");
        Objects.requireNonNull(nextPollAt, "nextPollAt");
        if (mapper.updateSubmitted(jobId, wecomJobId, nextPollAt) != 1) {
            throw new IllegalStateException("summary submit transition rejected");
        }
    }

    @Override
    @Transactional
    public void markCompleted(UUID jobId, String summary, String rawResponseJson,
                              String validationStage, Instant now) {
        requireText(summary, 65_536, "summary");
        requireBytes(rawResponseJson, 1_048_576, "rawResponseJson");
        requireText(validationStage, 40, "validationStage");
        Objects.requireNonNull(now, "now");
        if (mapper.updateCompleted(jobId, summary, rawResponseJson, validationStage, now) != 1) {
            throw new IllegalStateException("summary complete transition rejected");
        }
    }

    @Override
    @Transactional
    public void markRetry(UUID jobId, String code, String rawResponseJson,
                          String validationStage, Instant nextAttemptAt) {
        requireText(code, 100, "code");
        requireBytes(rawResponseJson, 1_048_576, "rawResponseJson");
        requireText(validationStage, 40, "validationStage");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (mapper.updateRetry(jobId, code, rawResponseJson, validationStage, nextAttemptAt, 100) != 1) {
            throw new IllegalStateException("summary retry transition rejected");
        }
    }

    @Override
    @Transactional
    public void markFailed(UUID jobId, String code, String state, String rawResponseJson,
                           String validationStage, Instant now) {
        requireText(code, 100, "code");
        requireText(state, 32, "state");
        requireBytes(rawResponseJson, 1_048_576, "rawResponseJson");
        requireText(validationStage, 40, "validationStage");
        Objects.requireNonNull(now, "now");
        if (mapper.updateFailed(jobId, code, state, rawResponseJson, validationStage, now) != 1) {
            throw new IllegalStateException("summary failure transition rejected");
        }
    }

    @Override
    public boolean exists(UUID installationId, String msgid) {
        requireUuid(installationId, "installationId");
        requireText(msgid, 256, "msgid");
        return mapper.exists(installationId, msgid);
    }

    @Override
    public Set<String> countNonTerminalByMsgids(List<String> msgids) {
        if (msgids == null || msgids.isEmpty()) return Set.of();
        if (msgids.size() > 8_000) throw new IllegalArgumentException("msgids limit exceeded");
        msgids.forEach(msgid -> requireText(msgid, 256, "msgid"));
        return Set.copyOf(mapper.findNonTerminalMsgids(msgids));
    }

    @Override
    public Optional<JobView> findByMsgid(UUID installationId, String msgid) {
        requireUuid(installationId, "installationId");
        requireText(msgid, 256, "msgid");
        WeComMessageSummaryJobEntity entity = mapper.selectByInstallationMsgid(installationId, msgid);
        boolean messageExists = messageMapper != null
                && messageMapper.findSummaryReference(installationId, msgid) != null;
        if (entity == null) return messageExists ? Optional.of(new JobView(true, null, installationId,
                null, null, msgid, 0L, "NOT_ENQUEUED", null, null, null, null, null, null, null,
                0, null, null, null, null, null)) : Optional.empty();
        return Optional.of(new JobView(messageExists, entity.getId(), entity.getInstallationId(), entity.getAuthCorpId(),
                entity.getSourceConversationId(), entity.getMsgid(), entity.getSendTime(), entity.getStatus(),
                entity.getWecomJobId(), entity.getSummary(), entity.getRawRequestJson(), entity.getRawResponseJson(),
                entity.getValidationStage(), entity.getLastErrorCode(), entity.getFailureState(),
                entity.getAttemptCount(), entity.getNextAttemptAt(), entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getSubmittedAt(), entity.getCompletedAt()));
    }

    @Override
    public PageResult find(PageQuery query) {
        Objects.requireNonNull(query, "query");
        requireUuid(query.installationId(), "installationId");
        if (query.page() < 0 || query.size() < 1 || query.size() > 100) {
            throw new IllegalArgumentException("page invalid");
        }
        if (query.status() != null && !Set.of("PENDING", "SUBMITTED", "RETRY_WAIT", "COMPLETED", "FAILED")
                .contains(query.status())) throw new IllegalArgumentException("status invalid");
        List<WeComMessageSummaryJobEntity> rows = mapper.search(query.installationId(),
                query.sourceConversationId(), query.status(), query.fromSendTime(), query.toSendTime(),
                Math.multiplyExact(query.page(), query.size()), query.size());
        List<JobView> items = rows.stream().map(this::toView).toList();
        return new PageResult(items, mapper.count(query.installationId(), query.sourceConversationId(),
                query.status(), query.fromSendTime(), query.toSendTime()), query.page(), query.size());
    }

    private JobView toView(WeComMessageSummaryJobEntity entity) {
        boolean messageExists = messageMapper != null
                && messageMapper.findSummaryReference(entity.getInstallationId(), entity.getMsgid()) != null;
        return new JobView(messageExists, entity.getId(), entity.getInstallationId(), entity.getAuthCorpId(),
                entity.getSourceConversationId(), entity.getMsgid(), entity.getSendTime(), entity.getStatus(),
                entity.getWecomJobId(), entity.getSummary(), entity.getRawRequestJson(), entity.getRawResponseJson(),
                entity.getValidationStage(), entity.getLastErrorCode(), entity.getFailureState(),
                entity.getAttemptCount(), entity.getNextAttemptAt(), entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getSubmittedAt(), entity.getCompletedAt());
    }

    private static void requireUuid(UUID value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " required");
    }

    private static void requireText(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " invalid");
        }
    }

    private static void requireBytes(String value, int max, String name) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > max) {
            throw new IllegalArgumentException(name + " invalid");
        }
        if ("rawRequestJson".equals(name)) {
            String normalized = value.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("secret_key") || normalized.contains("access_token")
                    || normalized.contains("private_key") || normalized.contains("content")) {
                throw new IllegalArgumentException(name + " contains sensitive field");
            }
        }
    }
}
