package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/** Atomic, sanitized idempotency state for one configured authorization enterprise. */
public final class WeComChatDataPublicKeyRegistrationStore {
    private static final int MAX_STATE_BYTES = 4096;
    private static final Set<String> FIELDS = Set.of(
            "authCorpId", "publicKeyVersion", "publicKeySha256", "registeredAt");
    private final Config config;
    private final Clock clock;

    public WeComChatDataPublicKeyRegistrationStore(Config config) {
        this(config, Clock.systemUTC());
    }

    private WeComChatDataPublicKeyRegistrationStore(Config config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    static WeComChatDataPublicKeyRegistrationStore forTests(Config config, Clock clock) {
        return new WeComChatDataPublicKeyRegistrationStore(config, clock);
    }

    public synchronized boolean isRegistered(String authCorpId, int publicKeyVersion,
                                             String publicKeySha256) throws WeComChatDataException {
        validateKey(authCorpId, publicKeyVersion, publicKeySha256);
        Path path = config.wecomChatDataPublicKeyRegistrationFile();
        if (!Files.exists(path)) return false;
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_STATE_BYTES) {
                throw new IOException("registration state unavailable");
            }
            JsonObject json = JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!json.keySet().equals(FIELDS)
                    || !json.get("authCorpId").isJsonPrimitive()
                    || !json.get("publicKeyVersion").isJsonPrimitive()
                    || !json.get("publicKeySha256").isJsonPrimitive()
                    || !json.get("registeredAt").isJsonPrimitive()) {
                throw new IOException("registration state invalid");
            }
            String storedCorpId = json.get("authCorpId").getAsString();
            int storedVersion = json.get("publicKeyVersion").getAsInt();
            String storedSha256 = json.get("publicKeySha256").getAsString();
            Instant.parse(json.get("registeredAt").getAsString());
            validateKey(storedCorpId, storedVersion, storedSha256);
            return authCorpId.equals(storedCorpId)
                    && publicKeyVersion == storedVersion
                    && publicKeySha256.equals(storedSha256);
        } catch (Exception exception) {
            throw stateFailed(exception);
        }
    }

    public synchronized void markRegistered(String authCorpId, int publicKeyVersion,
                                            String publicKeySha256) throws WeComChatDataException {
        validateKey(authCorpId, publicKeyVersion, publicKeySha256);
        try {
            JsonObject json = new JsonObject();
            json.addProperty("authCorpId", authCorpId);
            json.addProperty("publicKeyVersion", publicKeyVersion);
            json.addProperty("publicKeySha256", publicKeySha256);
            json.addProperty("registeredAt", clock.instant().toString());
            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_STATE_BYTES) throw new IOException("registration state too large");
            publish(config.wecomChatDataPublicKeyRegistrationFile(), bytes);
        } catch (Exception exception) {
            throw stateFailed(exception);
        }
    }

    private static void validateKey(String authCorpId, int version, String sha256)
            throws WeComChatDataException {
        if (authCorpId == null || authCorpId.isBlank() || authCorpId.length() > 128
                || version < 1 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw stateFailed(null);
        }
    }

    private static void publish(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("registration state parent missing");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("atomic registration state replace unsupported", exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static WeComChatDataException stateFailed(Throwable cause) {
        return new WeComChatDataException("WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_STATE_FAILED", 500,
                "企业微信会话存档公钥注册状态不可用", cause);
    }
}
