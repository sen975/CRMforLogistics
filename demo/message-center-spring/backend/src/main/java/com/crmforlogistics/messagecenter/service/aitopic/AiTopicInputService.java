package com.crmforlogistics.messagecenter.service.aitopic;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.InputBatch;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.SourceItem;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.SourceType;

@Service
public class AiTopicInputService {
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ChannelAccountMapper channelAccountMapper;
    private final ContactIdentityMapper contactIdentityMapper;
    private final CallRecordMapper callRecordMapper;
    private final AiTopicConfig config;

    public AiTopicInputService(ConversationMapper conversationMapper, MessageMapper messageMapper,
                               ChannelAccountMapper channelAccountMapper, ContactIdentityMapper contactIdentityMapper,
                               CallRecordMapper callRecordMapper, AiTopicConfig config) {
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.channelAccountMapper = Objects.requireNonNull(channelAccountMapper);
        this.contactIdentityMapper = Objects.requireNonNull(contactIdentityMapper);
        this.callRecordMapper = Objects.requireNonNull(callRecordMapper);
        this.config = Objects.requireNonNull(config);
    }

    public InputBatch collect(UUID contactId, UUID userId, Optional<Instant> after) {
        List<SourceItem> items = new ArrayList<>();
        List<ConversationEntity> conversations = conversationMapper.listAccessibleForContact(contactId, userId);
        List<UUID> conversationIds = conversations.stream().map(ConversationEntity::getId).toList();
        if (!conversationIds.isEmpty()) {
            IPage<MessageEntity> page = messageMapper.listUnassignedMessagesByConversations(
                    new Page<>(1, config.maxInputRecords(), false), conversationIds, userId, null, null, true);
            for (MessageEntity message : page.getRecords()) {
                ChannelAccountEntity account = channelAccountMapper.selectById(message.getChannelAccountId());
                if (account == null || !isSupportedChannel(account.getChannelType())) continue;
                if (after.isPresent() && !message.getOccurredAt().isAfter(after.get())) continue;
                items.add(new SourceItem(message.getId(), SourceType.MESSAGE, account.getChannelType(),
                        message.getOccurredAt(), message.getDirection(), nullToEmpty(message.getSubject()),
                        nullToEmpty(message.getBodyText())));
            }
        }
        List<String> anchors = contactIdentityMapper.findByContactId(contactId).stream()
                .filter(i -> "phone".equalsIgnoreCase(i.getChannelType()))
                .map(ContactIdentityEntity::getNormalizedValue)
                .filter(Objects::nonNull)
                .map(v -> v.startsWith("phone:") ? v : "phone:" + v)
                .toList();
        if (!anchors.isEmpty()) {
            for (CallRecordEntity call : callRecordMapper.listUnassignedByAnchors(new java.util.LinkedHashSet<>(anchors))) {
                if (after.isPresent() && !call.getOccurredAt().isAfter(after.get())) continue;
                String transcript = call.getTranscriptionResultOriginalText();
                String text = (nullToEmpty(transcript) + "\n" + nullToEmpty(call.getNote())).trim();
                items.add(new SourceItem(call.getId(), SourceType.CALL_RECORD, "phone", call.getOccurredAt(),
                        call.getDirection(), "", text));
            }
        }
        List<SourceItem> supported = new ArrayList<>(filterSupportedSources(items));
        supported.sort(Comparator.comparing(SourceItem::occurredAt).thenComparing(SourceItem::id));
        boolean hasMore = supported.size() > config.maxInputRecords();
        if (supported.size() > config.maxInputRecords()) supported = new ArrayList<>(supported.subList(0, config.maxInputRecords()));
        long bytes = 0;
        List<SourceItem> bounded = new ArrayList<>();
        for (SourceItem item : supported) {
            long itemBytes = canonical(item).getBytes(StandardCharsets.UTF_8).length;
            if (!bounded.isEmpty() && bytes + itemBytes > config.maxInputBytes()) { hasMore = true; break; }
            bounded.add(item); bytes += itemBytes;
        }
        return new InputBatch(List.copyOf(bounded), fingerprint(bounded), hasMore);
    }

    public InputBatch collect(UUID contactId, Optional<Instant> after) {
        List<SourceItem> items = new ArrayList<>();
        List<ConversationEntity> conversations = conversationMapper.listThreads(new Page<>(1, config.maxInputRecords(), false), contactId).getRecords();
        List<UUID> conversationIds = conversations.stream().map(ConversationEntity::getId).toList();
        if (!conversationIds.isEmpty()) {
            IPage<MessageEntity> page = messageMapper.listUnassignedMessagesByConversations(new Page<>(1, config.maxInputRecords(), false), conversationIds, null, null, null, true);
            for (MessageEntity message : page.getRecords()) {
                ChannelAccountEntity account = channelAccountMapper.selectById(message.getChannelAccountId());
                if (account == null || !isSupportedChannel(account.getChannelType())) continue;
                if (after.isPresent() && !message.getOccurredAt().isAfter(after.get())) continue;
                items.add(new SourceItem(message.getId(), SourceType.MESSAGE, account.getChannelType(), message.getOccurredAt(), message.getDirection(), nullToEmpty(message.getSubject()), nullToEmpty(message.getBodyText())));
            }
        }
        List<String> anchors = contactIdentityMapper.findByContactId(contactId).stream().filter(i -> "phone".equalsIgnoreCase(i.getChannelType())).map(ContactIdentityEntity::getNormalizedValue).filter(Objects::nonNull).map(v -> v.startsWith("phone:") ? v : "phone:" + v).toList();
        for (CallRecordEntity call : callRecordMapper.listUnassignedByAnchors(new java.util.LinkedHashSet<>(anchors))) {
            if (after.isPresent() && !call.getOccurredAt().isAfter(after.get())) continue;
            items.add(new SourceItem(call.getId(), SourceType.CALL_RECORD, "phone", call.getOccurredAt(), call.getDirection(), "", (nullToEmpty(call.getTranscriptionResultOriginalText()) + "\n" + nullToEmpty(call.getNote())).trim()));
        }
        return bounded(items);
    }

    public static List<SourceItem> filterSupportedSources(List<SourceItem> items) {
        return items.stream().filter(Objects::nonNull)
                .filter(i -> isSupportedChannel(i.channelType()))
                .sorted(Comparator.comparing(SourceItem::occurredAt).thenComparing(SourceItem::id))
                .toList();
    }

    public static String fingerprint(List<SourceItem> items) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (SourceItem item : filterSupportedSources(items)) digest.update(canonical(item).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private InputBatch bounded(List<SourceItem> items) {
        List<SourceItem> supported = new ArrayList<>(filterSupportedSources(items));
        boolean hasMore = supported.size() > config.maxInputRecords();
        if (supported.size() > config.maxInputRecords()) supported = new ArrayList<>(supported.subList(0, config.maxInputRecords()));
        long bytes = 0; List<SourceItem> bounded = new ArrayList<>();
        for (SourceItem item : supported) {
            long itemBytes = canonical(item).getBytes(StandardCharsets.UTF_8).length;
            if (!bounded.isEmpty() && bytes + itemBytes > config.maxInputBytes()) { hasMore = true; break; }
            bounded.add(item); bytes += itemBytes;
        }
        return new InputBatch(List.copyOf(bounded), fingerprint(bounded), hasMore);
    }

    private static String canonical(SourceItem item) {
        String[] fields = {
                item.sourceType().name(), item.id().toString(), item.occurredAt().toString(),
                item.channelType(), nullToEmpty(item.direction()), nullToEmpty(item.subject()), nullToEmpty(item.text())
        };
        StringBuilder encoded = new StringBuilder();
        for (String field : fields) {
            encoded.append(field.length()).append(':').append(field).append('|');
        }
        return encoded.append('\n').toString();
    }

    private static boolean isSupportedChannel(String channel) {
        return "chatapp".equalsIgnoreCase(channel) || "email".equalsIgnoreCase(channel) || "phone".equalsIgnoreCase(channel);
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}
