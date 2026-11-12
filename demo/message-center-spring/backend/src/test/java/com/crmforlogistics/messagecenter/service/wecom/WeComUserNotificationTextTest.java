package com.crmforlogistics.messagecenter.service.wecom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeComUserNotificationTextTest {

    @Test
    void singleMessageRendersContactAndPreview() {
        String text = WeComUserNotificationText.render("chatapp", "张三", 1, "你好，想问下运费");

        assertThat(text).isEqualTo("【WhatsApp】张三：你好，想问下运费");
    }

    @Test
    void aggregateRendersCountAndLatestPreview() {
        String text = WeComUserNotificationText.render("whatsapp", "张三", 3, "你好，想问下运费");

        assertThat(text).isEqualTo("【WhatsApp】张三 给你发了 3 条消息，最近一条：你好，想问下运费");
    }

    @Test
    void aggregateWithoutPreviewOmitsTheDanglingLabel() {
        String text = WeComUserNotificationText.render("email", "a@b.com", 2, "");

        assertThat(text).isEqualTo("【邮件】a@b.com 给你发了 2 条消息");
    }

    @Test
    void blankContactLabelFallsBackToPlaceholder() {
        String text = WeComUserNotificationText.render("email", "  ", 1, "hi");

        assertThat(text).isEqualTo("【邮件】未知联系人：hi");
    }

    @Test
    void previewCollapsesWhitespaceAndTruncates() {
        String body = "第一行\n第二行\t第三行 " + "x".repeat(80);

        String preview = WeComUserNotificationText.truncatePreview(body, "主题");

        assertThat(preview).doesNotContain("\n").doesNotContain("\t");
        assertThat(preview).hasSize(61);
        assertThat(preview).endsWith("…");
    }

    @Test
    void previewFallsBackToSubjectWhenBodyIsBlank() {
        assertThat(WeComUserNotificationText.truncatePreview("   ", "运费问题"))
                .isEqualTo("运费问题");
    }

    @Test
    void channelLabelsCoverTheSupportedChannels() {
        assertThat(WeComUserNotificationText.channelLabel("chatapp")).isEqualTo("WhatsApp");
        assertThat(WeComUserNotificationText.channelLabel("whatsapp")).isEqualTo("WhatsApp");
        assertThat(WeComUserNotificationText.channelLabel("email")).isEqualTo("邮件");
        assertThat(WeComUserNotificationText.channelLabel(null)).isEqualTo("消息");
        assertThat(WeComUserNotificationText.channelLabel("unknown")).isEqualTo("消息");
    }
}
