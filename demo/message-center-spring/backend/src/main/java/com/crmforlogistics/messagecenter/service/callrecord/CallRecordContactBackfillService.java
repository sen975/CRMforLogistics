package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

@Service
public class CallRecordContactBackfillService {
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;

    private final CallRecordMapper records;
    private final UserMapper users;
    private final CallRecordContactBackfillWorker worker;
    private final ContactMapper contacts;

    public CallRecordContactBackfillService(CallRecordMapper records, UserMapper users,
                                            CallRecordContactBackfillWorker worker) {
        this(records, users, worker, null);
    }

    @Autowired
    public CallRecordContactBackfillService(CallRecordMapper records, UserMapper users,
                                            CallRecordContactBackfillWorker worker,
                                            ContactMapper contacts) {
        this.records = records;
        this.users = users;
        this.worker = worker;
        this.contacts = contacts;
    }

    public BackfillResult run(int requestedLimit) {
        int limit = requestedLimit <= 0 ? DEFAULT_LIMIT : Math.min(requestedLimit, MAX_LIMIT);
        List<CallRecordEntity> candidates = records.listContactBackfillCandidates(limit);
        int processed = 0;
        int createdContacts = 0;
        int linkedExistingContacts = 0;
        int skippedUnowned = 0;
        int failed = 0;
        for (CallRecordEntity record : candidates) {
            processed++;
            try {
                UUID owner = resolveOwner(record);
                if (owner == null) {
                    skippedUnowned++;
                    continue;
                }
                String normalized = normalizePhone(record.getPhonePointId());
                if (normalized == null) {
                    failed++;
                    continue;
                }
                if (worker.process(record, owner, normalized)) createdContacts++;
                else linkedExistingContacts++;
            } catch (Exception ignored) {
                failed++;
            }
        }
        return new BackfillResult(processed, createdContacts, linkedExistingContacts,
                skippedUnowned, failed);
    }

    private UUID resolveOwner(CallRecordEntity record) {
        if (contacts != null && record.getContactId() != null) {
            UUID creator = contacts.findCreatedBy(record.getContactId()).orElse(null);
            if (creator != null) return creator;
        }
        String createdBy = record.getCreatedBy();
        if (createdBy == null || createdBy.isBlank()) return null;
        try {
            UUID candidate = UUID.fromString(createdBy.trim());
            return users.findByIdNotDeleted(candidate).map(user -> candidate).orElse(null);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalizePhone(String raw) {
        if (raw == null || !raw.startsWith("phone:")) return null;
        String digits = raw.substring("phone:".length()).replaceAll("[^0-9]", "");
        return digits.length() >= 6 && digits.length() <= 20 ? digits : null;
    }

    public record BackfillResult(int processed, int createdContacts, int linkedExistingContacts,
                                 int skippedUnowned, int failed) {}
}
