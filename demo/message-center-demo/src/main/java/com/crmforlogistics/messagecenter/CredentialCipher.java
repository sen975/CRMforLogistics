package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class CredentialCipher {
    private static final String ALGORITHM = "AES-256-GCM";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_VERSION = 1;
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD = (ALGORITHM + ":" + KEY_VERSION)
            .getBytes(StandardCharsets.US_ASCII);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final SecretKeySpec masterKey;
    private final SecureRandom secureRandom;

    private CredentialCipher(byte[] keyBytes, SecureRandom secureRandom) {
        this.masterKey = new SecretKeySpec(keyBytes, "AES");
        this.secureRandom = secureRandom;
    }

    public static CredentialCipher fromBase64Key(String encodedKey) {
        Objects.requireNonNull(encodedKey, "encodedKey");
        final byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Credential master key must be valid Base64", exception);
        }
        try {
            if (keyBytes.length != KEY_BYTES) {
                throw new IllegalArgumentException("Credential master key must contain exactly 32 bytes");
            }
            return new CredentialCipher(keyBytes, new SecureRandom());
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    public String encrypt(Map<String, String> secrets) throws CredentialEncryptionException {
        Map<String, String> validatedSecrets = validateSecrets(secrets);
        byte[] plaintext = GSON.toJson(validatedSecrets).getBytes(StandardCharsets.UTF_8);
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] ciphertext = null;
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            ciphertext = cipher.doFinal(plaintext);
            return GSON.toJson(new EncryptedConfig(
                    ALGORITHM,
                    KEY_VERSION,
                    Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(ciphertext)));
        } catch (GeneralSecurityException exception) {
            throw new CredentialEncryptionException("CREDENTIAL_ENCRYPTION_FAILED", exception);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
            if (ciphertext != null) {
                Arrays.fill(ciphertext, (byte) 0);
            }
            validatedSecrets.replaceAll((key, value) -> "");
            validatedSecrets.clear();
        }
    }

    public Map<String, String> decrypt(String encryptedConfig) throws CredentialDecryptionException {
        byte[] nonce = null;
        byte[] ciphertext = null;
        byte[] plaintext = null;
        try {
            EncryptedConfig envelope = parseEnvelope(encryptedConfig);
            nonce = Base64.getDecoder().decode(envelope.nonce());
            ciphertext = Base64.getDecoder().decode(envelope.ciphertext());
            if (nonce.length != NONCE_BYTES || ciphertext.length < TAG_BITS / Byte.SIZE) {
                throw new IllegalArgumentException("Invalid credential envelope dimensions");
            }

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            plaintext = cipher.doFinal(ciphertext);
            return parseSecrets(plaintext);
        } catch (AEADBadTagException exception) {
            throw decryptionFailure(exception);
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw decryptionFailure(exception);
        } finally {
            if (nonce != null) {
                Arrays.fill(nonce, (byte) 0);
            }
            if (ciphertext != null) {
                Arrays.fill(ciphertext, (byte) 0);
            }
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    static void requireEnvelope(String encryptedConfig) {
        byte[] nonce = null;
        byte[] ciphertext = null;
        try {
            EncryptedConfig envelope = parseEnvelope(encryptedConfig);
            nonce = Base64.getDecoder().decode(envelope.nonce());
            ciphertext = Base64.getDecoder().decode(envelope.ciphertext());
            if (nonce.length != NONCE_BYTES || ciphertext.length < TAG_BITS / Byte.SIZE) {
                throw new IllegalArgumentException("Invalid credential envelope dimensions");
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Encrypted channel configuration must use an AES-256-GCM envelope");
        } finally {
            if (nonce != null) {
                Arrays.fill(nonce, (byte) 0);
            }
            if (ciphertext != null) {
                Arrays.fill(ciphertext, (byte) 0);
            }
        }
    }

    private static Map<String, String> validateSecrets(Map<String, String> secrets) {
        Objects.requireNonNull(secrets, "secrets");
        Map<String, String> copy = new LinkedHashMap<>();
        secrets.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                throw new IllegalArgumentException("Credential names must be non-blank and values must be non-null");
            }
            copy.put(key, value);
        });
        return copy;
    }

    private static EncryptedConfig parseEnvelope(String encryptedConfig) {
        if (encryptedConfig == null || encryptedConfig.isBlank()) {
            throw new IllegalArgumentException("Credential envelope is missing");
        }
        JsonElement parsed = JsonParser.parseString(encryptedConfig);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Credential envelope is unsupported");
        }
        JsonObject object = parsed.getAsJsonObject();
        if (object.size() != 4
                || !stringField(object, "algorithm")
                || !numberField(object, "keyVersion")
                || !stringField(object, "nonce")
                || !stringField(object, "ciphertext")) {
            throw new IllegalArgumentException("Credential envelope is unsupported");
        }
        EncryptedConfig envelope = new EncryptedConfig(
                object.get("algorithm").getAsString(),
                object.get("keyVersion").getAsInt(),
                object.get("nonce").getAsString(),
                object.get("ciphertext").getAsString());
        if (!ALGORITHM.equals(envelope.algorithm()) || envelope.keyVersion() != KEY_VERSION) {
            throw new IllegalArgumentException("Credential envelope is unsupported");
        }
        return envelope;
    }

    private static boolean stringField(JsonObject object, String name) {
        return object.has(name)
                && object.get(name).isJsonPrimitive()
                && object.get(name).getAsJsonPrimitive().isString();
    }

    private static boolean numberField(JsonObject object, String name) {
        return object.has(name)
                && object.get(name).isJsonPrimitive()
                && object.get(name).getAsJsonPrimitive().isNumber();
    }

    private static Map<String, String> parseSecrets(byte[] plaintext) {
        JsonElement parsed = JsonParser.parseReader(new InputStreamReader(
                new ByteArrayInputStream(plaintext), StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Credential payload is not an object");
        }
        Map<String, String> secrets = new LinkedHashMap<>();
        JsonObject object = parsed.getAsJsonObject();
        object.entrySet().forEach(entry -> {
            if (!entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Credential payload contains an invalid value");
            }
            secrets.put(entry.getKey(), entry.getValue().getAsString());
        });
        return secrets;
    }

    private static CredentialDecryptionException decryptionFailure(Throwable cause) {
        return new CredentialDecryptionException("CREDENTIAL_DECRYPTION_FAILED", cause);
    }

    record EncryptedConfig(String algorithm, int keyVersion, String nonce, String ciphertext) {}

    public static final class CredentialEncryptionException extends Exception {
        private final String code;

        CredentialEncryptionException(String code, Throwable cause) {
            super("Unable to encrypt channel credentials", cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static final class CredentialDecryptionException extends Exception {
        private final String code;

        CredentialDecryptionException(String code, Throwable cause) {
            super("Unable to decrypt channel credentials", cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
