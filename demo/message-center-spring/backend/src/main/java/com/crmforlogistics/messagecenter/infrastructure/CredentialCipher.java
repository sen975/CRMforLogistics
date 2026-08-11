package com.crmforlogistics.messagecenter.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SecretKeySpec masterKey;
    private final SecureRandom secureRandom;

    private CredentialCipher(byte[] keyBytes, SecureRandom secureRandom) {
        this.masterKey = new SecretKeySpec(keyBytes, "AES");
        this.secureRandom = secureRandom;
    }

    public static CredentialCipher fromBase64Key(String encodedKey) {
        Objects.requireNonNull(encodedKey, "encodedKey");
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Credential master key must be valid Base64", e);
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
        Map<String, String> validated = validateSecrets(secrets);
        byte[] plaintext = null;
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] ciphertext = null;
        try {
            plaintext = MAPPER.writeValueAsBytes(validated);
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            ciphertext = cipher.doFinal(plaintext);
            var envelope = Map.of(
                    "algorithm", ALGORITHM,
                    "keyVersion", KEY_VERSION,
                    "nonce", Base64.getEncoder().encodeToString(nonce),
                    "ciphertext", Base64.getEncoder().encodeToString(ciphertext));
            return MAPPER.writeValueAsString(envelope);
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new CredentialEncryptionException("CREDENTIAL_ENCRYPTION_FAILED", e);
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
            if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
            validated.replaceAll((k, v) -> "");
            validated.clear();
        }
    }

    public Map<String, String> decrypt(String encryptedConfig) throws CredentialDecryptionException {
        byte[] nonce = null;
        byte[] ciphertext = null;
        byte[] plaintext = null;
        try {
            JsonNode envelope = MAPPER.readTree(encryptedConfig);
            if (!ALGORITHM.equals(envelope.path("algorithm").asText())
                    || envelope.path("keyVersion").asInt() != KEY_VERSION) {
                throw new IllegalArgumentException("Unsupported credential envelope");
            }
            nonce = Base64.getDecoder().decode(envelope.path("nonce").asText());
            ciphertext = Base64.getDecoder().decode(envelope.path("ciphertext").asText());
            if (nonce.length != NONCE_BYTES || ciphertext.length < TAG_BITS / Byte.SIZE) {
                throw new IllegalArgumentException("Invalid envelope dimensions");
            }
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            plaintext = cipher.doFinal(ciphertext);
            JsonNode secretsNode = MAPPER.readTree(plaintext);
            Map<String, String> secrets = new LinkedHashMap<>();
            secretsNode.fields().forEachRemaining(e -> secrets.put(e.getKey(), e.getValue().asText()));
            return secrets;
        } catch (AEADBadTagException e) {
            throw decryptionFailure();
        } catch (GeneralSecurityException | RuntimeException | java.io.IOException e) {
            throw decryptionFailure();
        } finally {
            if (nonce != null) Arrays.fill(nonce, (byte) 0);
            if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private Map<String, String> validateSecrets(Map<String, String> secrets) {
        Objects.requireNonNull(secrets, "secrets");
        Map<String, String> copy = new LinkedHashMap<>();
        secrets.forEach((k, v) -> {
            if (k == null || k.isBlank() || v == null)
                throw new IllegalArgumentException("Credential keys must be non-blank and values must be non-null");
            copy.put(k, v);
        });
        return copy;
    }

    private static CredentialDecryptionException decryptionFailure() {
        return new CredentialDecryptionException("CREDENTIAL_DECRYPTION_FAILED");
    }

    public static final class CredentialEncryptionException extends Exception {
        private final String code;
        CredentialEncryptionException(String code, Throwable cause) {
            super("Unable to encrypt channel credentials", cause);
            this.code = code;
        }
        public String code() { return code; }
    }

    public static final class CredentialDecryptionException extends Exception {
        private final String code;
        CredentialDecryptionException(String code) {
            super("Unable to decrypt channel credentials");
            this.code = code;
        }
        public String code() { return code; }
    }
}
