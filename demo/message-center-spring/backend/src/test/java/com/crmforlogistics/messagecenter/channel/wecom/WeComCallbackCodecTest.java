package com.crmforlogistics.messagecenter.channel.wecom;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;

class WeComCallbackCodecTest {

    // 32 bytes of 0x00, valid Base64
    private static final String AES_KEY_B64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String SUITE_ID = "ww1234567890abcdef";
    private static final String TOKEN = "testToken";

    // Derived from AES_KEY_B64: after padding fix (to multiple of 4), decode to 32 bytes
    private static byte[] aesKeyBytes() {
        String padded = AES_KEY_B64;
        while (padded.length() % 4 != 0) padded += "=";
        byte[] key = Base64.getDecoder().decode(padded);
        if (key.length < 32) key = java.util.Arrays.copyOf(key, 32);
        return key;
    }

    @Test
    void shouldConstructFromValidParams() {
        var codec = new WeComCallbackCodec(SUITE_ID, TOKEN, AES_KEY_B64);
        assertNotNull(codec);
    }

    @Test
    void shouldRejectBlankSuiteId() {
        assertThrows(IllegalArgumentException.class,
                () -> new WeComCallbackCodec("", TOKEN, AES_KEY_B64));
    }

    @Test
    void shouldRejectBadBase64Key() {
        assertThrows(IllegalArgumentException.class,
                () -> new WeComCallbackCodec(SUITE_ID, TOKEN, "!!!!!"));
    }

    @Test
    void shouldRejectShortAesKey() {
        var shortKey = Base64.getEncoder().encodeToString("short".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class,
                () -> new WeComCallbackCodec(SUITE_ID, TOKEN, shortKey));
    }

    @Test
    void sha1ShouldBeDeterministic() {
        var result1 = WeComCallbackCodec.sha1("a", "1", "b", "data");
        var result2 = WeComCallbackCodec.sha1("a", "1", "b", "data");
        assertEquals(result1, result2);
        assertEquals(40, result1.length());
    }

    @Test
    void sha1ShouldSortParameters() {
        var sorted = WeComCallbackCodec.sha1("a", "b", "c", "d");
        var unsorted = WeComCallbackCodec.sha1("d", "b", "a", "c");
        assertEquals(sorted, unsorted);
    }

    @Test
    void shouldRejectExpiredTimestamp() throws Exception {
        var codec = new WeComCallbackCodec(SUITE_ID, SUITE_ID, TOKEN, AES_KEY_B64,
                java.time.Clock.systemUTC());

        String xml = "<xml><SuiteId>" + SUITE_ID + "</SuiteId><InfoType>suite_ticket</InfoType><SuiteTicket>ticket123</SuiteTicket></xml>";
        String rawCiphertext = encrypt(aesKeyBytes(), SUITE_ID, xml);
        String xmlBody = wrapEncryptXml(rawCiphertext);
        long oldTimestamp = java.time.Instant.now().getEpochSecond() - 1000;
        String nonce = "testNonce";
        String sig = WeComCallbackCodec.sha1(TOKEN, String.valueOf(oldTimestamp), nonce, rawCiphertext);

        assertThrows(WeComException.class,
                () -> codec.decode(sig, String.valueOf(oldTimestamp), nonce, xmlBody));
    }

    @Test
    void shouldRejectBadSignature() throws Exception {
        var codec = new WeComCallbackCodec(SUITE_ID, SUITE_ID, TOKEN, AES_KEY_B64,
                java.time.Clock.systemUTC());

        String xml = "<xml><SuiteId>" + SUITE_ID + "</SuiteId><InfoType>suite_ticket</InfoType><SuiteTicket>ticket123</SuiteTicket></xml>";
        String rawCiphertext = encrypt(aesKeyBytes(), SUITE_ID, xml);
        String xmlBody = wrapEncryptXml(rawCiphertext);
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        String nonce = "testNonce";
        String badSig = "bad" + WeComCallbackCodec.sha1(TOKEN, timestamp, nonce, rawCiphertext);

        assertThrows(WeComException.class,
                () -> codec.decode(badSig, timestamp, nonce, xmlBody));
    }

    @Test
    void shouldDecodeValidCallback() throws Exception {
        var codec = new WeComCallbackCodec(SUITE_ID, SUITE_ID, TOKEN, AES_KEY_B64,
                java.time.Clock.systemUTC());

        String xml = "<xml><SuiteId>" + SUITE_ID + "</SuiteId><InfoType>suite_ticket</InfoType><AuthCorpId>corp123</AuthCorpId><SuiteTicket>ticket456</SuiteTicket></xml>";
        String rawCiphertext = encrypt(aesKeyBytes(), SUITE_ID, xml);
        String xmlBody = wrapEncryptXml(rawCiphertext);
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        String nonce = "testNonce";
        String sig = WeComCallbackCodec.sha1(TOKEN, timestamp, nonce, rawCiphertext);

        var result = codec.decode(sig, timestamp, nonce, xmlBody);
        assertEquals(SUITE_ID, result.suiteId());
        assertEquals("suite_ticket", result.infoType());
        assertEquals("corp123", result.authCorpId());
        assertEquals("ticket456", result.suiteTicket());
    }

    @Test
    void shouldDecodeEchoString() throws Exception {
        var codec = new WeComCallbackCodec(SUITE_ID, SUITE_ID, TOKEN, AES_KEY_B64,
                java.time.Clock.systemUTC());

        String echoPlain = "testEchoValue12345678";
        String rawCiphertext = encrypt(aesKeyBytes(), SUITE_ID, echoPlain);
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        String nonce = "echoNonce";
        String sig = WeComCallbackCodec.sha1(TOKEN, timestamp, nonce, rawCiphertext);

        String decrypted = codec.verifyAndDecryptEcho(sig, timestamp, nonce, rawCiphertext);
        assertEquals(echoPlain, decrypted);
    }

    private static String encrypt(byte[] aesKey, String receiveId, String text) throws Exception {
        byte[] random = new byte[16];
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] receiveBytes = receiveId.getBytes(StandardCharsets.UTF_8);

        byte[] plain = new byte[16 + 4 + textBytes.length + receiveBytes.length];
        ByteBuffer.wrap(plain).put(random).putInt(textBytes.length).put(textBytes).put(receiveBytes);

        int blockSize = 32;
        int padding = blockSize - (plain.length % blockSize);
        byte[] padded = new byte[plain.length + padding];
        System.arraycopy(plain, 0, padded, 0, plain.length);
        for (int i = plain.length; i < padded.length; i++) padded[i] = (byte) padding;

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                new IvParameterSpec(aesKey, 0, 16));
        return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
    }

    private static String wrapEncryptXml(String ciphertext) {
        return "<xml><Encrypt><![CDATA[" + ciphertext + "]]></Encrypt></xml>";
    }
}
