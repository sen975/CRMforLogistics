package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The single owner of delegated WeCom authorization installation records. */
public final class WeComAuthorizationStore {
    private static final int MAX_LINE_BYTES = 16 * 1024;
    private static final int MAX_INSTALLATION_ID = 128;
    private static final int MAX_SUITE_ID = 128;
    private static final int MAX_CORP_ID = 128;
    private static final int MAX_AGENT_ID = 32;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (value, type, context) ->
                    new com.google.gson.JsonPrimitive(value.toString()))
            .registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>) (json, type, context) ->
                    Instant.parse(json.getAsString()))
            .create();

    private final Path file;
    private final CredentialCipher cipher;
    private final Clock clock;
    private final Object lock = new Object();

    public WeComAuthorizationStore(Config config) throws IOException {
        this(config, CredentialCipher.fromBase64Key(config.readSecret(config.credentialMasterKeyFile())),
                Clock.systemUTC());
    }

    WeComAuthorizationStore(Config config, CredentialCipher cipher, Clock clock) {
        this.file = config.wecomAuthorizationInstallationsFile();
        this.cipher = cipher;
        this.clock = clock;
    }

    static WeComAuthorizationStore forTests(Path file, CredentialCipher cipher, Clock clock) {
        return new WeComAuthorizationStore(file, cipher, clock);
    }

    private WeComAuthorizationStore(Path file, CredentialCipher cipher, Clock clock) {
        this.file = file;
        this.cipher = cipher;
        this.clock = clock;
    }

    public Optional<Installation> find(String suiteId, String authCorpId) throws WeComAuthorizationException {
        validateKey(suiteId, authCorpId);
        synchronized (lock) {
            return readSnapshot().stream()
                    .filter(item -> item.suiteId().equals(suiteId) && item.authCorpId().equals(authCorpId))
                    .findFirst();
        }
    }

    public Installation requireActive(String suiteId, String authCorpId) throws WeComAuthorizationException {
        Installation installation = find(suiteId, authCorpId).orElseThrow(() ->
                error("WECOM_INSTALLATION_NOT_FOUND", 403, "未找到企业微信授权安装记录"));
        if (installation.authStatus() != AuthStatus.ACTIVE) {
            throw error("WECOM_INSTALLATION_INACTIVE", 403, "企业微信授权安装已撤销或失效");
        }
        if (installation.agentId().isBlank() || installation.permanentCodeEncrypted().isBlank()) {
            throw error("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 500, "企业微信授权凭据不可用");
        }
        return installation;
    }

    public ResolvedInstallation resolveActive(String suiteId, String authCorpId)
            throws WeComAuthorizationException {
        Installation installation = requireActive(suiteId, authCorpId);
        return decryptInstallation(installation);
    }

    ResolvedInstallation resolveRefreshable(String suiteId, String authCorpId)
            throws WeComAuthorizationException {
        Installation installation = find(suiteId, authCorpId).orElseThrow(() ->
                error("WECOM_INSTALLATION_NOT_FOUND", 403, "未找到企业微信授权安装记录"));
        if (installation.authStatus() == AuthStatus.REVOKED) {
            throw error("WECOM_INSTALLATION_INACTIVE", 403, "企业微信授权安装已撤销或失效");
        }
        return decryptInstallation(installation);
    }

    private ResolvedInstallation decryptInstallation(Installation installation)
            throws WeComAuthorizationException {
        try {
            Map<String, String> secrets = cipher.decrypt(installation.permanentCodeEncrypted());
            String permanentCode = secrets.getOrDefault("permanentCode", "");
            if (permanentCode.isBlank() || permanentCode.length() > 512 || secrets.size() != 1) {
                throw new IllegalArgumentException("permanent code payload is invalid");
            }
            return new ResolvedInstallation(installation, permanentCode);
        } catch (CredentialCipher.CredentialDecryptionException | RuntimeException exception) {
            throw error("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 500, "企业微信授权凭据不可用", exception);
        }
    }

    public Installation upsertActive(String suiteId, String authCorpId, String agentId, String permanentCode)
            throws WeComAuthorizationException {
        validateKey(suiteId, authCorpId);
        requireText(agentId, "agentId", MAX_AGENT_ID);
        requireText(permanentCode, "permanentCode", 512);
        final String encrypted;
        try {
            encrypted = cipher.encrypt(Map.of("permanentCode", permanentCode));
        } catch (CredentialCipher.CredentialEncryptionException | RuntimeException exception) {
            throw error("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 500, "企业微信授权凭据不可用", exception);
        }
        synchronized (lock) {
            List<Installation> snapshot = readSnapshot();
            Instant now = clock.instant();
            Installation existing = snapshot.stream()
                    .filter(item -> item.suiteId().equals(suiteId) && item.authCorpId().equals(authCorpId))
                    .findFirst().orElse(null);
            String installationId = existing == null ? UUID.randomUUID().toString().replace("-", "")
                    : existing.installationId();
            long version = existing == null ? 1 : Math.addExact(existing.version(), 1);
            Instant authorizedAt = existing == null ? now : existing.authorizedAt();
            Instant lastSuiteTicketAt = existing == null ? null : existing.lastSuiteTicketAt();
            Installation updated = new Installation(installationId, suiteId, authCorpId, agentId, encrypted,
                    AuthStatus.ACTIVE, authorizedAt, now, lastSuiteTicketAt, version,
                    existing == null ? null : existing.lastAuthorizationEventId(),
                    existing == null ? null : existing.lastAuthorizationEventAt());
            List<Installation> next = replace(snapshot, updated);
            writeSnapshot(next);
            return updated;
        }
    }

    public Installation updateStatus(String suiteId, String authCorpId, AuthStatus status)
            throws WeComAuthorizationException {
        validateKey(suiteId, authCorpId);
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        synchronized (lock) {
            List<Installation> snapshot = readSnapshot();
            Installation current = snapshot.stream()
                    .filter(item -> item.suiteId().equals(suiteId) && item.authCorpId().equals(authCorpId))
                    .findFirst().orElseThrow(() -> error("WECOM_INSTALLATION_NOT_FOUND", 403,
                            "未找到企业微信授权安装记录"));
            Installation updated = new Installation(current.installationId(), current.suiteId(), current.authCorpId(),
                    current.agentId(), current.permanentCodeEncrypted(), status, current.authorizedAt(),
                    clock.instant(), current.lastSuiteTicketAt(), Math.addExact(current.version(), 1),
                    current.lastAuthorizationEventId(), current.lastAuthorizationEventAt());
            writeSnapshot(replace(snapshot, updated));
            return updated;
        }
    }

    public Installation recordSuiteTicket(String suiteId, String authCorpId, Instant receivedAt)
            throws WeComAuthorizationException {
        validateKey(suiteId, authCorpId);
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt is required");
        }
        synchronized (lock) {
            List<Installation> snapshot = readSnapshot();
            Installation current = snapshot.stream()
                    .filter(item -> item.suiteId().equals(suiteId) && item.authCorpId().equals(authCorpId))
                    .findFirst().orElseThrow(() -> error("WECOM_INSTALLATION_NOT_FOUND", 403,
                            "未找到企业微信授权安装记录"));
            Installation updated = new Installation(current.installationId(), current.suiteId(), current.authCorpId(),
                    current.agentId(), current.permanentCodeEncrypted(), current.authStatus(), current.authorizedAt(),
                    clock.instant(), receivedAt, Math.addExact(current.version(), 1),
                    current.lastAuthorizationEventId(), current.lastAuthorizationEventAt());
            writeSnapshot(replace(snapshot, updated));
            return updated;
        }
    }

    public MutationResult upsertActiveForEvent(String suiteId, String authCorpId,
                                               String agentId, String permanentCode,
                                               String eventId, Instant eventAt)
            throws WeComAuthorizationException {
        validateKey(suiteId, authCorpId);
        requireText(agentId, "agentId", MAX_AGENT_ID);
        requireText(permanentCode, "permanentCode", 512);
        validateEvent(eventId, eventAt);
        synchronized (lock) {
            List<Installation> snapshot = readSnapshot();
            Installation existing = findIn(snapshot, suiteId, authCorpId);
            MutationResult replay = replayOrRejectStale(existing, eventId, eventAt);
            if (replay != null) return replay;
            final String encrypted;
            try {
                encrypted = cipher.encrypt(Map.of("permanentCode", permanentCode));
            } catch (CredentialCipher.CredentialEncryptionException | RuntimeException exception) {
                throw error("WECOM_INSTALLATION_CREDENTIAL_UNAVAILABLE", 500,
                        "企业微信授权凭据不可用", exception);
            }
            Instant now = clock.instant();
            Installation updated = new Installation(
                    existing == null ? UUID.randomUUID().toString().replace("-", "")
                            : existing.installationId(),
                    suiteId, authCorpId, agentId, encrypted, AuthStatus.ACTIVE,
                    existing == null ? now : existing.authorizedAt(), now,
                    existing == null ? null : existing.lastSuiteTicketAt(),
                    existing == null ? 1 : Math.addExact(existing.version(), 1),
                    eventId, eventAt);
            writeSnapshot(replace(snapshot, updated));
            return new MutationResult(updated, true);
        }
    }

    public MutationResult updateStatusForEvent(String suiteId, String authCorpId,
                                               AuthStatus status, String eventId,
                                               Instant eventAt)
            throws WeComAuthorizationException {
        validateKey(suiteId, authCorpId);
        if (status == null) throw new IllegalArgumentException("status is required");
        validateEvent(eventId, eventAt);
        synchronized (lock) {
            List<Installation> snapshot = readSnapshot();
            Installation current = findIn(snapshot, suiteId, authCorpId);
            if (current == null) {
                throw error("WECOM_INSTALLATION_NOT_FOUND", 403,
                        "未找到企业微信授权安装记录");
            }
            MutationResult replay = replayOrRejectStale(current, eventId, eventAt);
            if (replay != null) return replay;
            Installation updated = new Installation(current.installationId(), current.suiteId(),
                    current.authCorpId(), current.agentId(), current.permanentCodeEncrypted(),
                    status, current.authorizedAt(), clock.instant(), current.lastSuiteTicketAt(),
                    Math.addExact(current.version(), 1), eventId, eventAt);
            writeSnapshot(replace(snapshot, updated));
            return new MutationResult(updated, true);
        }
    }

    private static Installation findIn(List<Installation> snapshot,
                                       String suiteId, String authCorpId) {
        return snapshot.stream().filter(item -> item.suiteId().equals(suiteId)
                        && item.authCorpId().equals(authCorpId))
                .findFirst().orElse(null);
    }

    private static MutationResult replayOrRejectStale(Installation current,
                                                      String eventId, Instant eventAt)
            throws WeComAuthorizationException {
        if (current == null || current.lastAuthorizationEventId() == null) return null;
        if (current.lastAuthorizationEventId().equals(eventId)) {
            return new MutationResult(current, false);
        }
        if (eventAt.isBefore(current.lastAuthorizationEventAt())) {
            throw error("WECOM_AUTHORIZATION_EVENT_STALE", 409,
                    "企业微信授权事件早于当前安装状态");
        }
        return null;
    }

    private static void validateEvent(String eventId, Instant eventAt) {
        requireText(eventId, "eventId", 128);
        if (!eventId.matches("sha256:[0-9a-f]{64}") || eventAt == null) {
            throw new IllegalArgumentException("authorization event marker is invalid");
        }
    }

    private List<Installation> readSnapshot() throws WeComAuthorizationException {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            if (Files.size(file) > 4L * 1024L * 1024L) {
                throw error("WECOM_INSTALLATION_STORE_CORRUPTED", 500, "企业微信授权安装存储不可用");
            }
            List<Installation> result = new ArrayList<>();
            Set<String> keys = new java.util.HashSet<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
                    throw error("WECOM_INSTALLATION_STORE_CORRUPTED", 500, "企业微信授权安装存储不可用");
                }
                Installation item;
                try {
                    item = GSON.fromJson(line, Installation.class);
                } catch (RuntimeException exception) {
                    throw error("WECOM_INSTALLATION_STORE_CORRUPTED", 500, "企业微信授权安装存储不可用", exception);
                }
                validateInstallation(item);
                if (!keys.add(item.suiteId() + "\u0000" + item.authCorpId())) {
                    throw error("WECOM_INSTALLATION_STORE_CORRUPTED", 500, "企业微信授权安装存储不可用");
                }
                result.add(item);
            }
            return result;
        } catch (WeComAuthorizationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw error("WECOM_INSTALLATION_STORE_CORRUPTED", 500, "企业微信授权安装存储不可用", exception);
        }
    }

    private void writeSnapshot(List<Installation> snapshot) throws WeComAuthorizationException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) {
            throw error("WECOM_INSTALLATION_STORE_WRITE_FAILED", 500, "企业微信授权安装存储写入失败");
        }
        try {
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, ".wecom-authorizations-", ".tmp");
            try {
                setOwnerOnly(temporary);
                try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    for (Installation item : snapshot) {
                        writer.write(GSON.toJson(item));
                        writer.newLine();
                    }
                    writer.flush();
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    throw error("WECOM_INSTALLATION_STORE_WRITE_FAILED", 500, "企业微信授权安装存储写入失败", unsupported);
                }
                setOwnerOnly(file);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (WeComAuthorizationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw error("WECOM_INSTALLATION_STORE_WRITE_FAILED", 500, "企业微信授权安装存储写入失败", exception);
        }
    }

    private static void setOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems still get an atomic replacement; deployment controls directory permissions.
        }
    }

    private static List<Installation> replace(List<Installation> snapshot, Installation updated) {
        List<Installation> result = new ArrayList<>(snapshot.size() + 1);
        boolean replaced = false;
        for (Installation item : snapshot) {
            if (item.suiteId().equals(updated.suiteId()) && item.authCorpId().equals(updated.authCorpId())) {
                result.add(updated);
                replaced = true;
            } else {
                result.add(item);
            }
        }
        if (!replaced) {
            result.add(updated);
        }
        return result;
    }

    private static void validateKey(String suiteId, String authCorpId) {
        requireText(suiteId, "suiteId", MAX_SUITE_ID);
        requireText(authCorpId, "authCorpId", MAX_CORP_ID);
    }

    private static void validateInstallation(Installation item) throws WeComAuthorizationException {
        if (item == null) {
            throw error("WECOM_INSTALLATION_STORE_CORRUPTED", 500, "企业微信授权安装存储不可用");
        }
        try {
            requireText(item.installationId(), "installationId", MAX_INSTALLATION_ID);
            requireText(item.suiteId(), "suiteId", MAX_SUITE_ID);
            requireText(item.authCorpId(), "authCorpId", MAX_CORP_ID);
            requireText(item.agentId(), "agentId", MAX_AGENT_ID);
            requireText(item.permanentCodeEncrypted(), "permanentCodeEncrypted", 8192);
            if (item.authStatus() == null || item.authorizedAt() == null || item.updatedAt() == null
                    || item.version() < 1) {
                throw new IllegalArgumentException("installation fields are incomplete");
            }
            if ((item.lastAuthorizationEventId() == null) != (item.lastAuthorizationEventAt() == null)) {
                throw new IllegalArgumentException("authorization event marker is incomplete");
            }
            if (item.lastAuthorizationEventId() != null) {
                validateEvent(item.lastAuthorizationEventId(), item.lastAuthorizationEventAt());
            }
            CredentialCipher.requireEnvelope(item.permanentCodeEncrypted());
        } catch (RuntimeException exception) {
            throw error("WECOM_INSTALLATION_STORE_CORRUPTED", 500, "企业微信授权安装存储不可用", exception);
        }
    }

    private static void requireText(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " is required and must not exceed " + max + " characters");
        }
    }

    private static WeComAuthorizationException error(String code, int status, String message) {
        return new WeComAuthorizationException(code, status, message);
    }

    private static WeComAuthorizationException error(String code, int status, String message, Throwable cause) {
        return new WeComAuthorizationException(code, status, message, cause);
    }

    public enum AuthStatus { ACTIVE, REVOKED, FAILED }

    public record Installation(String installationId, String suiteId, String authCorpId, String agentId,
                               String permanentCodeEncrypted, AuthStatus authStatus, Instant authorizedAt,
                               Instant updatedAt, Instant lastSuiteTicketAt, long version,
                               String lastAuthorizationEventId, Instant lastAuthorizationEventAt) {
        public Installation(String installationId, String suiteId, String authCorpId,
                            String agentId, String permanentCodeEncrypted,
                            AuthStatus authStatus, Instant authorizedAt, Instant updatedAt,
                            Instant lastSuiteTicketAt, long version) {
            this(installationId, suiteId, authCorpId, agentId, permanentCodeEncrypted,
                    authStatus, authorizedAt, updatedAt, lastSuiteTicketAt, version,
                    null, null);
        }
    }

    public record MutationResult(Installation installation, boolean applied) {}

    public record ResolvedInstallation(Installation installation, String permanentCode) {}
}
