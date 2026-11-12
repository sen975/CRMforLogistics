package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.config.FunAsrConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

@Service
public class FunAsrClient {
    private static final int MAX_TEXT_CHARACTERS = 1_000_000;
    private static final int MAX_SEGMENT_TEXT_CHARACTERS = 100_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final URI endpoint;
    private final String configuredModel;
    private final Duration requestTimeout;
    private final int maxSegments;
    private final int maxDurationSeconds;
    private final long maxResponseBytes;

    public FunAsrClient(FunAsrConfig config) {
        Objects.requireNonNull(config, "config");
        this.endpoint = URI.create(config.baseUrl() + "/v1/audio/transcriptions");
        this.configuredModel = config.model();
        this.requestTimeout = config.requestTimeout();
        this.maxSegments = 20_000;
        this.maxDurationSeconds = 7_200;
        this.maxResponseBytes = config.maxResponseBytes();
        this.client = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public CallRecordStateMachine.TranscriptionResult transcribe(
            Path audioPath, String model, double audioDurationSeconds) throws CallRecordException {
        requireRequest(audioPath, model, audioDurationSeconds);

        String boundary = "funasr-" + UUID.randomUUID().toString().replace("-", "");
        try {
            HttpRequest.BodyPublisher filePublisher = HttpRequest.BodyPublishers.ofFile(audioPath);
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
                    filePublisher,
                    ofText("\r\n--" + boundary + "--\r\n"));

            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(body)
                    .build();

            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CallRecordException(
                        "FUNASR_INTERRUPTED", 503,
                        "FunASR request was interrupted", true, e);
            } catch (IOException e) {
                throw new CallRecordException(
                        "FUNASR_UNAVAILABLE", 503,
                        "FunASR is unavailable", true, e);
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
                return parseResponse(readBounded(stream), audioDurationSeconds);
            } catch (CallRecordException e) {
                throw e;
            } catch (IOException e) {
                throw new CallRecordException(
                        "FUNASR_UNAVAILABLE", 503,
                        "FunASR response could not be read", true, e);
            }
        } catch (CallRecordException e) {
            throw e;
        } catch (FileNotFoundException e) {
            throw new CallRecordException(
                    "CALL_AUDIO_NOT_FOUND", 404,
                    "Call audio does not exist", false, e);
        }
    }

    byte[] readBounded(InputStream stream) throws IOException, CallRecordException {
        Objects.requireNonNull(stream, "stream");
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maxResponseBytes, 8192));
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            if (read == 0) continue;
            if (total > maxResponseBytes - read) {
                throw new CallRecordException("FUNASR_RESPONSE_TOO_LARGE", 502,
                        "FunASR response exceeds the configured size limit", false);
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    CallRecordStateMachine.TranscriptionResult parseResponse(
            byte[] bytes, double audioDurationSeconds) throws CallRecordException {
        final JsonNode object;
        try {
            object = MAPPER.readTree(bytes);
        } catch (IOException exception) {
            throw invalidResponse("FunASR response is not valid JSON", exception);
        }

        String text = requiredString(object, "text", MAX_TEXT_CHARACTERS);
        String responseModel = object.has("model")
                ? requiredString(object, "model", 128) : configuredModel;
        requireAudioDuration(audioDurationSeconds);
        JsonNode segmentsElement = object.get("segments");
        if (segmentsElement == null || !segmentsElement.isArray()) {
            throw invalidResponse("FunASR segments are missing", null);
        }
        if (segmentsElement.size() > maxSegments) {
            throw invalidResponse("FunASR segment count is invalid", null);
        }
        List<CallRecordStateMachine.TranscriptSegment> segments = new ArrayList<>(segmentsElement.size());
        double previousStart = -1;
        for (JsonNode value : segmentsElement) {
            if (value == null || !value.isObject()) {
                throw invalidResponse("FunASR segment is invalid", null);
            }
            String segmentText = requiredString(value, "text", MAX_SEGMENT_TEXT_CHARACTERS);
            double start = requiredNumber(value, "start");
            double end = requiredNumber(value, "end");
            if (!Double.isFinite(start) || !Double.isFinite(end)
                    || start < 0 || end < start || end > audioDurationSeconds + 1.0
                    || start < previousStart) {
                throw invalidResponse("FunASR segment timing is invalid", null);
            }
            segments.add(new CallRecordStateMachine.TranscriptSegment(start, end, segmentText));
            previousStart = start;
        }
        return new CallRecordStateMachine.TranscriptionResult(
                responseModel, audioDurationSeconds, text,
                List.copyOf(segments), Instant.now());
    }

    private static String requiredString(JsonNode object, String name, int maximum) throws CallRecordException {
        JsonNode element = object.get(name);
        if (element == null || !element.isTextual()) {
            throw invalidResponse("FunASR field is missing", null);
        }
        String value = element.asText();
        if (value.isBlank() || value.length() > maximum || value.indexOf('\0') >= 0) {
            throw invalidResponse("FunASR text field is invalid", null);
        }
        return value;
    }

    private static double requiredNumber(JsonNode object, String name) throws CallRecordException {
        JsonNode element = object.get(name);
        if (element == null || !element.isNumber()) {
            throw invalidResponse("FunASR numeric field is invalid", null);
        }
        return element.asDouble();
    }

    private void requireRequest(Path audioPath, String model,
                                double audioDurationSeconds) throws CallRecordException {
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
        requireAudioDuration(audioDurationSeconds);
    }

    private void requireAudioDuration(double audioDurationSeconds) throws CallRecordException {
        if (!Double.isFinite(audioDurationSeconds)
                || audioDurationSeconds <= 0
                || audioDurationSeconds > maxDurationSeconds) {
            throw new CallRecordException(
                    "FUNASR_REQUEST_INVALID", 500,
                    "Call audio duration is invalid", false);
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
