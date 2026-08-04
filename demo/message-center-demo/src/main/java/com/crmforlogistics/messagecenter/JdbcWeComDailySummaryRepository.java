package com.crmforlogistics.messagecenter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcWeComDailySummaryRepository implements WeComDailySummaryRepository {
    private static final int MAX_SUMMARY_BYTES = 65_536;
    private final Database database;

    public JdbcWeComDailySummaryRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void ensureDailyJob(DailySummaryKey key, List<String> msgidDigests,
                               Instant deadline, Instant now) throws Exception {
        validateKey(key);
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(now, "now");
        if (!deadline.isAfter(now) || msgidDigests == null
                || msgidDigests.size() != key.sliceEnd() - key.sliceStart()) {
            throw new IllegalArgumentException("daily summary job input invalid");
        }
        String messageDigest = aggregateDigest(msgidDigests);
        database.transaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into wecom_daily_summary_jobs
                        (installation_id,auth_corp_id,summary_day,user_id,external_user_id,
                         slice_start,slice_end,message_count,message_digest,status,
                         next_attempt_at,deadline_at,created_at,updated_at)
                    values (?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?)
                    on conflict (installation_id,auth_corp_id,summary_day,user_id,external_user_id,
                                 slice_start,slice_end) do nothing
                    """)) {
                int index = bindKey(insert, key, 1);
                insert.setInt(index++, key.sliceStart());
                insert.setInt(index++, key.sliceEnd());
                insert.setInt(index++, msgidDigests.size());
                insert.setString(index++, messageDigest);
                insert.setTimestamp(index++, Timestamp.from(now));
                insert.setTimestamp(index++, Timestamp.from(deadline));
                insert.setTimestamp(index++, Timestamp.from(now));
                insert.setTimestamp(index, Timestamp.from(now));
                insert.executeUpdate();
            }
            try (PreparedStatement verify = connection.prepareStatement("""
                    select message_digest,message_count,deadline_at
                    from wecom_daily_summary_jobs
                    where installation_id=? and auth_corp_id=? and summary_day=?
                      and user_id=? and external_user_id=? and slice_start=? and slice_end=?
                    """)) {
                int index = bindKey(verify, key, 1);
                verify.setInt(index++, key.sliceStart());
                verify.setInt(index, key.sliceEnd());
                try (ResultSet rows = verify.executeQuery()) {
                    if (!rows.next() || !messageDigest.equals(rows.getString("message_digest"))
                            || rows.getInt("message_count") != msgidDigests.size()
                            || !rows.getTimestamp("deadline_at").toInstant().equals(deadline)) {
                        throw new IllegalStateException("daily summary job identity drift");
                    }
                }
            }
            return null;
        });
    }

    @Override
    public Optional<LeasedSummaryJob> leaseNext(String owner, Instant now, Duration lease)
            throws Exception {
        requireText(owner, 100, "owner");
        Objects.requireNonNull(now, "now");
        if (lease == null || lease.isZero() || lease.isNegative()
                || lease.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("lease invalid");
        }
        return database.transaction(connection -> {
            UUID jobId = null;
            try (PreparedStatement select = connection.prepareStatement("""
                    select id from wecom_daily_summary_jobs
                    where status in ('PENDING','SUBMITTED','RETRY_WAIT')
                      and next_attempt_at<=?
                      and (lease_until is null or lease_until<=?)
                    order by next_attempt_at,created_at,id
                    for update skip locked limit 1
                    """)) {
                Timestamp current = Timestamp.from(now);
                select.setTimestamp(1, current);
                select.setTimestamp(2, current);
                try (ResultSet rows = select.executeQuery()) {
                    if (rows.next()) jobId = rows.getObject(1, UUID.class);
                }
            }
            if (jobId == null) return Optional.empty();
            try (PreparedStatement update = connection.prepareStatement("""
                    update wecom_daily_summary_jobs
                    set lease_owner=?,lease_until=?,updated_at=? where id=?
                    """)) {
                update.setString(1, owner);
                update.setTimestamp(2, Timestamp.from(now.plus(lease)));
                update.setTimestamp(3, Timestamp.from(now));
                update.setObject(4, jobId);
                update.executeUpdate();
            }
            return Optional.of(readJob(connection, jobId));
        });
    }

    @Override
    public void markSubmitted(UUID jobId, String wecomJobId, Instant nextPollAt) throws Exception {
        Objects.requireNonNull(jobId, "jobId");
        requireText(wecomJobId, 256, "wecomJobId");
        Objects.requireNonNull(nextPollAt, "nextPollAt");
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    update wecom_daily_summary_jobs
                    set status='SUBMITTED',wecom_job_id=?,next_attempt_at=?,lease_owner=null,
                        lease_until=null,submitted_at=coalesce(submitted_at,now()),updated_at=now()
                    where id=? and status in ('PENDING','RETRY_WAIT','SUBMITTED')
                      and (wecom_job_id is null or wecom_job_id=?)
                    """)) {
                statement.setString(1, wecomJobId);
                statement.setTimestamp(2, Timestamp.from(nextPollAt));
                statement.setObject(3, jobId);
                statement.setString(4, wecomJobId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("daily summary submit transition rejected");
                }
            }
            return null;
        });
    }

    @Override
    public void markCompleted(UUID jobId, String summary, Coverage coverage, Instant now)
            throws Exception {
        Objects.requireNonNull(jobId, "jobId");
        validateSummary(summary);
        validateCoverage(coverage);
        Objects.requireNonNull(now, "now");
        database.transaction(connection -> {
            JobGroup group = lockJobGroup(connection, jobId);
            if ("COMPLETED".equals(group.status())) return null;
            if (!"SUBMITTED".equals(group.status())) {
                throw new IllegalStateException("daily summary complete transition rejected");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    update wecom_daily_summary_jobs
                    set status='COMPLETED',batch_summary=?,lease_owner=null,lease_until=null,
                        completed_at=?,updated_at=? where id=? and status='SUBMITTED'
                    """)) {
                statement.setString(1, summary);
                statement.setTimestamp(2, Timestamp.from(now));
                statement.setTimestamp(3, Timestamp.from(now));
                statement.setObject(4, jobId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("daily summary complete transition rejected");
                }
            }
            finalizeGroup(connection, group, now);
            return null;
        });
    }

    @Override
    public void markRetry(UUID jobId, String code, Instant nextAttemptAt) throws Exception {
        Objects.requireNonNull(jobId, "jobId");
        requireText(code, 100, "code");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    update wecom_daily_summary_jobs
                    set status='RETRY_WAIT',attempt_count=attempt_count+1,next_attempt_at=?,
                        last_error_code=?,lease_owner=null,lease_until=null,updated_at=now()
                    where id=? and status in ('PENDING','SUBMITTED','RETRY_WAIT')
                      and attempt_count<20
                    """)) {
                statement.setTimestamp(1, Timestamp.from(nextAttemptAt));
                statement.setString(2, code);
                statement.setObject(3, jobId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("daily summary retry transition rejected");
                }
            }
            return null;
        });
    }

    @Override
    public void markFailed(UUID jobId, String code, String state, Instant now) throws Exception {
        Objects.requireNonNull(jobId, "jobId");
        requireText(code, 100, "code");
        requireText(state, 32, "state");
        Objects.requireNonNull(now, "now");
        database.transaction(connection -> {
            JobGroup group = lockJobGroup(connection, jobId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    update wecom_daily_summary_jobs
                    set status='FAILED',last_error_code=?,failure_state=?,lease_owner=null,
                        lease_until=null,completed_at=?,updated_at=?
                    where id=? and status in ('PENDING','SUBMITTED','RETRY_WAIT')
                    """)) {
                statement.setString(1, code);
                statement.setString(2, state);
                statement.setTimestamp(3, Timestamp.from(now));
                statement.setTimestamp(4, Timestamp.from(now));
                statement.setObject(5, jobId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("daily summary failure transition rejected");
                }
            }
            finalizeGroup(connection, group, now);
            return null;
        });
    }

    @Override
    public void replaceWithSplit(UUID jobId, List<String> leftMsgidDigests,
                                 List<String> rightMsgidDigests, int maxBatches, Instant now)
            throws Exception {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(now, "now");
        if (maxBatches < 2 || maxBatches > 32 || leftMsgidDigests == null
                || leftMsgidDigests.isEmpty() || rightMsgidDigests == null
                || rightMsgidDigests.isEmpty()) {
            throw new IllegalArgumentException("daily summary split invalid");
        }
        String leftDigest = aggregateDigest(leftMsgidDigests);
        String rightDigest = aggregateDigest(rightMsgidDigests);
        database.transaction(connection -> {
            SplitParent parent;
            try (PreparedStatement statement = connection.prepareStatement("""
                    select installation_id,auth_corp_id,summary_day,user_id,external_user_id,
                           slice_start,slice_end,status,wecom_job_id,deadline_at
                    from wecom_daily_summary_jobs where id=? for update
                    """)) {
                statement.setObject(1, jobId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("daily summary split parent missing");
                    parent = new SplitParent(rows.getString("installation_id"),
                            rows.getString("auth_corp_id"),
                            rows.getObject("summary_day", java.time.LocalDate.class),
                            rows.getString("user_id"), rows.getString("external_user_id"),
                            rows.getInt("slice_start"), rows.getInt("slice_end"),
                            rows.getString("status"), rows.getString("wecom_job_id"),
                            rows.getTimestamp("deadline_at").toInstant());
                }
            }
            if (!("PENDING".equals(parent.status()) || "RETRY_WAIT".equals(parent.status()))
                    || parent.wecomJobId() != null
                    || leftMsgidDigests.size() + rightMsgidDigests.size()
                    != parent.sliceEnd() - parent.sliceStart()) {
                throw new IllegalStateException("daily summary split transition rejected");
            }
            int activeCount;
            try (PreparedStatement count = connection.prepareStatement("""
                    select count(*) from wecom_daily_summary_jobs
                    where installation_id=? and auth_corp_id=? and summary_day=?
                      and user_id=? and external_user_id=? and status<>'SUPERSEDED'
                    """)) {
                bindSplitParent(count, parent, 1);
                try (ResultSet rows = count.executeQuery()) {
                    rows.next();
                    activeCount = rows.getInt(1);
                }
            }
            if (activeCount + 1 > maxBatches) {
                throw new IllegalStateException("daily summary batch limit exceeded");
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    update wecom_daily_summary_jobs
                    set status='SUPERSEDED',lease_owner=null,lease_until=null,updated_at=? where id=?
                    """)) {
                update.setTimestamp(1, Timestamp.from(now));
                update.setObject(2, jobId);
                update.executeUpdate();
            }
            int midpoint = parent.sliceStart() + leftMsgidDigests.size();
            insertSplitChild(connection, parent, parent.sliceStart(), midpoint,
                    leftMsgidDigests.size(), leftDigest, now);
            insertSplitChild(connection, parent, midpoint, parent.sliceEnd(),
                    rightMsgidDigests.size(), rightDigest, now);
            return null;
        });
    }

    @Override
    public Optional<DailySummary> findSummary(DailyConversationKey key) throws Exception {
        validateConversationKey(key);
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select summary,message_count,completed_message_count,batch_count,
                           completed_batch_count,completeness,generated_at
                    from wecom_daily_summaries
                    where installation_id=? and auth_corp_id=? and summary_day=?
                      and user_id=? and external_user_id=?
                    """)) {
                bindConversationKey(statement, key, 1);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    return Optional.of(new DailySummary(key, rows.getString("summary"),
                            rows.getInt("message_count"), rows.getInt("completed_message_count"),
                            rows.getInt("batch_count"), rows.getInt("completed_batch_count"),
                            rows.getString("completeness"),
                            rows.getTimestamp("generated_at").toInstant()));
                }
            }
        });
    }

    private static LeasedSummaryJob readJob(java.sql.Connection connection, UUID jobId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                select installation_id,auth_corp_id,summary_day,user_id,external_user_id,
                       slice_start,slice_end,message_digest,message_count,status,wecom_job_id,
                       attempt_count,deadline_at
                from wecom_daily_summary_jobs where id=?
                """)) {
            statement.setObject(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("leased summary job missing");
                DailySummaryKey key = new DailySummaryKey(rows.getString("installation_id"),
                        rows.getString("auth_corp_id"), rows.getObject("summary_day", java.time.LocalDate.class),
                        rows.getString("user_id"), rows.getString("external_user_id"),
                        rows.getInt("slice_start"), rows.getInt("slice_end"));
                return new LeasedSummaryJob(jobId, key, rows.getString("message_digest"),
                        rows.getInt("message_count"), rows.getString("status"),
                        rows.getString("wecom_job_id"), rows.getInt("attempt_count"),
                        rows.getTimestamp("deadline_at").toInstant());
            }
        }
    }

    private static JobGroup lockJobGroup(java.sql.Connection connection, UUID jobId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                select installation_id,auth_corp_id,summary_day,user_id,external_user_id,status
                from wecom_daily_summary_jobs where id=? for update
                """)) {
            statement.setObject(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("daily summary job missing");
                return new JobGroup(rows.getString("installation_id"), rows.getString("auth_corp_id"),
                        rows.getObject("summary_day", java.time.LocalDate.class), rows.getString("user_id"),
                        rows.getString("external_user_id"), rows.getString("status"));
            }
        }
    }

    private static void finalizeGroup(java.sql.Connection connection, JobGroup group,
                                      Instant now) throws Exception {
        int nonTerminal;
        int batchCount;
        int messageCount;
        int completedBatches;
        int completedMessageCount;
        int failedBatches;
        String combinedSummary;
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) filter (where status not in ('COMPLETED','FAILED')) as non_terminal,
                       count(*) as batch_count,
                       coalesce(sum(message_count),0) as message_count,
                       count(*) filter (where status='COMPLETED') as completed_batches,
                       coalesce(sum(message_count) filter (where status='COMPLETED'),0)
                           as completed_message_count,
                       count(*) filter (where status='FAILED') as failed_batches,
                       string_agg(batch_summary, E'\n\n' order by slice_start)
                           filter (where status='COMPLETED') as combined_summary
                from wecom_daily_summary_jobs
                where installation_id=? and auth_corp_id=? and summary_day=?
                  and user_id=? and external_user_id=? and status<>'SUPERSEDED'
                """)) {
            bindGroup(statement, group, 1);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                nonTerminal = rows.getInt("non_terminal");
                batchCount = rows.getInt("batch_count");
                messageCount = rows.getInt("message_count");
                completedBatches = rows.getInt("completed_batches");
                completedMessageCount = rows.getInt("completed_message_count");
                failedBatches = rows.getInt("failed_batches");
                combinedSummary = rows.getString("combined_summary");
            }
        }
        if (nonTerminal != 0 || completedBatches == 0 || combinedSummary == null) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into wecom_daily_summaries
                    (installation_id,auth_corp_id,summary_day,user_id,external_user_id,summary,
                     message_count,completed_message_count,batch_count,completed_batch_count,
                     completeness,generated_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict (installation_id,auth_corp_id,summary_day,user_id,external_user_id)
                do nothing
                """)) {
            int index = bindGroup(statement, group, 1);
            statement.setString(index++, combinedSummary);
            statement.setInt(index++, messageCount);
            statement.setInt(index++, completedMessageCount);
            statement.setInt(index++, batchCount);
            statement.setInt(index++, completedBatches);
            statement.setString(index++, failedBatches == 0 ? "COMPLETE" : "PARTIAL");
            statement.setTimestamp(index, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private static int bindKey(PreparedStatement statement, DailySummaryKey key, int index)
            throws Exception {
        statement.setString(index++, key.installationId());
        statement.setString(index++, key.authCorpId());
        statement.setObject(index++, key.day());
        statement.setString(index++, key.userId());
        statement.setString(index++, key.externalUserId());
        return index;
    }

    private static int bindConversationKey(PreparedStatement statement, DailyConversationKey key,
                                           int index) throws Exception {
        statement.setString(index++, key.installationId());
        statement.setString(index++, key.authCorpId());
        statement.setObject(index++, key.day());
        statement.setString(index++, key.userId());
        statement.setString(index++, key.externalUserId());
        return index;
    }

    private static int bindGroup(PreparedStatement statement, JobGroup group, int index)
            throws Exception {
        statement.setString(index++, group.installationId());
        statement.setString(index++, group.authCorpId());
        statement.setObject(index++, group.day());
        statement.setString(index++, group.userId());
        statement.setString(index++, group.externalUserId());
        return index;
    }

    private static int bindSplitParent(PreparedStatement statement, SplitParent parent, int index)
            throws Exception {
        statement.setString(index++, parent.installationId());
        statement.setString(index++, parent.authCorpId());
        statement.setObject(index++, parent.day());
        statement.setString(index++, parent.userId());
        statement.setString(index++, parent.externalUserId());
        return index;
    }

    private static void insertSplitChild(java.sql.Connection connection, SplitParent parent,
                                         int start, int end, int messageCount,
                                         String messageDigest, Instant now) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into wecom_daily_summary_jobs
                    (installation_id,auth_corp_id,summary_day,user_id,external_user_id,
                     slice_start,slice_end,message_count,message_digest,status,
                     next_attempt_at,deadline_at,created_at,updated_at)
                values (?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?)
                """)) {
            int index = bindSplitParent(statement, parent, 1);
            statement.setInt(index++, start);
            statement.setInt(index++, end);
            statement.setInt(index++, messageCount);
            statement.setString(index++, messageDigest);
            statement.setTimestamp(index++, Timestamp.from(now));
            statement.setTimestamp(index++, Timestamp.from(parent.deadline()));
            statement.setTimestamp(index++, Timestamp.from(now));
            statement.setTimestamp(index, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private static String aggregateDigest(List<String> digests) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (String digest : digests) {
            if (digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("msgid digest invalid");
            }
            hash.update(digest.getBytes(StandardCharsets.US_ASCII));
            hash.update((byte) '\n');
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static void validateKey(DailySummaryKey key) {
        if (key == null || key.day() == null || key.sliceStart() < 0
                || key.sliceEnd() <= key.sliceStart()) {
            throw new IllegalArgumentException("daily summary key invalid");
        }
        requireText(key.installationId(), 128, "installationId");
        requireText(key.authCorpId(), 128, "authCorpId");
        requireText(key.userId(), 128, "userId");
        requireText(key.externalUserId(), 128, "externalUserId");
    }

    private static void validateConversationKey(DailyConversationKey key) {
        if (key == null || key.day() == null) {
            throw new IllegalArgumentException("daily conversation key invalid");
        }
        requireText(key.installationId(), 128, "installationId");
        requireText(key.authCorpId(), 128, "authCorpId");
        requireText(key.userId(), 128, "userId");
        requireText(key.externalUserId(), 128, "externalUserId");
    }

    private static void validateCoverage(Coverage coverage) {
        if (coverage == null || coverage.messageCount() < 1
                || coverage.completedMessageCount() < 1
                || coverage.completedMessageCount() > coverage.messageCount()
                || coverage.batchCount() < 1
                || !("COMPLETE".equals(coverage.completeness())
                || "PARTIAL".equals(coverage.completeness()))) {
            throw new IllegalArgumentException("coverage invalid");
        }
    }

    private static void validateSummary(String summary) {
        requireText(summary, Integer.MAX_VALUE, "summary");
        if (summary.getBytes(StandardCharsets.UTF_8).length > MAX_SUMMARY_BYTES) {
            throw new IllegalArgumentException("summary too large");
        }
    }

    private static void requireText(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(field + " invalid");
        }
    }

    private record JobGroup(String installationId, String authCorpId, java.time.LocalDate day,
                            String userId, String externalUserId, String status) {}
    private record SplitParent(String installationId, String authCorpId, java.time.LocalDate day,
                               String userId, String externalUserId, int sliceStart, int sliceEnd,
                               String status, String wecomJobId, Instant deadline) {}
}
