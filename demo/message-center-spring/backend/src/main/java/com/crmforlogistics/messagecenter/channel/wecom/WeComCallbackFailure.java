package com.crmforlogistics.messagecenter.channel.wecom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class WeComCallbackFailure extends WeComException {
    enum Stage {
        INPUT,
        ENVELOPE_XML,
        TIMESTAMP,
        SIGNATURE,
        CIPHERTEXT,
        AES,
        PADDING,
        PLAINTEXT,
        RECEIVE_ID,
        PAYLOAD_XML,
        SUITE_ID
    }

    private final Stage stage;
    private final String receiveIdSha256;

    WeComCallbackFailure(Stage stage, Throwable cause) {
        this(stage, cause, null);
    }

    WeComCallbackFailure(Stage stage, Throwable cause, String receiveId) {
        super("WECOM_CALLBACK_SIGNATURE_INVALID", 403,
                "企业微信授权回调验签或解密失败", cause);
        this.stage = stage;
        this.receiveIdSha256 = receiveId == null ? null : sha256(receiveId);
    }

    Stage stage() {
        return stage;
    }

    String receiveIdSha256() {
        return receiveIdSha256;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
