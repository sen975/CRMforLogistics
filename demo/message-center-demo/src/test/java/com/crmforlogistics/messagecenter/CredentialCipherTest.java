package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialCipherTest {
    @Test
    void encryptsWithRandomNonceAndDecryptsOnlyWithMasterKey() throws Exception {
        CredentialCipher cipher = CredentialCipher.fromBase64Key(testKey("primary-master-key"));

        String first = cipher.encrypt(Map.of("accessKeySecret", "secret-value"));
        String second = cipher.encrypt(Map.of("accessKeySecret", "secret-value"));

        assertNotEquals(first, second);
        assertEquals("secret-value", cipher.decrypt(first).get("accessKeySecret"));
        assertFalse(first.contains("secret-value"));

        JsonObject envelope = JsonParser.parseString(first).getAsJsonObject();
        assertEquals("AES-256-GCM", envelope.get("algorithm").getAsString());
        assertEquals(1, envelope.get("keyVersion").getAsInt());
        assertEquals(12, Base64.getDecoder().decode(envelope.get("nonce").getAsString()).length);
    }

    @Test
    void rejectsWrongKeyWithoutLeakingCiphertextOrPlaintext() throws Exception {
        CredentialCipher encryptor = CredentialCipher.fromBase64Key(testKey("primary-master-key"));
        CredentialCipher decryptor = CredentialCipher.fromBase64Key(testKey("secondary-master-key"));
        String encrypted = encryptor.encrypt(Map.of("accessKeySecret", "never-leak-this"));

        CredentialCipher.CredentialDecryptionException failure = assertThrows(
                CredentialCipher.CredentialDecryptionException.class,
                () -> decryptor.decrypt(encrypted));

        assertEquals("CREDENTIAL_DECRYPTION_FAILED", failure.code());
        assertFalse(failure.getMessage().contains(encrypted));
        assertFalse(failure.getMessage().contains("never-leak-this"));
    }

    @Test
    void requiresA256BitMasterKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalArgumentException.class, () -> CredentialCipher.fromBase64Key(shortKey));
    }

    private static String testKey(String seed) {
        byte[] input = seed.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = input[index % input.length];
        }
        return Base64.getEncoder().encodeToString(key);
    }
}
