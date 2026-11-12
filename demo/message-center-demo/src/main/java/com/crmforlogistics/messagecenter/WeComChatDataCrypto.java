package com.crmforlogistics.messagecenter;

import javax.crypto.Cipher;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

public final class WeComChatDataCrypto {
    private static final int MAX_KEY_FILE_BYTES = 16_384;
    private final int publicKeyVersion;
    private final PrivateKey privateKey;
    private final PublicKeyMaterial publicKeyMaterial;

    public WeComChatDataCrypto(Config config) throws WeComChatDataException {
        publicKeyVersion = config.wecomChatDataPublicKeyVersion();
        try {
            java.nio.file.Path path = config.wecomChatDataPrivateKeyFile();
            if (!path.isAbsolute() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > MAX_KEY_FILE_BYTES) {
                throw new IllegalArgumentException("private key unavailable");
            }
            requireOwnerOnlyPermissions(path);
            String pem = Files.readString(path, StandardCharsets.US_ASCII);
            String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(encoded);
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(privateKey instanceof RSAKey rsaKey) || rsaKey.getModulus().bitLength() != 2048) {
                throw new IllegalArgumentException("private key size invalid");
            }
            if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
                throw new IllegalArgumentException("private key public parameters unavailable");
            }
            byte[] publicKeyDer = KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent())).getEncoded();
            String publicKeyPem = "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(publicKeyDer)
                    + "\n-----END PUBLIC KEY-----\n";
            String sha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(publicKeyDer));
            publicKeyMaterial = new PublicKeyMaterial(publicKeyPem, publicKeyVersion, sha256,
                    crtKey.getModulus().bitLength());
        } catch (Exception exception) {
            throw decryptFailed(exception);
        }
    }

    private static void requireOwnerOnlyPermissions(java.nio.file.Path path) throws Exception {
        if (!Files.getFileStore(path).supportsFileAttributeView("posix")) return;
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (!permissions.contains(PosixFilePermission.OWNER_READ)
                || permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                || permissions.stream().anyMatch(permission -> permission.name().startsWith("GROUP_")
                || permission.name().startsWith("OTHERS_"))) {
            throw new IllegalArgumentException("private key permissions invalid");
        }
    }

    public String decryptSecretKey(int keyVersion, String encryptedSecretKey)
            throws WeComChatDataException {
        if (keyVersion != publicKeyVersion) {
            throw new WeComChatDataException("WECOM_CHATDATA_KEY_VERSION_MISMATCH", 500,
                    "企业微信会话密钥版本不匹配");
        }
        try {
            if (encryptedSecretKey == null || encryptedSecretKey.isBlank()
                    || encryptedSecretKey.length() > 4096) throw new IllegalArgumentException("cipher invalid");
            byte[] encrypted = Base64.getDecoder().decode(encryptedSecretKey);
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            String secret = decodeUtf8(cipher.doFinal(encrypted));
            if (secret.isBlank() || secret.length() > 512
                    || secret.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
                throw new IllegalArgumentException("secret invalid");
            }
            return secret;
        } catch (Exception exception) {
            throw decryptFailed(exception);
        }
    }

    public PublicKeyMaterial publicKeyMaterial() {
        return publicKeyMaterial;
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static WeComChatDataException decryptFailed(Throwable cause) {
        return new WeComChatDataException("WECOM_CHATDATA_DECRYPT_FAILED", 500,
                "企业微信会话密钥解密失败", cause);
    }

    public record PublicKeyMaterial(String pem, int version, String sha256, int bitLength) {}
}
