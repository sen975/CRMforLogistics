package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnWeComEnabled
public class WeComViewerReferenceLeaseRegistry {
    private static final int DEFAULT_MAX_LEASES = 512;
    private static final int DEFAULT_MAX_MESSAGE_IDS = 15;

    private final Clock clock;
    private final int maxLeases;
    private final int maxMessageIds;
    private final Map<String, Lease> leases = new LinkedHashMap<>();

    public WeComViewerReferenceLeaseRegistry() {
        this(Clock.systemUTC(), DEFAULT_MAX_LEASES, DEFAULT_MAX_MESSAGE_IDS);
    }

    WeComViewerReferenceLeaseRegistry(Clock clock, int maxLeases, int maxMessageIds) {
        if (clock == null || maxLeases < 1 || maxMessageIds < 1 || maxMessageIds > DEFAULT_MAX_MESSAGE_IDS) {
            throw new IllegalArgumentException("WeCom viewer reference lease limits are invalid");
        }
        this.clock = clock;
        this.maxLeases = maxLeases;
        this.maxMessageIds = maxMessageIds;
    }

    public synchronized void acquire(String viewerSessionId, List<String> messageIds,
                                     long expiresAtEpochSecond) {
        requireBounded(viewerSessionId, "WeCom viewer session id", 64);
        long now = clock.instant().getEpochSecond();
        if (expiresAtEpochSecond <= now) {
            throw new IllegalArgumentException("WeCom viewer reference lease must expire in the future");
        }
        List<String> boundedIds = boundedMessageIds(messageIds);
        cleanupExpired(now);
        leases.remove(viewerSessionId);
        while (leases.size() >= maxLeases) {
            String oldest = leases.keySet().iterator().next();
            leases.remove(oldest);
        }
        leases.put(viewerSessionId, new Lease(boundedIds, expiresAtEpochSecond));
    }

    public synchronized void release(String viewerSessionId) {
        if (viewerSessionId != null) {
            leases.remove(viewerSessionId);
        }
    }

    public synchronized boolean isLeased(String messageId) {
        cleanupExpired(clock.instant().getEpochSecond());
        if (messageId == null || messageId.isBlank()) return false;
        return leases.values().stream().anyMatch(lease -> lease.messageIds().contains(messageId));
    }

    public synchronized Set<String> leasedMessageIds() {
        cleanupExpired(clock.instant().getEpochSecond());
        Set<String> result = new LinkedHashSet<>();
        leases.values().forEach(lease -> result.addAll(lease.messageIds()));
        return Set.copyOf(result);
    }

    private void cleanupExpired(long now) {
        leases.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtEpochSecond());
    }

    private List<String> boundedMessageIds(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty() || messageIds.size() > maxMessageIds) {
            throw new IllegalArgumentException("WeCom viewer reference lease messageIds are invalid");
        }
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>(messageIds.size());
        for (String value : messageIds) {
            String messageId = value == null ? "" : value.trim();
            if (messageId.isBlank() || messageId.length() > 256 || !seen.add(messageId)) {
                throw new IllegalArgumentException("WeCom viewer reference lease messageIds are invalid");
            }
            result.add(messageId);
        }
        return List.copyOf(result);
    }

    private static void requireBounded(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is required and must be bounded");
        }
    }

    private record Lease(List<String> messageIds, long expiresAtEpochSecond) {}
}
