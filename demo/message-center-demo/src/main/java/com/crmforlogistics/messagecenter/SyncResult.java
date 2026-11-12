package com.crmforlogistics.messagecenter;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class SyncResult {
    private static final int MEDIA_FAILURE_LIMIT = 20;

    public String channel;
    public int fetched;
    public int saved;
    public int updated;
    public int skipped;
    public int pages;
    public long durationMillis;
    public long syncStartTime;
    public long syncEndTime;
    public int mediaCached;
    public int mediaQueued;
    public int mediaFailed;
    public List<MediaFailure> mediaFailures = new ArrayList<>();
    public int templatesFetched;
    public int templatesSaved;
    public int templatesSkipped;
    public String message;

    public SyncResult(String channel) {
        this.channel = channel;
    }

    public void recordMediaFailure(UnifiedMessage message, Exception ex) {
        if (mediaFailures.size() >= MEDIA_FAILURE_LIMIT) {
            return;
        }
        MediaFailure failure = new MediaFailure();
        failure.messageId = message == null ? "" : ContactPointUtil.firstNonBlank(message.sourceId, message.id);
        failure.mediaType = message == null ? "" : ContactPointUtil.firstNonBlank(message.mediaType, "");
        failure.mediaHost = hostFromUrl(message == null ? "" : message.mediaUrl);
        failure.objectKey = message == null ? "" : ContactPointUtil.firstNonBlank(message.objectKey, objectKeyFromUrl(message.mediaUrl));
        failure.reason = cleanReason(ex == null ? "" : ex.getMessage());
        mediaFailures.add(failure);
    }

    private static String cleanReason(String value) {
        String cleaned = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.length() > 220 ? cleaned.substring(0, 220) : cleaned;
    }

    private static String hostFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static String objectKeyFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isBlank()) {
                return "";
            }
            return path.startsWith("/") ? path.substring(1) : path;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    public static class MediaFailure {
        public String messageId;
        public String mediaType;
        public String mediaHost;
        public String objectKey;
        public String reason;

        @Override
        public String toString() {
            return "MediaFailure{"
                    + "messageId='" + messageId + '\''
                    + ", mediaType='" + mediaType + '\''
                    + ", mediaHost='" + mediaHost + '\''
                    + ", objectKey='" + objectKey + '\''
                    + ", reason='" + reason + '\''
                    + '}';
        }
    }
}
