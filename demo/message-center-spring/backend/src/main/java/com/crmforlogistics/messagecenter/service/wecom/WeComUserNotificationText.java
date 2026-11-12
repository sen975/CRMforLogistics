package com.crmforlogistics.messagecenter.service.wecom;

import java.util.Locale;
import java.util.Map;

public final class WeComUserNotificationText {

    static final int PREVIEW_LIMIT = 60;

    private static final Map<String, String> CHANNEL_LABELS = Map.of(
            "chatapp", "WhatsApp",
            "whatsapp", "WhatsApp",
            "email", "邮件");

    private WeComUserNotificationText() {
    }

    public static String channelLabel(String channelType) {
        if (channelType == null || channelType.isBlank()) {
            return "消息";
        }
        return CHANNEL_LABELS.getOrDefault(channelType.toLowerCase(Locale.ROOT), "消息");
    }

    public static String truncatePreview(String bodyText, String subject) {
        String source = bodyText == null || bodyText.isBlank() ? subject : bodyText;
        if (source == null || source.isBlank()) {
            return "";
        }
        String flattened = source.replaceAll("\\s+", " ").trim();
        if (flattened.length() <= PREVIEW_LIMIT) {
            return flattened;
        }
        return flattened.substring(0, PREVIEW_LIMIT) + "…";
    }

    public static String render(String channelType, String contactLabel, int messageCount,
                                String preview) {
        String channel = channelLabel(channelType);
        String contact = contactLabel == null || contactLabel.isBlank()
                ? "未知联系人" : contactLabel.trim();
        boolean hasPreview = preview != null && !preview.isBlank();
        if (messageCount <= 1) {
            return "【" + channel + "】" + contact + (hasPreview ? "：" + preview : "");
        }
        return "【" + channel + "】" + contact + " 给你发了 " + messageCount + " 条消息"
                + (hasPreview ? "，最近一条：" + preview : "");
    }
}
