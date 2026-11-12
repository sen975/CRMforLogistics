package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeComCallbackCodecTest {
    private static final String SUITE = "dk-suite";
    private static final String TOKEN = "callback-token";
    private static final byte[] KEY = key();
    private static final String KEY_B64 = Base64.getEncoder().withoutPadding().encodeToString(KEY);
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void verifiesOfficialSignatureAndDecryptsSuiteTicket() throws Exception {
        WeComCallbackCodec codec = new WeComCallbackCodec(SUITE, TOKEN, KEY_B64, CLOCK);
        String timestamp = Long.toString(NOW.getEpochSecond());
        String encrypted = encrypt("<xml><SuiteId>dk-suite</SuiteId><InfoType>suite_ticket</InfoType>"
                + "<SuiteTicket>ticket-value</SuiteTicket></xml>");
        WeComCallbackCodec.DecodedCallback callback = codec.decode(
                WeComCallbackCodec.sha1(TOKEN, timestamp, "nonce", encrypted), timestamp, "nonce", encrypted);

        assertEquals("suite_ticket", callback.infoType());
        assertEquals("ticket-value", callback.suiteTicket());
    }

    @Test
    void decryptsExplicitlyConfiguredLoginSuiteCallbackAndRejectsUnknownSuite() throws Exception {
        Config config = new Config(Map.of(
                "WECOM_SUITE_ID", SUITE,
                "WECOM_LOGIN_SUITE_ID", "ww-login-suite",
                "WECOM_TOKEN", TOKEN,
                "WECOM_ENCODING_AES_KEY", KEY_B64));
        WeComCallbackCodec codec = new WeComCallbackCodec(config, CLOCK);
        String timestamp = Long.toString(NOW.getEpochSecond());
        String loginEncrypted = encrypt("<xml><SuiteId>ww-login-suite</SuiteId><InfoType>suite_ticket</InfoType>"
                + "<SuiteTicket>login-ticket</SuiteTicket></xml>", "ww-login-suite");
        WeComCallbackCodec.DecodedCallback decoded = codec.decode(
                WeComCallbackCodec.sha1(TOKEN, timestamp, "nonce", loginEncrypted),
                timestamp, "nonce", loginEncrypted);
        assertEquals("ww-login-suite", decoded.suiteId());

        String loginEcho = encrypt("login-suite-echo", "ww-login-suite");
        assertEquals("login-suite-echo", codec.verifyAndDecryptEcho(
                WeComCallbackCodec.sha1(TOKEN, timestamp, "nonce", loginEcho),
                timestamp, "nonce", loginEcho));

        String unknownEncrypted = encrypt("<xml><SuiteId>unknown</SuiteId><InfoType>suite_ticket</InfoType>"
                + "<SuiteTicket>ticket</SuiteTicket></xml>", "unknown");
        assertThrows(WeComAuthorizationException.class, () -> codec.decode(
                WeComCallbackCodec.sha1(TOKEN, timestamp, "nonce", unknownEncrypted),
                timestamp, "nonce", unknownEncrypted));
    }

    @Test
    void verifiesAndDecryptsUrlValidationEcho() throws Exception {
        WeComCallbackCodec codec = new WeComCallbackCodec(SUITE, TOKEN, KEY_B64, CLOCK);
        String timestamp = Long.toString(NOW.getEpochSecond());
        String encryptedEcho = encrypt("verified-echo");
        String signature = WeComCallbackCodec.sha1(TOKEN, timestamp, "nonce", encryptedEcho);

        assertEquals("verified-echo",
                codec.verifyAndDecryptEcho(signature, timestamp, "nonce", encryptedEcho));
        assertThrows(WeComAuthorizationException.class,
                () -> codec.verifyAndDecryptEcho("bad", timestamp, "nonce", encryptedEcho));
    }

    @Test
    void separatesCallbackReceiveIdFromBusinessSuiteId() throws Exception {
        String callbackReceiveId = "ww-callback-owner";
        WeComCallbackCodec codec = new WeComCallbackCodec(
                SUITE, callbackReceiveId, TOKEN, KEY_B64, CLOCK);
        String timestamp = Long.toString(NOW.getEpochSecond());
        String encryptedEcho = encrypt("verified-echo", callbackReceiveId);

        assertEquals("verified-echo", codec.verifyAndDecryptEcho(
                WeComCallbackCodec.sha1(TOKEN, timestamp, "nonce", encryptedEcho),
                timestamp, "nonce", encryptedEcho));

        String encryptedCallback = encrypt("<xml><SuiteId>dk-suite</SuiteId>"
                + "<InfoType>suite_ticket</InfoType><SuiteTicket>ticket-value</SuiteTicket></xml>");
        WeComCallbackCodec.DecodedCallback callback = codec.decode(
                WeComCallbackCodec.sha1(TOKEN, timestamp, "nonce", encryptedCallback),
                timestamp, "nonce", encryptedCallback);
        assertEquals(SUITE, callback.suiteId());
    }

    @Test
    void rejectsTamperedSignatureAndWrongReceiveId() throws Exception {
        WeComCallbackCodec codec = new WeComCallbackCodec(SUITE, TOKEN, KEY_B64, CLOCK);
        String timestamp = Long.toString(NOW.getEpochSecond());
        String encrypted = encrypt("<xml><SuiteId>dk-suite</SuiteId><InfoType>create_auth</InfoType>"
                + "<AuthCorpId>ww-corp</AuthCorpId><AuthCode>auth-code</AuthCode></xml>");
        assertThrows(WeComAuthorizationException.class,
                () -> codec.decode("bad", timestamp, "nonce", encrypted));
        String wrongReceive = encrypt("<xml><SuiteId>dk-suite</SuiteId><InfoType>create_auth</InfoType></xml>", "other-suite");
        String signature = WeComCallbackCodec.sha1(TOKEN, timestamp, "nonce", wrongReceive);
        assertThrows(WeComAuthorizationException.class,
                () -> codec.decode(signature, timestamp, "nonce", wrongReceive));
    }

    @Test
    void rejectsDtdAndOversizedFields() throws Exception {
        WeComCallbackCodec codec = new WeComCallbackCodec(SUITE, TOKEN, KEY_B64, CLOCK);
        String timestamp = Long.toString(NOW.getEpochSecond());
        String dtd = "<!DOCTYPE xml [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"
                + "<xml><InfoType>&xxe;</InfoType></xml>";
        String encrypted = encrypt(dtd);
        String signature = WeComCallbackCodec.sha1(TOKEN, timestamp, "nonce", encrypted);
        assertThrows(WeComAuthorizationException.class,
                () -> codec.decode(signature, timestamp, "nonce", encrypted));
        String huge = encrypt("<xml><InfoType>" + "x".repeat(513) + "</InfoType></xml>");
        String hugeSignature = WeComCallbackCodec.sha1(TOKEN, timestamp, "nonce", huge);
        assertThrows(WeComAuthorizationException.class,
                () -> codec.decode(hugeSignature, timestamp, "nonce", huge));
    }

    @Test
    void rejectsCallbackOutsideTheFixedClockWindow() throws Exception {
        WeComCallbackCodec codec = new WeComCallbackCodec(SUITE, TOKEN, KEY_B64, CLOCK);
        String timestamp = Long.toString(NOW.minusSeconds(901).getEpochSecond());
        String encrypted = encrypt("<xml><SuiteId>dk-suite</SuiteId><InfoType>suite_ticket</InfoType>"
                + "<SuiteTicket>ticket-value</SuiteTicket></xml>");
        String signature = WeComCallbackCodec.sha1(TOKEN, timestamp, "nonce", encrypted);

        assertEquals("WECOM_CALLBACK_SIGNATURE_INVALID", assertThrows(WeComAuthorizationException.class,
                () -> codec.decode(signature, timestamp, "nonce", encrypted)).code());
    }

    private static String encrypt(String xml) throws Exception {
        return encrypt(xml, SUITE);
    }

    private static String encrypt(String xml, String receiveId) throws Exception {
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
        byte[] receiveBytes = receiveId.getBytes(StandardCharsets.UTF_8);
        byte[] plain = ByteBuffer.allocate(16 + 4 + xmlBytes.length + receiveBytes.length)
                .put(new byte[16]).putInt(xmlBytes.length).put(xmlBytes).put(receiveBytes).array();
        int padding = 32 - (plain.length % 32);
        byte[] padded = Arrays.copyOf(plain, plain.length + padding);
        Arrays.fill(padded, plain.length, padded.length, (byte) padding);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(KEY, 0, 16));
        return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
    }

    private static byte[] key() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 1);
        return key;
    }
}
