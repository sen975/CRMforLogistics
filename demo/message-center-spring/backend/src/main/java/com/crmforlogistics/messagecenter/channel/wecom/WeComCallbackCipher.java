package com.crmforlogistics.messagecenter.channel.wecom;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 企业微信回调的签名与加解密共享实现。
 *
 * <p>模板回调（{@link WeComCallbackCodec}）与应用级回调（{@link WeComAppEventCodec}）用的是同一套
 * AES-256-CBC 规范 —— {@code Key = Base64Decode(EncodingAESKey + "=")}、{@code IV = Key} 前 16 字节、
 * 明文结构 {@code random(16) + msg_len(4, 大端) + msg + receiveid}、PKCS#7 填充、
 * {@code msg_signature = sha1(sort(token, timestamp, nonce, encrypt))}。
 *
 * <p><b>唯一差异是 receiveid</b>：模板通道校验 {@code suite_id}（可预先枚举，因此用集合成员判定），
 * 应用通道校验密文 {@code corpid}（企业数会增长，无法预先枚举，因此改为「解密后断言
 * {@code receiveid == 明文 ToUserName}」的自洽校验）。所以本类只负责解密并**返回** receiveid，
 * 由各调用方决定用什么规则去校验它。
 *
 * <p>抽出来的第二个原因是安全配置只能有一份：XML 解析的 XXE 加固、填充校验、密钥长度校验
 * 都是易漏项，复制一份就会有两份逐渐走样的加固。失败语义统一走 {@link WeComCallbackFailure}
 * 的 stage，便于按阶段定位是「验签失败」还是「解密失败」。
 */
final class WeComCallbackCipher {
    static final int MAX_XML_BYTES = 1_048_576;

    /**
     * WeCom 规范下填充块大小是 32（不是 AES 的 16）。
     * 校验上限必须按 32 放开，否则官方密文会被自家校验拒掉。
     */
    private static final int MAX_PADDING = 32;

    private static final int MIN_PLAINTEXT = 20;

    private WeComCallbackCipher() {}

    /** 解出 32 字节 AES 密钥；长度或编码不对时直接拒绝，避免把坏密钥带进运行期。 */
    static byte[] decodeKey(String encodingAesKey) {
        String padded = require(encodingAesKey, "encodingAesKey", 128);
        while (padded.length() % 4 != 0) {
            padded += "=";
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(padded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("WECOM_ENCODING_AES_KEY must be Base64", exception);
        }
        if (key.length != 32) {
            throw new IllegalArgumentException("WECOM_ENCODING_AES_KEY must decode to 32 bytes");
        }
        return key;
    }

    static String sha1(String token, String timestamp, String nonce, String encrypted) {
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

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    static String verifySignature(String token, String msgSignature, String timestamp, String nonce,
                                  String encrypted) {
        try {
            String expected = sha1(token, timestamp, nonce, encrypted);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    msgSignature.getBytes(StandardCharsets.US_ASCII))) {
                throw new SecurityException("callback signature is invalid");
            }
            return expected;
        } catch (Exception exception) {
            throw new WeComCallbackFailure(WeComCallbackFailure.Stage.SIGNATURE, exception);
        }
    }

    /**
     * 从信封 XML 取出 {@code <Encrypt>}。
     *
     * <p>回调体允许两种形态：裸密文（部分联调场景直接发密文）与 {@code <xml><Encrypt>…} 信封。
     * XML 解析一律关闭 DTD、外部实体与 XInclude —— 回调体是完全不可信的外部输入。
     */
    static String encryptedValue(String body) throws Exception {
        String trimmed = body.trim();
        if (!trimmed.startsWith("<")) {
            return trimmed;
        }
        String encrypted = field(parseXml(trimmed), "Encrypt");
        return require(encrypted, "Encrypt", MAX_XML_BYTES);
    }

    /** 关闭 DTD / 外部实体 / XInclude 后的 XML 解析，供信封与载荷共用。 */
    static Document parseXml(String xml) throws Exception {
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
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    /**
     * Base64 解码 + AES-256-CBC 解密 + 去 PKCS#7 填充，返回原始明文字节。
     *
     * <p>失败按阶段区分：{@code CIPHERTEXT}（长度/编码非法）→ {@code AES}（解密异常）
     * → {@code PADDING}（填充字节不合法）。区分这三者对排障很重要：前者说明请求根本不该被受理，
     * 后两者说明密钥对不上。
     */
    static byte[] aesDecrypt(byte[] aesKey, String base64Ciphertext) {
        byte[] ciphertext;
        try {
            ciphertext = Base64.getDecoder().decode(base64Ciphertext);
        } catch (Exception exception) {
            throw new WeComCallbackFailure(WeComCallbackFailure.Stage.CIPHERTEXT, exception);
        }
        if (ciphertext.length == 0 || ciphertext.length % 16 != 0 || ciphertext.length > MAX_XML_BYTES) {
            throw new WeComCallbackFailure(WeComCallbackFailure.Stage.CIPHERTEXT,
                    new IllegalArgumentException("encrypted callback has invalid dimensions"));
        }
        byte[] padded;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(aesKey, 0, 16));
            padded = cipher.doFinal(ciphertext);
        } catch (Exception exception) {
            java.util.Arrays.fill(ciphertext, (byte) 0);
            throw new WeComCallbackFailure(WeComCallbackFailure.Stage.AES, exception);
        }
        try {
            int padding = padded[padded.length - 1] & 0xff;
            if (padding < 1 || padding > MAX_PADDING || padding > padded.length) {
                throw paddingFailure();
            }
            for (int i = padded.length - padding; i < padded.length; i++) {
                if ((padded[i] & 0xff) != padding) {
                    throw paddingFailure();
                }
            }
            return java.util.Arrays.copyOf(padded, padded.length - padding);
        } finally {
            java.util.Arrays.fill(padded, (byte) 0);
            java.util.Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private static WeComCallbackFailure paddingFailure() {
        return new WeComCallbackFailure(WeComCallbackFailure.Stage.PADDING,
                new IllegalArgumentException("padding"));
    }

    /** 解开密文并切出「明文 + receiveid」，但**不校验** receiveid 取值。 */
    static Decrypted decrypt(byte[] aesKey, String base64Ciphertext) {
        byte[] plain = aesDecrypt(aesKey, base64Ciphertext);
        try {
            if (plain.length < MIN_PLAINTEXT) {
                throw new WeComCallbackFailure(WeComCallbackFailure.Stage.PLAINTEXT,
                        new IllegalArgumentException("callback plaintext is too short"));
            }
            int xmlLength = ByteBuffer.wrap(plain, 16, 4).getInt();
            if (xmlLength < 1 || xmlLength > MAX_XML_BYTES || MIN_PLAINTEXT + xmlLength > plain.length) {
                throw new WeComCallbackFailure(WeComCallbackFailure.Stage.PLAINTEXT,
                        new IllegalArgumentException("callback XML length is invalid"));
            }
            String xml = new String(plain, 20, xmlLength, StandardCharsets.UTF_8);
            String receiveId = new String(plain, 20 + xmlLength, plain.length - 20 - xmlLength,
                    StandardCharsets.UTF_8);
            return new Decrypted(xml, receiveId);
        } finally {
            java.util.Arrays.fill(plain, (byte) 0);
        }
    }

    /** 便捷重载：直接给 Base64 形态的 EncodingAESKey。 */
    static Decrypted decrypt(String aesKeyBase64, String base64Ciphertext) {
        return decrypt(decodeKey(aesKeyBase64), base64Ciphertext);
    }

    static String field(Document document, String name) {
        var nodes = document.getElementsByTagName(name);
        if (nodes.getLength() == 0) {
            return "";
        }
        Node node = nodes.item(0);
        String value = node.getTextContent();
        return value == null ? "" : value.trim();
    }

    static String require(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " is required and must not exceed " + max + " characters");
        }
        return value;
    }

    /** 明文字节零化前就切出 xml 与 receiveid，避免把整段明文留在调用方内存里。 */
    record Decrypted(String xml, String receiveId) {}
}
