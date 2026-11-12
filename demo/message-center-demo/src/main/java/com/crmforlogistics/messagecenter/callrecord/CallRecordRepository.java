package com.crmforlogistics.messagecenter.callrecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface CallRecordRepository extends AutoCloseable {
    Optional<CallRecord> find(UUID id) throws CallRecordException;

    Optional<CallRecord> findByIdempotency(String anchorPointId, String clientRequestId)
            throws CallRecordException;

    List<CallRecord> listByAnchors(Set<String> anchorPointIds) throws CallRecordException;

    int countPending() throws CallRecordException;

    void saveNew(CallRecord record) throws CallRecordException;

    CallRecord replace(CallRecord replacement, long expectedVersion) throws CallRecordException;

    List<CallRecord> recoverProcessing(Instant now) throws CallRecordException;

    List<CallRecord> listRunnable(Instant now, int limit) throws CallRecordException;

    @Override
    void close() throws CallRecordException;
}
