package com.crmforlogistics.messagecenter.callrecord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.crmforlogistics.messagecenter.Config;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class FunAsrClient {
    private static final int MAX_TEXT_CHARACTERS = 1_000_000;
    private static final int MAX_SEGMENT_TEXT_CHARACTERS = 100_000;
    private static final int READ_BUFFER_BYTES = 16 * 1_024;

    private final HttpClient client;
    private final URI endpoint;
    private final String configuredModel;
    private final Duration requestTimeout;
    private final long maxResponseBytes;
    private final int maxSegments;
    private final int maxDurationSeconds;

    public FunAsrClient(Config config, HttpClient client) {
        Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
        this.endpoint = config.funAsrBaseUri().resolve("/v1/audio/transcriptions");
        this.configuredModel = config.funAsrModel();
        this.requestTimeout = config.funAsrRequestTimeout();
        this.maxResponseBytes = config.callRecordMaxResponseBytes();
        this.maxSegments = config.callRecordMaxSegments();
        this.maxDurationSeconds = config.callRecordMaxDurationSeconds();
    }

    public TranscriptionResult transcribe(Path audioPath, String model)
            throws CallRecordException {
        requireRequest(audioPath, model);
        String boundary = "funasr-" + UUID.randomUUID().toString().replace("-", "");
        final HttpRequest.BodyPublisher file;
        try {
            file = HttpRequest.BodyPublishers.ofFile(audioPath);
        } catch (FileNotFoundException exception) {
            throw new CallRecordException(
                    "CALL_AUDIO_NOT_FOUND", 404,
                    "Call audio does not exist", false, exception);
        }
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                ofText("--" + boundary
                        + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"
                        + model + "\r\n"),
                ofText("--" + boundary
                        + "\r\nContent-Disposition: form-data; name=\"response_format\""
                        + "\r\n\r\nverbose_json\r\n"),
                ofText("--" + boundary
                        + "\r\nContent-Disposition: form-data; name=\"file\"; "
                        + "filename=\"recording.mp3\"\r\nContent-Type: audio/mpeg\r\n\r\n"),
                file,
                ofText("\r\n--" + boundary + "--\r\n"));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body)
                .build();

        final HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException exception) {
            throw new CallRecordException(
                    "FUNASR_TIMEOUT", 504, "FunASR request timed out", true, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CallRecordException(
                    "FUNASR_INTERRUPTED", 503,
                    "FunASR request was interrupted", true, exception);
        } catch (IOException exception) {
            throw new CallRecordException(
                    "FUNASR_UNAVAILABLE", 503,
                    "FunASR is unavailable", true, exception);
        }

        try (InputStream stream = response.body()) {
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                if (status == 408 || status == 429 || status >= 500) {
                    throw new CallRecordException(
                            "FUNASR_UNAVAILABLE", 503,
                            "FunASR is unavailable", true);
                }
                throw new CallRecordException(
                        "FUNASR_REJECTED", 502,
                        "FunASR rejected the transcription request", false);
            }
            return parseResponse(readBounded(stream));
        } catch (CallRecordException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new CallRecordException(
                    "FUNASR_UNAVAILABLE", 503,
                    "FunASR response could not be read", true, exception);
        }
    }

    private byte[] readBounded(InputStream stream) throws IOException, CallRecordException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min(maxResponseBytes, 64 * 1_024L));
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        long total = 0;
        while (true) {
            int read = stream.read(buffer);
            if (read < 0) break;
            if (read == 0) continue;
            if (total > maxResponseBytes - read) {
                throw invalidResponse("FunASR response exceeds its configured limit", null);
            }
            output.write(buffer, 0, read);
            total += read;
        }
        if (total == 0) throw invalidResponse("FunASR response is empty", null);
        return output.toByteArray();
    }

    private TranscriptionResult parseResponse(byte[] bytes) throws CallRecordException {
        final JsonObject object;
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("root is not an object");
            object = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw invalidResponse("FunASR response is not valid JSON", exception);
        }

        String text = requiredString(object, "text", MAX_TEXT_CHARACTERS);
        String model = requiredString(object, "model", 128);
        double duration = requiredNumber(object, "duration");
        if (!Double.isFinite(duration) || duration <= 0 || duration > maxDurationSeconds) {
            throw invalidResponse("FunASR duration is invalid", null);
        }
        JsonElement segmentsElement = object.get("segments");
        if (segmentsElement == null || !segmentsElement.isJsonArray()) {
            throw invalidResponse("FunASR segments are missing", null);
        }
        JsonArray values = segmentsElement.getAsJsonArray();
        if (values.isEmpty() || values.size() > maxSegments) {
            throw invalidResponse("FunASR segment count is invalid", null);
        }
        List<TranscriptSegment> segments = new ArrayList<>(values.size());
        double previousStart = -1;
        for (JsonElement value : values) {
            if (value == null || !value.isJsonObject()) {
                throw invalidResponse("FunASR segment is invalid", null);
            }
            JsonObject segment = value.getAsJsonObject();
            String segmentText = requiredString(
                    segment, "text", MAX_SEGMENT_TEXT_CHARACTERS);
            double start = requiredNumber(segment, "start");
            double end = requiredNumber(segment, "end");
            if (!Double.isFinite(start) || !Double.isFinite(end)
                    || start < 0 || end < start || end > duration + 1.0
                    || start < previousStart) {
                throw invalidResponse("FunASR segment timing is invalid", null);
            }
            segments.add(new TranscriptSegment(start, end, segmentText));
            previousStart = start;
        }
        return new TranscriptionResult(
                model, duration, text, segments, Instant.now());
    }

    private static String requiredString(JsonObject object, String name, int maximum)
            throws CallRecordException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw invalidResponse("FunASR field is missing", null);
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isString()) {
            throw invalidResponse("FunASR field type is invalid", null);
        }
        String value = primitive.getAsString();
        if (value.isBlank() || value.length() > maximum || value.indexOf('\u0000') >= 0) {
            throw invalidResponse("FunASR text field is invalid", null);
        }
        return value;
    }

    private static double requiredNumber(JsonObject object, String name)
            throws CallRecordException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw invalidResponse("FunASR numeric field is invalid", null);
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException exception) {
            throw invalidResponse("FunASR numeric field is invalid", exception);
        }
    }

    private void requireRequest(Path audioPath, String model) throws CallRecordException {
        if (audioPath == null || Files.isSymbolicLink(audioPath)
                || !Files.isRegularFile(audioPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new CallRecordException(
                    "CALL_AUDIO_NOT_FOUND", 404,
                    "Call audio does not exist", false);
        }
        if (!Objects.equals(configuredModel, model)) {
            throw new CallRecordException(
                    "FUNASR_REQUEST_INVALID", 500,
                    "FunASR model does not match configuration", false);
        }
    }

    private static HttpRequest.BodyPublisher ofText(String value) {
        return HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8);
    }

    private static CallRecordException invalidResponse(String message, Throwable cause) {
        return new CallRecordException(
                "FUNASR_INVALID_RESPONSE", 502, message, false, cause);
    }
}
