package com.crmforlogistics.messagecenter.channel.wecom;

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
        SUITE_ID,
        /**
         * 应用级事件缺 {@code ChangeType}。
         * 与 {@link #SUITE_ID} 一样按「通道特有的解析失败」单列，避免日志里两条通道的 stage 混淆。
         */
        APP_EVENT_MISSING_CHANGE_TYPE,
        /** 应用级事件类型不在本能力支持范围内（非 event 的 MsgType、非客户关系事件、未知 ChangeType）。 */
        APP_EVENT_UNSUPPORTED
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
        this.receiveIdSha256 = receiveId == null ? null : WeComCallbackCipher.sha256Hex(receiveId);
    }

    Stage stage() {
        return stage;
    }

    String receiveIdSha256() {
        return receiveIdSha256;
    }
}
