package com.crmforlogistics.messagecenter;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/** Verifies and decrypts official WeCom suite callbacks. */
public final class WeComCallbackCodec {
    private static final int MAX_XML_BYTES = 1_048_576;
    private static final int MAX_FIELD = 512;
    private final String suiteId;
    private final Set<String> allowedSuiteIds;
    private final String callbackReceiveId;
    private final Set<String> allowedEchoReceiveIds;
    private final String token;
    private final byte[] aesKey;
    private final Clock clock;

    public WeComCallbackCodec(Config config) {
        this(config, Clock.systemUTC());
    }

    WeComCallbackCodec(Config config, Clock clock) {
        this(config.wecomSuiteId(), config.wecomLoginSuiteId(), config.wecomCallbackReceiveId(),
                config.wecomToken(), config.wecomEncodingAesKey(), clock);
    }

    WeComCallbackCodec(String suiteId, String token, String encodingAesKey) {
        this(suiteId, suiteId, token, encodingAesKey, Clock.systemUTC());
    }

    WeComCallbackCodec(String suiteId, String token, String encodingAesKey, Clock clock) {
        this(suiteId, suiteId, token, encodingAesKey, clock);
    }

    WeComCallbackCodec(String suiteId, String callbackReceiveId, String token,
                       String encodingAesKey, Clock clock) {
        this(suiteId, "", callbackReceiveId, token, encodingAesKey, clock);
    }

    private WeComCallbackCodec(String suiteId, String loginSuiteId, String callbackReceiveId, String token,
                               String encodingAesKey, Clock clock) {
        this.suiteId = require(suiteId, "suiteId", 128);
        LinkedHashSet<String> suiteIds = new LinkedHashSet<>();
        suiteIds.add(this.suiteId);
        if (loginSuiteId != null && !loginSuiteId.isBlank()) {
            suiteIds.add(require(loginSuiteId, "loginSuiteId", 128));
        }
        this.allowedSuiteIds = Set.copyOf(suiteIds);
        this.callbackReceiveId = require(callbackReceiveId, "callbackReceiveId", 128);
        LinkedHashSet<String> echoReceiveIds = new LinkedHashSet<>(suiteIds);
        echoReceiveIds.add(this.callbackReceiveId);
        this.allowedEchoReceiveIds = Set.copyOf(echoReceiveIds);
        this.token = require(token, "token", 512);
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        String padded = require(encodingAesKey, "encodingAesKey", 128);
        while (padded.length() % 4 != 0) padded += "=";
        try {
            this.aesKey = Base64.getDecoder().decode(padded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("WECOM_ENCODING_AES_KEY must be Base64", exception);
        }
        if (aesKey.length != 32) {
            throw new IllegalArgumentException("WECOM_ENCODING_AES_KEY must decode to 32 bytes");
        }
    }

    public DecodedCallback decode(String msgSignature, String timestamp, String nonce, String encryptXml)
            throws WeComAuthorizationException {
        try {
            require(msgSignature, "msg_signature", 128);
            require(timestamp, "timestamp", 32);
            require(nonce, "nonce", 128);
            require(encryptXml, "encrypt", MAX_XML_BYTES);
            String encrypted = encryptedValue(encryptXml);
            long epoch = verifySignature(msgSignature, timestamp, nonce, encrypted);
            DecryptedPayload payload = decrypt(encrypted, allowedSuiteIds);
            return parse(payload.xml(), payload.receiveId(), epoch);
        } catch (WeComAuthorizationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WeComAuthorizationException("WECOM_CALLBACK_SIGNATURE_INVALID", 403,
                    "企业微信授权回调验签或解密失败", exception);
        }
    }

    public String verifyAndDecryptEcho(String msgSignature, String timestamp, String nonce, String encryptedEcho)
            throws WeComAuthorizationException {
        try {
            require(msgSignature, "msg_signature", 128);
            require(timestamp, "timestamp", 32);
            require(nonce, "nonce", 128);
            require(encryptedEcho, "echostr", MAX_XML_BYTES);
            verifySignature(msgSignature, timestamp, nonce, encryptedEcho);
            return decrypt(encryptedEcho, allowedEchoReceiveIds).xml();
        } catch (WeComAuthorizationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WeComAuthorizationException("WECOM_CALLBACK_SIGNATURE_INVALID", 403,
                    "企业微信授权回调验签或解密失败", exception);
        }
    }

    private long verifySignature(String msgSignature, String timestamp, String nonce, String encrypted)
            throws Exception {
        long epoch = Long.parseLong(timestamp);
        if (Math.abs(clock.instant().getEpochSecond() - epoch) > 900) {
            throw new SecurityException("callback timestamp is outside the accepted window");
        }
        String expected = sha1(token, timestamp, nonce, encrypted);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                msgSignature.getBytes(StandardCharsets.US_ASCII))) {
            throw new SecurityException("callback signature is invalid");
        }
        return epoch;
    }

    private static String encryptedValue(String body) throws Exception {
        String trimmed = body.trim();
        if (!trimmed.startsWith("<")) return trimmed;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        Document document = builder.parse(new InputSource(new StringReader(trimmed)));
        String encrypted = field(document, "Encrypt");
        return require(encrypted, "Encrypt", MAX_XML_BYTES);
    }

    private DecryptedPayload decrypt(String encrypted, Set<String> expectedReceiveIds) throws Exception {
        byte[] ciphertext = Base64.getDecoder().decode(encrypted);
        if (ciphertext.length == 0 || ciphertext.length % 16 != 0 || ciphertext.length > MAX_XML_BYTES) {
            throw new IllegalArgumentException("encrypted callback has invalid dimensions");
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(aesKey, 0, 16));
        byte[] padded = cipher.doFinal(ciphertext);
        try {
            int padding = padded[padded.length - 1] & 0xff;
            if (padding < 1 || padding > 32 || padding > padded.length) throw new IllegalArgumentException("padding");
            for (int i = padded.length - padding; i < padded.length; i++) {
                if ((padded[i] & 0xff) != padding) throw new IllegalArgumentException("padding");
            }
            byte[] plain = java.util.Arrays.copyOf(padded, padded.length - padding);
            try {
                if (plain.length < 20) throw new IllegalArgumentException("callback plaintext is too short");
                int xmlLength = ByteBuffer.wrap(plain, 16, 4).getInt();
                if (xmlLength < 1 || xmlLength > MAX_XML_BYTES || 20L + xmlLength > plain.length) {
                    throw new IllegalArgumentException("callback XML length is invalid");
                }
                String xml = new String(plain, 20, xmlLength, StandardCharsets.UTF_8);
                String receiveId = new String(plain, 20 + xmlLength, plain.length - 20 - xmlLength,
                        StandardCharsets.UTF_8);
                if (!expectedReceiveIds.contains(receiveId)) {
                    throw new SecurityException("callback receive id is invalid");
                }
                return new DecryptedPayload(xml, receiveId);
            } finally {
                java.util.Arrays.fill(plain, (byte) 0);
            }
        } finally {
            java.util.Arrays.fill(padded, (byte) 0);
            java.util.Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private DecodedCallback parse(String xml, String receiveId, long epoch) throws Exception {
        if (xml.getBytes(StandardCharsets.UTF_8).length > MAX_XML_BYTES) throw new IllegalArgumentException("XML too large");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        Document document = builder.parse(new InputSource(new StringReader(xml)));
        String callbackSuiteId = field(document, "SuiteId");
        if (callbackSuiteId.isBlank() || !allowedSuiteIds.contains(callbackSuiteId)
                || !callbackSuiteId.equals(receiveId)) {
            throw new SecurityException("callback suite id is invalid");
        }
        String infoType = require(field(document, "InfoType"), "InfoType", 64);
        String authCorpId = field(document, "AuthCorpId");
        String authCode = field(document, "AuthCode");
        String suiteTicket = field(document, "SuiteTicket");
        String state = field(document, "State");
        for (String value : List.of(authCorpId, authCode, suiteTicket, state)) {
            if (value.length() > MAX_FIELD) throw new IllegalArgumentException("callback field is too long");
        }
        return new DecodedCallback(callbackSuiteId, infoType, authCorpId, authCode, suiteTicket, state,
                Instant.ofEpochSecond(epoch));
    }

    private static String field(Document document, String name) {
        var nodes = document.getElementsByTagName(name);
        if (nodes.getLength() == 0) return "";
        Node node = nodes.item(0);
        String value = node.getTextContent();
        return value == null ? "" : value.trim();
    }

    public static String sha1(String token, String timestamp, String nonce, String encrypted) {
        List<String> values = new ArrayList<>(List.of(token, timestamp, nonce, encrypted));
        Collections.sort(values);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(
                    String.join("", values).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-1 unavailable", exception);
        }
    }

    private static String require(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " is required and must not exceed " + max + " characters");
        }
        return value;
    }

    public record DecodedCallback(String suiteId, String infoType, String authCorpId, String authCode,
                                  String suiteTicket, String state, Instant timestamp) {}
    private record DecryptedPayload(String xml, String receiveId) {}
}
