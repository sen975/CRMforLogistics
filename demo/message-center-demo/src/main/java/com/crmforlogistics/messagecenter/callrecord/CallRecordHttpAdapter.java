package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;
import com.crmforlogistics.messagecenter.UnifiedMessage;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUpload;
import org.apache.commons.fileupload.FileUploadBase;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.RequestContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class CallRecordHttpAdapter {
    private static final Gson GSON = new Gson();
    private static final long MULTIPART_OVERHEAD_BYTES = 65_536L;
    private static final int MAX_FIELD_BYTES = 8_192;
    private static final int MAX_FIELDS = 8;
    private static final int MAX_JSON_BODY_BYTES = 65_536;
    private final Config config;
    private final CallRecordService service;
    private final ContactTimelineService timeline;
    private final LocalAudioStore audioStore;
    private final CallAudioSessionService audioSessions;
    private final ViewerActorResolver actors;

    public CallRecordHttpAdapter(Config config, CallRecordService service,
                                 ContactTimelineService timeline,
                                 LocalAudioStore audioStore,
                                 CallAudioSessionService audioSessions,
                                 ViewerActorResolver actors) {
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.timeline = java.util.Objects.requireNonNull(timeline, "timeline");
        this.audioStore = java.util.Objects.requireNonNull(audioStore, "audioStore");
        this.audioSessions = java.util.Objects.requireNonNull(audioSessions, "audioSessions");
        this.actors = java.util.Objects.requireNonNull(actors, "actors");
    }

    public boolean handle(HttpExchange exchange) throws IOException {
        String rawPath = exchange.getRequestURI().getRawPath();
        String[] segments = rawSegments(rawPath);
        if (segments == null || !apiV1(segments)) {
            return false;
        }
        boolean createPath = segments.length == 6 && "contacts".equals(segments[3])
                && "call-records".equals(segments[5]);
        boolean timelinePath = segments.length == 6 && "contacts".equals(segments[3])
                && "timeline".equals(segments[5]);
        boolean detailPath = segments.length == 5 && "call-records".equals(segments[3]);
        boolean audioSessionPath = segments.length == 6 && "call-records".equals(segments[3])
                && "audio-sessions".equals(segments[5]);
        boolean audioPath = segments.length == 6 && "call-records".equals(segments[3])
                && "audio".equals(segments[5]);
        boolean retryPath = segments.length == 6 && "call-records".equals(segments[3])
                && "retry".equals(segments[5]);
        boolean transcriptPath = segments.length == 6 && "call-records".equals(segments[3])
                && "transcript".equals(segments[5]);
        if (!(createPath || timelinePath || detailPath || audioSessionPath
                || audioPath || retryPath || transcriptPath)) return false;
        boolean createRoute = createPath && "POST".equals(exchange.getRequestMethod());
        boolean timelineRoute = timelinePath && "GET".equals(exchange.getRequestMethod());
        boolean detailRoute = detailPath && "GET".equals(exchange.getRequestMethod());
        boolean audioSessionRoute = audioSessionPath && "POST".equals(exchange.getRequestMethod());
        boolean audioRoute = audioPath && "GET".equals(exchange.getRequestMethod());
        boolean retryRoute = retryPath && "POST".equals(exchange.getRequestMethod());
        boolean transcriptRoute = transcriptPath && "PATCH".equals(exchange.getRequestMethod());
        try {
            if (!(createRoute || timelineRoute || detailRoute || audioSessionRoute
                    || audioRoute || retryRoute || transcriptRoute)) {
                writeMethodNotAllowed(exchange);
                return true;
            }
            String contactId = createRoute || timelineRoute
                    ? decodeRawPathSegment(segments[4]) : null;
            if ((detailRoute || audioSessionRoute || audioRoute || retryRoute || transcriptRoute)
                    && segments[4].indexOf('%') >= 0) throw routePathInvalid();
            UUID callId = detailRoute || audioSessionRoute || audioRoute
                    || retryRoute || transcriptRoute
                    ? parseUuid(segments[4]) : null;
            if (audioRoute) {
                CallAudioSessionService.AudioAuthorization authorization =
                        audioSessions.authorize(audioCookie(exchange), callId);
                CallRecord record = service.authenticatedDetail(authorization.callRecordId());
                writeAudio(exchange, audioStore.path(record.audio()));
                return true;
            }
            String actor;
            try {
                actor = actors.requireActor(
                        exchange.getRequestHeaders().getFirst("X-WeCom-Viewer-Auth"));
            } catch (CallRecordException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new CallRecordException(
                        "AUTH_REQUIRED", 401, "无法取得认证操作人", false, exception);
            }
            if (createRoute) {
                create(exchange, contactId, actor);
            } else if (timelineRoute) {
                writeJson(exchange, 200, timelineProjection(timeline.page(
                        contactId, queryValue(exchange, "cursor"),
                        queryInt(exchange, "limit", 20))));
            } else if (detailRoute) {
                writeJson(exchange, 200, detailProjection(
                        service.authenticatedDetail(callId)));
            } else if (audioSessionRoute) {
                service.authenticatedDetail(callId);
                CallAudioSessionService.AudioSessionCookie cookie =
                        audioSessions.create(actor, callId);
                exchange.getResponseHeaders().add("Set-Cookie", cookie.headerValue());
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            } else if (retryRoute) {
                JsonObject body = readJson(exchange, "clientRequestId");
                CallRecord retried = service.retry(callId,
                        actor, requiredString(body, "clientRequestId"));
                writeJson(exchange, 200, detailProjection(retried));
            } else {
                JsonObject body = readJson(exchange, "text", "expectedVersion");
                String text = requiredString(body, "text");
                if (!body.has("expectedVersion")
                        || !body.get("expectedVersion").isJsonPrimitive()
                        || !body.get("expectedVersion").getAsJsonPrimitive().isNumber()) {
                    throw jsonFieldInvalid();
                }
                CallRecord revised = service.revise(callId, text, actor,
                        body.get("expectedVersion").getAsLong());
                writeJson(exchange, 200, detailProjection(revised));
            }
        } catch (CallRecordException exception) {
            writeError(exchange, exception);
        } catch (Exception exception) {
            writeError(exchange, new CallRecordException(
                    "CALL_RECORD_INTERNAL_ERROR", 500,
                    "电话记录请求处理失败", false, exception));
        }
        return true;
    }

    public static boolean matchesRoute(String rawPath) {
        String[] segments = rawSegments(rawPath);
        if (segments == null || !apiV1(segments)) return false;
        return (segments.length == 6 && "contacts".equals(segments[3])
                && ("call-records".equals(segments[5]) || "timeline".equals(segments[5])))
                || (segments.length == 5 && "call-records".equals(segments[3]))
                || (segments.length == 6 && "call-records".equals(segments[3])
                && ("audio-sessions".equals(segments[5]) || "audio".equals(segments[5])
                || "retry".equals(segments[5]) || "transcript".equals(segments[5])));
    }

    private static JsonObject readJson(HttpExchange exchange, String... allowed)
            throws IOException, CallRecordException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_JSON_BODY_BYTES + 1);
        if (bytes.length > MAX_JSON_BODY_BYTES) {
            throw new CallRecordException(
                    "JSON_BODY_TOO_LARGE", 413, "JSON 请求体超过大小限制", false);
        }
        final JsonObject object;
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            object = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw jsonFieldInvalid();
        }
        List<String> allowedFields = List.of(allowed);
        for (String field : object.keySet()) {
            if (!allowedFields.contains(field)) throw jsonFieldInvalid();
        }
        return object;
    }

    private static String requiredString(JsonObject object, String name)
            throws CallRecordException {
        if (!object.has(name) || object.get(name).isJsonNull()
                || !object.get(name).isJsonPrimitive()
                || !object.get(name).getAsJsonPrimitive().isString()) {
            throw jsonFieldInvalid();
        }
        return object.get(name).getAsString();
    }

    private static CallRecordException jsonFieldInvalid() {
        return new CallRecordException(
                "JSON_FIELD_INVALID", 400, "JSON 字段无效", false);
    }

    private static String audioCookie(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().getOrDefault("Cookie", List.of());
        for (String header : headers) {
            for (String cookie : header.split(";")) {
                String trimmed = cookie.trim();
                if (trimmed.startsWith("mc_call_audio=")) {
                    return trimmed.substring("mc_call_audio=".length());
                }
            }
        }
        return "";
    }

    private static void writeMethodNotAllowed(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Allow", "GET");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(405, -1);
        exchange.close();
    }

    private static void writeAudio(HttpExchange exchange, Path path)
            throws IOException, CallRecordException {
        long size = Files.size(path);
        Range range;
        try {
            range = parseRange(exchange.getRequestHeaders().getFirst("Range"), size);
        } catch (CallRecordException exception) {
            exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
            throw exception;
        }
        long length = range.end() - range.start() + 1;
        exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (range.partial()) {
            exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + range.start() + "-" + range.end() + "/" + size);
        }
        exchange.sendResponseHeaders(range.partial() ? 206 : 200, length);
        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ);
             OutputStream output = exchange.getResponseBody()) {
            input.position(range.start());
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            long remaining = length;
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = input.read(buffer);
                if (read < 0) throw new IOException("音频文件读取提前结束");
                buffer.flip();
                output.write(buffer.array(), 0, read);
                remaining -= read;
            }
        }
    }

    private static Range parseRange(String header, long size) throws CallRecordException {
        if (header == null || header.isBlank()) return new Range(0, size - 1, false);
        if (!header.startsWith("bytes=") || header.indexOf(',') >= 0) throw rangeInvalid();
        String value = header.substring("bytes=".length());
        int dash = value.indexOf('-');
        if (dash < 0 || value.indexOf('-', dash + 1) >= 0) throw rangeInvalid();
        try {
            String left = value.substring(0, dash);
            String right = value.substring(dash + 1);
            long start;
            long end;
            if (left.isEmpty()) {
                long suffix = Long.parseLong(right);
                if (suffix <= 0) throw rangeInvalid();
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(left);
                end = right.isEmpty() ? size - 1 : Long.parseLong(right);
                if (start < 0 || start >= size || end < start) throw rangeInvalid();
                end = Math.min(end, size - 1);
            }
            return new Range(start, end, true);
        } catch (NumberFormatException exception) {
            throw rangeInvalid();
        }
    }

    private static CallRecordException rangeInvalid() {
        return new CallRecordException(
                "AUDIO_RANGE_INVALID", 416, "音频 Range 请求无效", false);
    }

    private record Range(long start, long end, boolean partial) {}

    private static boolean apiV1(String[] segments) {
        return segments.length >= 3 && "api".equals(segments[1]) && "v1".equals(segments[2]);
    }

    private static UUID parseUuid(String raw) throws CallRecordException {
        try {
            UUID id = UUID.fromString(raw);
            if (!id.toString().equals(raw)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException exception) {
            throw routePathInvalid();
        }
    }

    private static Map<String, Object> timelineProjection(
            ContactTimelineService.TimelinePage page) {
        return Map.of(
                "items", page.items().stream().map(CallRecordHttpAdapter::timelineItemProjection).toList(),
                "nextCursor", page.nextCursor() == null ? "" : page.nextCursor(),
                "itemCount", page.itemCount(),
                "threadRevision", page.threadRevision());
    }

    private static Map<String, Object> timelineItemProjection(
            ContactTimelineService.TimelineItem item) {
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("type", item.type());
        projected.put("occurredAt", item.occurredAt().toString());
        projected.put("sortId", item.sortId());
        if (item.message() != null) projected.put("payload", messageProjection(item.message()));
        else projected.put("payload", callCardProjection(item.callRecordCard()));
        return projected;
    }

    private static Map<String, Object> callCardProjection(
            ContactTimelineService.CallRecordCard card) {
        return Map.of(
                "id", card.id().toString(),
                "direction", card.direction(),
                "phonePointId", card.phonePointId(),
                "occurredAt", card.occurredAt().toString(),
                "durationSeconds", card.durationSeconds(),
                "state", card.state(),
                "errorCode", card.errorCode(),
                "version", card.version());
    }

    private static Map<String, Object> messageProjection(UnifiedMessage message) {
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("id", scalar(message.id));
        projected.put("sourceId", scalar(message.sourceId));
        projected.put("channel", scalar(message.channel));
        projected.put("contactPointId", scalar(message.contactPointId));
        projected.put("direction", scalar(message.direction));
        projected.put("timestamp", scalar(message.timestamp));
        projected.put("from", scalar(message.from));
        projected.put("to", scalar(message.to));
        projected.put("title", scalar(message.title));
        projected.put("text", scalar(message.text));
        projected.put("summary", scalar(message.summary));
        projected.put("bodyText", scalar(message.bodyText));
        projected.put("status", scalar(message.status));
        projected.put("statusTimestamp", scalar(message.statusTimestamp));
        projected.put("mediaType", scalar(message.mediaType));
        projected.put("mediaUrl", scalar(message.mediaUrl));
        projected.put("mimeType", scalar(message.mimeType));
        projected.put("fileName", scalar(message.fileName));
        projected.put("countsAsUnread", message.countsAsUnread);
        projected.put("unread", message.unread);
        return projected;
    }

    private static Map<String, Object> detailProjection(CallRecord record) {
        Transcription transcription = record.transcription();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", record.id().toString());
        detail.put("contactAnchorPointId", record.contactAnchorPointId());
        detail.put("phonePointId", record.phonePointId());
        detail.put("direction", record.direction());
        detail.put("occurredAt", record.occurredAt().toString());
        detail.put("createdAt", record.createdAt().toString());
        detail.put("clientRequestId", record.clientRequestId());
        detail.put("audio", Map.of(
                "originalFileName", record.audio().originalFileName(),
                "sizeBytes", record.audio().sizeBytes(),
                "sha256", record.audio().sha256(),
                "contentType", record.audio().contentType(),
                "durationSeconds", record.audio().durationSeconds()));
        Map<String, Object> projectedTranscription = new LinkedHashMap<>();
        projectedTranscription.put("state", transcription.state());
        projectedTranscription.put("model", scalar(transcription.model()));
        projectedTranscription.put("attempts", transcription.attempts());
        projectedTranscription.put("result", transcription.result() == null
                ? Map.of() : resultProjection(transcription.result()));
        projectedTranscription.put("error", transcription.error() == null
                ? Map.of() : Map.of("code", transcription.error().code(),
                "message", transcription.error().message(),
                "retryable", transcription.error().retryable()));
        detail.put("transcription", projectedTranscription);
        detail.put("revisions", record.revisions().stream().map(revision -> Map.of(
                "id", revision.id().toString(),
                "text", revision.text(),
                "editedAt", revision.editedAt().toString(),
                "editedBy", revision.editedBy())).toList());
        detail.put("currentRevisionId", record.currentRevisionId() == null
                ? "" : record.currentRevisionId().toString());
        detail.put("version", record.version());
        return detail;
    }

    private static Map<String, Object> resultProjection(TranscriptionResult result) {
        return Map.of(
                "model", result.model(),
                "durationSeconds", result.durationSeconds(),
                "originalText", result.originalText(),
                "segments", result.segments().stream().map(segment -> Map.of(
                        "startSeconds", segment.startSeconds(),
                        "endSeconds", segment.endSeconds(),
                        "text", segment.text())).toList(),
                "completedAt", result.completedAt().toString());
    }

    private static String scalar(String value) { return value == null ? "" : value; }

    private static String queryValue(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) return "";
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            if (name.equals(parts[0])) return parts.length == 1 ? "" : parts[1];
        }
        return "";
    }

    private static int queryInt(HttpExchange exchange, String name, int fallback) {
        try { return Integer.parseInt(queryValue(exchange, name)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private void create(HttpExchange exchange, String contactId, String actor)
            throws Exception {
        long declaredLength = declaredLength(exchange);
        long maximumBody = config.callRecordMaxAudioBytes() + MULTIPART_OVERHEAD_BYTES;
        if (declaredLength > maximumBody) {
            throw new CallRecordException(
                    "AUDIO_TOO_LARGE", 413, "MP3 文件超过大小限制", false);
        }
        FileUpload upload = new FileUpload();
        upload.setFileSizeMax(config.callRecordMaxAudioBytes());
        upload.setSizeMax(maximumBody);
        upload.setPartHeaderSizeMax(8_192);

        LocalAudioStore.StagedAudio staged = null;
        try {
            FileItemIterator items = upload.getItemIterator(new ExchangeRequestContext(exchange));
            Map<String, String> fields = new LinkedHashMap<>();
            boolean fileSeen = false;
            int fieldCount = 0;
            while (items.hasNext()) {
                FileItemStream item = items.next();
                String name = item.getFieldName();
                if (item.isFormField()) {
                    if (++fieldCount > MAX_FIELDS || !allowedField(name)
                            || fields.containsKey(name)) {
                        throw partInvalid();
                    }
                    fields.put(name, readField(item.openStream()));
                    continue;
                }
                if (!"file".equals(name) || fileSeen) {
                    throw new CallRecordException(
                            "MULTIPART_ORDER_INVALID", 400,
                            "MP3 文件必须是最后且唯一的文件字段", false);
                }
                fileSeen = true;
                CallRecordService.CreateCallRecordCommand command =
                        new CallRecordService.CreateCallRecordCommand(
                                contactId, fields.getOrDefault("phonePointId", ""),
                                fields.get("direction"), parseInstant(fields.get("occurredAt")),
                                fields.get("clientRequestId"), item.getName(),
                                item.getContentType(), actor);
                try (CallRecordService.PreparedCreate prepared =
                             service.prepareCreate(command)) {
                    if (prepared.existing() != null) {
                        writeCreated(exchange, prepared.existing());
                        return;
                    }
                    staged = audioStore.stage(
                            item.openStream(), item.getName(), item.getContentType());
                    if (items.hasNext()) {
                        throw new CallRecordException(
                                "MULTIPART_ORDER_INVALID", 400,
                                "MP3 文件必须是 multipart 的最后一部分", false);
                    }
                    CallRecord created = service.create(prepared, staged);
                    staged = null;
                    writeCreated(exchange, created);
                    return;
                }
            }
            throw multipartInvalid();
        } catch (FileUploadBase.SizeLimitExceededException
                 | FileUploadBase.FileSizeLimitExceededException exception) {
            throw new CallRecordException(
                    "AUDIO_TOO_LARGE", 413, "MP3 文件超过大小限制", false, exception);
        } catch (FileUploadException exception) {
            throw new CallRecordException(
                    "MULTIPART_INVALID", 400, "multipart 请求无效", false, exception);
        } finally {
            audioStore.discard(staged);
        }
    }

    private static void writeCreated(HttpExchange exchange, CallRecord created)
            throws IOException {
        writeJson(exchange, 202, Map.of(
                "callRecordId", created.id().toString(),
                "state", created.transcription().state(),
                "clientRequestId", created.clientRequestId()));
    }

    private static boolean allowedField(String name) {
        return "phonePointId".equals(name) || "direction".equals(name)
                || "occurredAt".equals(name) || "clientRequestId".equals(name);
    }

    private static String readField(InputStream input) throws IOException, CallRecordException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        copy(input, bytes, MAX_FIELD_BYTES);
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }

    private static void copy(InputStream input, OutputStream output, long maximum)
            throws IOException, CallRecordException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        for (int read; (read = input.read(buffer)) != -1; ) {
            total += read;
            if (total > maximum) {
                throw new CallRecordException(
                        "AUDIO_TOO_LARGE", 413, "上传内容超过大小限制", false);
            }
            output.write(buffer, 0, read);
        }
    }

    private static Instant parseInstant(String value) throws CallRecordException {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw partInvalid();
        }
    }

    private static long declaredLength(HttpExchange exchange) throws CallRecordException {
        String value = exchange.getRequestHeaders().getFirst("Content-Length");
        if (value == null || value.isBlank()) return -1;
        try {
            long length = Long.parseLong(value);
            if (length < 0) throw partInvalid();
            return length;
        } catch (NumberFormatException exception) {
            throw partInvalid();
        }
    }

    private static void writeJson(HttpExchange exchange, int status, Object value)
            throws IOException {
        byte[] body = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static CallRecordException multipartInvalid() {
        return new CallRecordException(
                "MULTIPART_INVALID", 400, "multipart 请求无效", false);
    }

    private static CallRecordException partInvalid() {
        return new CallRecordException(
                "MULTIPART_PART_INVALID", 400, "multipart 字段无效", false);
    }

    private record ExchangeRequestContext(HttpExchange exchange) implements RequestContext {
        @Override public String getCharacterEncoding() { return StandardCharsets.UTF_8.name(); }
        @Override public int getContentLength() {
            String value = exchange.getRequestHeaders().getFirst("Content-Length");
            if (value == null) return -1;
            try {
                long length = Long.parseLong(value);
                return length > Integer.MAX_VALUE ? -1 : (int) length;
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        @Override public String getContentType() {
            return exchange.getRequestHeaders().getFirst("Content-Type");
        }
        @Override public InputStream getInputStream() { return exchange.getRequestBody(); }
    }

    private static String[] rawSegments(String rawPath) {
        if (rawPath == null || !rawPath.startsWith("/")
                || rawPath.length() == 1 || rawPath.endsWith("//")) {
            return null;
        }
        String[] segments = rawPath.split("/", -1);
        for (int index = 1; index < segments.length - 1; index++) {
            if (segments[index].isEmpty()) return null;
        }
        return segments;
    }

    private static void writeError(HttpExchange exchange, CallRecordException exception)
            throws IOException {
        byte[] body = GSON.toJson(Map.of(
                "code", exception.code(),
                "message", exception.getMessage(),
                "traceId", UUID.randomUUID().toString(),
                "context", Map.of())).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(exception.httpStatus(), body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    static String decodeRawPathSegment(String raw) throws CallRecordException {
        if (raw == null || raw.isEmpty() || raw.length() > 2_048) {
            throw routePathInvalid();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (current == '%') {
                if (index + 2 >= raw.length()) throw routePathInvalid();
                int high = Character.digit(raw.charAt(index + 1), 16);
                int low = Character.digit(raw.charAt(index + 2), 16);
                if (high < 0 || low < 0) throw routePathInvalid();
                int decodedByte = (high << 4) | low;
                if (decodedByte == '/' || decodedByte == '\\' || decodedByte == 0) {
                    throw routePathInvalid();
                }
                bytes.write(decodedByte);
                index += 2;
                continue;
            }
            if (current > 0x7f || current == '\\' || current == 0) {
                throw routePathInvalid();
            }
            bytes.write(current);
        }

        final String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw routePathInvalid();
        }
        String lower = decoded.toLowerCase(Locale.ROOT);
        if (decoded.isBlank() || decoded.equals(".") || decoded.equals("..")
                || decoded.indexOf('/') >= 0 || decoded.indexOf('\\') >= 0
                || decoded.indexOf(0) >= 0 || lower.contains("%2f")
                || lower.contains("%5c") || lower.contains("%00")) {
            throw routePathInvalid();
        }
        return decoded;
    }

    private static CallRecordException routePathInvalid() {
        return new CallRecordException(
                "ROUTE_PATH_INVALID", 400, "请求路径无效", false);
    }

    @FunctionalInterface
    public interface ViewerActorResolver {
        String requireActor(String viewerAuthToken) throws Exception;
    }
}
