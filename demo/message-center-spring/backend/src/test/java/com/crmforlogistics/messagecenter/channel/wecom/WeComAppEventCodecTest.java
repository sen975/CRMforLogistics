package com.crmforlogistics.messagecenter.channel.wecom;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应用级解码器的行为验证。
 *
 * <p>加密与签名夹具是**独立实现**的（本地手写 PKCS#7 + AES-CBC、本地手写带排序的 SHA-1），
 * 不复用被测代码 —— 用被测代码自己加密再解密是同一段逻辑自证，测不出协议层偏差。
 */
class WeComAppEventCodecTest {

    /** 32 字节全 0 密钥，Base64 形态合法。 */
    private static final String AES_KEY_B64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String TOKEN = "appToken";
    private static final String CORP_ID = "wpxxxxxxxxcorpid";
    private static final String NONCE = "appNonce";
    private static final Instant NOW = Instant.parse("2026-09-21T02:00:00Z");
    /** 官方文档 92130 示例里的 CreateTime，事件 XML 的原始值。 */
    private static final long CREATE_TIME = 1403610513L;

    private static WeComAppEventCodec codec() {
        return new WeComAppEventCodec(TOKEN, AES_KEY_B64, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String timestamp() {
        return String.valueOf(NOW.getEpochSecond());
    }

    // ---------- 6 个客户关系事件的字段解析 ----------

    @Test
    void decodesAddExternalContactWithStateAndWelcomeCode() throws Exception {
        String xml = event("add_external_contact",
                "<State>teststate</State><WelcomeCode>WELCOMECODE</WelcomeCode>");
        String cipher = ciphertext(xml, CORP_ID, false);

        var decoded = codec().decode(signature(cipher), timestamp(), NONCE, envelope(cipher));

        assertEquals(CORP_ID, decoded.toUserName());
        assertEquals("sys", decoded.fromUserName());
        assertEquals("change_external_contact", decoded.event());
        assertEquals("add_external_contact", decoded.changeType());
        assertEquals("zhangsan", decoded.userId());
        assertEquals("woAJ2GCAAAXtWyujaWJHDDGi0mAAAA", decoded.externalUserId());
        assertEquals("teststate", decoded.state());
        assertEquals("WELCOMECODE", decoded.welcomeCode());
        assertNull(decoded.failReason());
        assertNull(decoded.providerSource());
        assertNull(decoded.chatId());
        assertEquals(Instant.ofEpochSecond(CREATE_TIME), decoded.providerCreatedAt());
    }

    @Test
    void decodesEditExternalContactWithNoOptionalFields() throws Exception {
        var decoded = decode("edit_external_contact", "");

        assertEquals("edit_external_contact", decoded.changeType());
        assertEquals("zhangsan", decoded.userId());
        assertEquals("woAJ2GCAAAXtWyujaWJHDDGi0mAAAA", decoded.externalUserId());
        assertNull(decoded.state());
        assertNull(decoded.welcomeCode());
    }

    @Test
    void decodesAddHalfExternalContact() throws Exception {
        var decoded = decode("add_half_external_contact",
                "<State>teststate</State><WelcomeCode>WELCOMECODE</WelcomeCode>");

        assertEquals("add_half_external_contact", decoded.changeType());
        assertEquals("teststate", decoded.state());
        assertEquals("WELCOMECODE", decoded.welcomeCode());
    }

    @Test
    void decodesDeleteExternalContactWithDeleteSource() throws Exception {
        var decoded = decode("del_external_contact", "<Source>DELETE_BY_TRANSFER</Source>");

        assertEquals("del_external_contact", decoded.changeType());
        assertEquals("DELETE_BY_TRANSFER", decoded.providerSource());
    }

    @Test
    void decodesDeleteFollowUser() throws Exception {
        var decoded = decode("del_follow_user", "");

        assertEquals("del_follow_user", decoded.changeType());
        assertEquals("woAJ2GCAAAXtWyujaWJHDDGi0mACHAAA", decoded.externalUserId());
        assertNull(decoded.providerSource());
    }

    @Test
    void decodesTransferFailWithFailReason() throws Exception {
        var decoded = decode("transfer_fail", "<FailReason>customer_refused</FailReason>");

        assertEquals("transfer_fail", decoded.changeType());
        assertEquals("customer_refused", decoded.failReason());
    }

    @Test
    void decodesCustomerLimitExceededTransferFailure() throws Exception {
        var decoded = decode("transfer_fail", "<FailReason>customer_limit_exceed</FailReason>");

        assertEquals("customer_limit_exceed", decoded.failReason());
    }

    // ---------- 自洽校验与拒绝路径 ----------

    @Test
    void rejectsWhenToUserNameIsNotTheDecryptedReceiveId() throws Exception {
        // 密文尾部带的是真正的 corpid，但 XML 里的 ToUserName 被换成了别的企业 —— 必须拒绝。
        String xml = event("add_external_contact", "").replace(CORP_ID, "wpSOMEBODYELSE");
        String cipher = ciphertext(xml, CORP_ID, false);

        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> codec().decode(signature(cipher), timestamp(), NONCE, envelope(cipher)));
        assertEquals(WeComCallbackFailure.Stage.RECEIVE_ID, failure.stage());
        assertEquals(WeComCallbackCipher.sha256Hex(CORP_ID), failure.receiveIdSha256());
    }

    @Test
    void rejectsWhenToUserNameIsMissing() throws Exception {
        String xml = "<xml><FromUserName>sys</FromUserName><CreateTime>" + CREATE_TIME
                + "</CreateTime><MsgType>event</MsgType><Event>change_external_contact</Event>"
                + "<ChangeType>add_external_contact</ChangeType><UserID>u</UserID>"
                + "<ExternalUserID>e</ExternalUserID></xml>";
        String cipher = ciphertext(xml, CORP_ID, false);

        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> codec().decode(signature(cipher), timestamp(), NONCE, envelope(cipher)));
        assertEquals(WeComCallbackFailure.Stage.RECEIVE_ID, failure.stage());
    }

    @Test
    void rejectsNonEventMsgType() throws Exception {
        String xml = "<xml><ToUserName>" + CORP_ID + "</ToUserName><FromUserName>sys</FromUserName>"
                + "<CreateTime>" + CREATE_TIME + "</CreateTime><MsgType>text</MsgType>"
                + "<Content>hello</Content></xml>";
        String cipher = ciphertext(xml, CORP_ID, false);

        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> codec().decode(signature(cipher), timestamp(), NONCE, envelope(cipher)));
        assertEquals(WeComCallbackFailure.Stage.APP_EVENT_UNSUPPORTED, failure.stage());
    }

    @Test
    void rejectsCustomerChatEventWhichIsNotInScope() throws Exception {
        String xml = "<xml><ToUserName>" + CORP_ID + "</ToUserName><FromUserName>sys</FromUserName>"
                + "<CreateTime>" + CREATE_TIME + "</CreateTime><MsgType>event</MsgType>"
                + "<Event>change_external_chat</Event><ChatId>CHAT_ID</ChatId>"
                + "<ChangeType>create</ChangeType></xml>";
        String cipher = ciphertext(xml, CORP_ID, false);

        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> codec().decode(signature(cipher), timestamp(), NONCE, envelope(cipher)));
        assertEquals(WeComCallbackFailure.Stage.APP_EVENT_UNSUPPORTED, failure.stage());
    }

    @Test
    void rejectsUnknownChangeType() throws Exception {
        String xml = event("some_future_change_type", "");

        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> decodeStrict(xml));
        assertEquals(WeComCallbackFailure.Stage.APP_EVENT_UNSUPPORTED, failure.stage());
    }

    @Test
    void rejectsMissingChangeType() throws Exception {
        String xml = "<xml><ToUserName>" + CORP_ID + "</ToUserName><FromUserName>sys</FromUserName>"
                + "<CreateTime>" + CREATE_TIME + "</CreateTime><MsgType>event</MsgType>"
                + "<Event>change_external_contact</Event><UserID>zhangsan</UserID>"
                + "<ExternalUserID>wmZZZ</ExternalUserID></xml>";

        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> decodeStrict(xml));
        assertEquals(WeComCallbackFailure.Stage.APP_EVENT_MISSING_CHANGE_TYPE, failure.stage());
    }

    @Test
    void rejectsMissingUserIdentifiers() throws Exception {
        String xml = "<xml><ToUserName>" + CORP_ID + "</ToUserName><FromUserName>sys</FromUserName>"
                + "<CreateTime>" + CREATE_TIME + "</CreateTime><MsgType>event</MsgType>"
                + "<Event>change_external_contact</Event>"
                + "<ChangeType>del_follow_user</ChangeType><UserID>zhangsan</UserID></xml>";

        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> decodeStrict(xml));
        assertEquals(WeComCallbackFailure.Stage.PAYLOAD_XML, failure.stage());
    }

    @Test
    void rejectsMissingCreateTimeBecauseDedupeNeedsIt() throws Exception {
        String xml = "<xml><ToUserName>" + CORP_ID + "</ToUserName><FromUserName>sys</FromUserName>"
                + "<MsgType>event</MsgType><Event>change_external_contact</Event>"
                + "<ChangeType>add_external_contact</ChangeType><UserID>zhangsan</UserID>"
                + "<ExternalUserID>wmZZZ</ExternalUserID></xml>";

        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> decodeStrict(xml));
        assertEquals(WeComCallbackFailure.Stage.PAYLOAD_XML, failure.stage());
    }

    @Test
    void rejectsOverlongState() throws Exception {
        String xml = event("add_external_contact", "<State>" + "s".repeat(257) + "</State>");

        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> decodeStrict(xml));
        assertEquals(WeComCallbackFailure.Stage.PAYLOAD_XML, failure.stage());
    }

    @Test
    void rejectsExpiredTimestamp() throws Exception {
        String cipher = ciphertext(event("add_external_contact", ""), CORP_ID, false);
        String stale = String.valueOf(NOW.getEpochSecond() - 1000);

        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> codec().decode(localSha1(TOKEN, stale, NONCE, cipher), stale, NONCE, envelope(cipher)));
        assertEquals(WeComCallbackFailure.Stage.TIMESTAMP, failure.stage());
    }

    @Test
    void rejectsBadSignature() throws Exception {
        String cipher = ciphertext(event("add_external_contact", ""), CORP_ID, false);

        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> codec().decode("deadbeef", timestamp(), NONCE, envelope(cipher)));
        assertEquals(WeComCallbackFailure.Stage.SIGNATURE, failure.stage());
    }

    @Test
    void rejectsBrokenPadding() throws Exception {
        String cipher = ciphertext(event("add_external_contact", ""), CORP_ID, true);

        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> codec().decode(signature(cipher), timestamp(), NONCE, envelope(cipher)));
        assertEquals(WeComCallbackFailure.Stage.PADDING, failure.stage());
    }

    @Test
    void rejectsCiphertextWithInvalidDimensions() throws Exception {
        String cipher = Base64.getEncoder().encodeToString("short".getBytes(StandardCharsets.UTF_8));

        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> codec().decode(signature(cipher), timestamp(), NONCE, envelope(cipher)));
        assertEquals(WeComCallbackFailure.Stage.CIPHERTEXT, failure.stage());
    }

    @Test
    void rejectsEnvelopeWithoutEncrypt() {
        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> codec().decode("sig", timestamp(), NONCE, "<xml><Other>1</Other></xml>"));
        assertEquals(WeComCallbackFailure.Stage.ENVELOPE_XML, failure.stage());
    }

    @Test
    void rejectsBlankInput() {
        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> codec().decode("", timestamp(), NONCE, "<xml><Encrypt>x</Encrypt></xml>"));
        assertEquals(WeComCallbackFailure.Stage.INPUT, failure.stage());
    }

    @Test
    void rejectsBlankCredentials() {
        assertThrows(IllegalArgumentException.class, () -> new WeComAppEventCodec("", AES_KEY_B64));
        assertThrows(IllegalArgumentException.class, () -> new WeComAppEventCodec(TOKEN, ""));
        assertThrows(IllegalArgumentException.class, () -> new WeComAppEventCodec(TOKEN, "!!!!!"));
    }

    @Test
    void neverNormalizesCiphertextIdCase() throws Exception {
        // 官方明确提示密文 ID 区分大小写；这里锁住「原样保留」，防止有人顺手加 toLowerCase()。
        String lower = event("del_follow_user", "");
        String upper = lower.replace("woAJ2GCAAAXtWyujaWJHDDGi0mACHAAA",
                "WOAJ2GCAAAXTWYUJAWJHDDGI0MACHAAA");

        assertEquals("woAJ2GCAAAXtWyujaWJHDDGi0mACHAAA", decodeStrict(lower).externalUserId());
        assertEquals("WOAJ2GCAAAXTWYUJAWJHDDGI0MACHAAA", decodeStrict(upper).externalUserId());
    }

    // ---------- URL 验证 ----------

    @Test
    void verifyAndDecryptEchoReturnsProviderPlaintext() throws Exception {
        String echoPlain = "randomEchoValue12345678";
        String cipher = ciphertext(echoPlain, CORP_ID, false);

        assertEquals(echoPlain, codec().verifyAndDecryptEcho(
                signature(cipher), timestamp(), NONCE, cipher));
    }

    @Test
    void verifyAndDecryptEchoRejectsXmlWhoseToUserNameDisagreesWithReceiveId() throws Exception {
        String xml = event("add_external_contact", "").replace(CORP_ID, "wpSOMEBODYELSE");
        String cipher = ciphertext(xml, CORP_ID, false);

        WeComCallbackFailure failure = assertThrows(WeComCallbackFailure.class,
                () -> codec().verifyAndDecryptEcho(signature(cipher), timestamp(), NONCE, cipher));
        assertEquals(WeComCallbackFailure.Stage.RECEIVE_ID, failure.stage());
    }

    @Test
    void supportedChangeTypesAreExactlyTheSixCustomerRelationEvents() {
        assertEquals(6, WeComAppEventCodec.SUPPORTED_CHANGE_TYPES.size());
        assertTrue(WeComAppEventCodec.SUPPORTED_CHANGE_TYPES.containsAll(List.of(
                "add_external_contact", "edit_external_contact", "add_half_external_contact",
                "del_external_contact", "del_follow_user", "transfer_fail")));
    }

    // ---------- 夹具 ----------

    private static WeComAppEventCodec.DecodedAppEvent decode(String changeType, String extraFields)
            throws Exception {
        return decodeStrict(event(changeType, extraFields));
    }

    private static WeComAppEventCodec.DecodedAppEvent decodeStrict(String xml) throws Exception {
        String cipher = ciphertext(xml, CORP_ID, false);
        return codec().decode(signature(cipher), timestamp(), NONCE, envelope(cipher));
    }

    private static String event(String changeType, String extraFields) {
        String externalUserId = "del_follow_user".equals(changeType)
                ? "woAJ2GCAAAXtWyujaWJHDDGi0mACHAAA" : "woAJ2GCAAAXtWyujaWJHDDGi0mAAAA";
        return "<xml><ToUserName>" + CORP_ID + "</ToUserName>"
                + "<FromUserName>sys</FromUserName>"
                + "<CreateTime>" + CREATE_TIME + "</CreateTime>"
                + "<MsgType>event</MsgType>"
                + "<Event>change_external_contact</Event>"
                + "<ChangeType>" + changeType + "</ChangeType>"
                + "<UserID>zhangsan</UserID>"
                + "<ExternalUserID>" + externalUserId + "</ExternalUserID>"
                + extraFields
                + "</xml>";
    }

    private static String signature(String rawCiphertext) {
        return localSha1(TOKEN, timestamp(), NONCE, rawCiphertext);
    }

    private static String envelope(String rawCiphertext) {
        return "<xml><Encrypt><![CDATA[" + rawCiphertext + "]]></Encrypt></xml>";
    }

    private static byte[] aesKeyBytes() {
        return Base64.getDecoder().decode(AES_KEY_B64);
    }

    /**
     * 独立实现的加密方向：PKCS#7 填充到 32 字节块（企微规范），AES-256-CBC，IV = 密钥前 16 字节。
     * 刻意不复用被测代码 —— 否则「加密对了」只是同义反复。
     *
     * @param brokenPadding 故意把最后一个填充字节写成 0，用来验证填充校验确实会拒绝
     */
    private static String ciphertext(String text, String receiveId, boolean brokenPadding) throws Exception {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] receiveBytes = receiveId.getBytes(StandardCharsets.UTF_8);
        byte[] plain = new byte[16 + 4 + textBytes.length + receiveBytes.length];
        ByteBuffer.wrap(plain).put(new byte[16]).putInt(textBytes.length).put(textBytes).put(receiveBytes);

        int blockSize = 32;
        int padding = blockSize - (plain.length % blockSize);
        byte[] padded = new byte[plain.length + padding];
        System.arraycopy(plain, 0, padded, 0, plain.length);
        for (int i = plain.length; i < padded.length; i++) {
            padded[i] = (byte) padding;
        }
        if (brokenPadding) {
            padded[padded.length - 1] = 0;
        }

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        byte[] key = aesKeyBytes();
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
        return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
    }

    /** 独立实现的签名：四值排序拼接后取 SHA-1 十六进制。 */
    private static String localSha1(String token, String timestamp, String nonce, String encrypted) {
        List<String> values = new ArrayList<>(List.of(token, timestamp, nonce, encrypted));
        Collections.sort(values);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(String.join("", values).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
