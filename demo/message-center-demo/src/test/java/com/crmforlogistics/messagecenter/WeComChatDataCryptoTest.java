package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComChatDataCryptoTest {
    @TempDir
    Path tempDir;

    @Test
    void decryptsOfficialRsaPkcs1SecretKey() throws Exception {
        KeyPair keyPair = keyPair(2048);
        Path privateKey = writePrivateKey(keyPair, "private-key.pem");
        WeComChatDataCrypto crypto = new WeComChatDataCrypto(new Config(Map.of(
                "WECOM_CHATDATA_PRIVATE_KEY_FILE", privateKey.toString(),
                "WECOM_CHATDATA_PUBLIC_KEY_VERSION", "7"
        )));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.getPublic());
        String encrypted = Base64.getEncoder().encodeToString(
                cipher.doFinal("viewer-secret-key".getBytes(StandardCharsets.UTF_8)));

        assertEquals("viewer-secret-key", crypto.decryptSecretKey(7, encrypted));
    }

    @Test
    void derivesStableRsa2048PublicKeyMaterialFromConfiguredPrivateKey() throws Exception {
        KeyPair keyPair = keyPair(2048);
        Path privateKey = writePrivateKey(keyPair, "private-key.pem");
        WeComChatDataCrypto crypto = new WeComChatDataCrypto(new Config(Map.of(
                "WECOM_CHATDATA_PRIVATE_KEY_FILE", privateKey.toString(),
                "WECOM_CHATDATA_PUBLIC_KEY_VERSION", "7"
        )));

        WeComChatDataCrypto.PublicKeyMaterial material = crypto.publicKeyMaterial();
        String encoded = material.pem()
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        RSAKey publicKey = (RSAKey) KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));

        assertEquals(7, material.version());
        assertEquals(2048, material.bitLength());
        assertEquals(2048, publicKey.getModulus().bitLength());
        assertEquals(((RSAPublicKey) keyPair.getPublic()).getModulus(), publicKey.getModulus());
        assertEquals(((RSAPublicKey) keyPair.getPublic()).getPublicExponent(),
                ((RSAPublicKey) publicKey).getPublicExponent());
        assertArrayEquals(keyPair.getPublic().getEncoded(),
                Base64.getDecoder().decode(encoded));
        assertTrue(material.pem().endsWith("-----END PUBLIC KEY-----\n"));
        assertTrue(material.sha256().matches("[0-9a-f]{64}"));
        assertEquals(material, crypto.publicKeyMaterial());
    }

    @Test
    void rejectsVersionMismatchAndInvalidCiphertextWithoutLeakingSecrets() throws Exception {
        Path privateKey = writePrivateKey(keyPair(2048), "private-key.pem");
        WeComChatDataCrypto crypto = new WeComChatDataCrypto(new Config(Map.of(
                "WECOM_CHATDATA_PRIVATE_KEY_FILE", privateKey.toString(),
                "WECOM_CHATDATA_PUBLIC_KEY_VERSION", "1"
        )));

        WeComChatDataException mismatch = assertThrows(WeComChatDataException.class,
                () -> crypto.decryptSecretKey(2, "cipher-secret"));
        WeComChatDataException invalid = assertThrows(WeComChatDataException.class,
                () -> crypto.decryptSecretKey(1, "cipher-secret"));

        assertEquals("WECOM_CHATDATA_KEY_VERSION_MISMATCH", mismatch.code());
        assertEquals("WECOM_CHATDATA_DECRYPT_FAILED", invalid.code());
        assertFalse(invalid.getMessage().contains("cipher-secret"));
    }

    @Test
    void rejectsNon2048BitPrivateKey() throws Exception {
        Path privateKey = writePrivateKey(keyPair(1024), "small-key.pem");

        WeComChatDataException exception = assertThrows(WeComChatDataException.class,
                () -> new WeComChatDataCrypto(new Config(Map.of(
                        "WECOM_CHATDATA_PRIVATE_KEY_FILE", privateKey.toString()))));

        assertEquals("WECOM_CHATDATA_DECRYPT_FAILED", exception.code());
    }

    @Test
    void rejectsPrivateKeyReadableByGroupOrOthers() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        Path privateKey = writePrivateKey(keyPair(2048), "wide-key.pem");
        Files.setPosixFilePermissions(privateKey, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ));

        WeComChatDataException exception = assertThrows(WeComChatDataException.class,
                () -> new WeComChatDataCrypto(new Config(Map.of(
                        "WECOM_CHATDATA_PRIVATE_KEY_FILE", privateKey.toString()))));

        assertEquals("WECOM_CHATDATA_DECRYPT_FAILED", exception.code());
    }

    private KeyPair keyPair(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private Path writePrivateKey(KeyPair keyPair, String filename) throws Exception {
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(keyPair.getPrivate().getEncoded());
        Path path = tempDir.resolve(filename);
        Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n" + encoded
                + "\n-----END PRIVATE KEY-----\n", StandardCharsets.US_ASCII);
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        }
        return path;
    }
}
