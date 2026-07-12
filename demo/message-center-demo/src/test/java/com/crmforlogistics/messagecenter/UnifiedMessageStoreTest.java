package com.crmforlogistics.messagecenter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class UnifiedMessageStoreTest {
    public static void main(String[] args) throws Exception {
        mergesEmailAndChatAppContactPointsIntoOneTimeline();
        splitsMergedContactPointWithoutDeletingRawMessages();
        exposesWecomAdapterPlaceholderAsUnavailableChannel();
        chatAppStatusRecordsUpdateOriginalMessageInsteadOfCreatingMessages();
        rendersChatAppTemplateMessagesFromTemplateCache();
        rendersWebShellWithChineseCopyAndUnifiedSendActions();
        chatAppTextMessagesUseMessageBodyAsContactPreview();
    }

    private static void mergesEmailAndChatAppContactPointsIntoOneTimeline() throws Exception {
        Path dir = Files.createTempDirectory("message-center-merge-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);

        Files.writeString(emailData.resolve("inbox.jsonl"), "{"
                + "\"id\":\"mail-1\","
                + "\"storedAt\":\"2026-07-10T01:00:00Z\","
                + "\"direction\":\"in\","
                + "\"contactEmail\":\"buyer@example.com\","
                + "\"contactName\":\"Buyer\","
                + "\"from\":\"Buyer <buyer@example.com>\","
                + "\"to\":\"seller@example.com\","
                + "\"subject\":\"Need quote\","
                + "\"sentDate\":\"2026-07-10T01:00:00Z\","
                + "\"summary\":\"Need quote body\","
                + "\"bodyText\":\"Need quote body\","
                + "\"messageId\":\"<mail-1@example.com>\""
                + "}\n", StandardCharsets.UTF_8);

        Files.writeString(chatData.resolve("messages.jsonl"), "{"
                + "\"id\":\"chat-1\","
                + "\"direction\":\"inbound\","
                + "\"timestamp\":\"2026-07-10T01:05:00Z\","
                + "\"from\":\"8613800000000\","
                + "\"to\":\"8613266259485\","
                + "\"text\":\"{\\\"text\\\":\\\"WhatsApp hello\\\"}\","
                + "\"raw\":\"{}\""
                + "}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));
        assertEquals("2", Integer.toString(store.contacts().size()));

        store.mergeContacts("email:buyer@example.com", "chatapp:whatsapp:8613800000000");

        List<UnifiedContact> contacts = store.contacts();
        assertEquals("1", Integer.toString(contacts.size()));
        assertEquals("2", Integer.toString(contacts.get(0).points.size()));
        assertEquals("email,chatapp", String.join(",", contacts.get(0).channels));

        List<UnifiedMessage> thread = store.thread("email:buyer@example.com");
        assertEquals("2", Integer.toString(thread.size()));
        assertEquals("email", thread.get(0).channel);
        assertEquals("chatapp", thread.get(1).channel);
        assertEquals("WhatsApp hello", thread.get(1).text);
    }

    private static void splitsMergedContactPointWithoutDeletingRawMessages() throws Exception {
        Path dir = Files.createTempDirectory("message-center-split-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(emailData.resolve("inbox.jsonl"), "{\"id\":\"mail-1\",\"direction\":\"in\",\"contactEmail\":\"buyer@example.com\",\"contactName\":\"Buyer\",\"subject\":\"Need quote\",\"sentDate\":\"2026-07-10T01:00:00Z\",\"bodyText\":\"Body\"}\n", StandardCharsets.UTF_8);
        Files.writeString(chatData.resolve("messages.jsonl"), "{\"id\":\"chat-1\",\"direction\":\"inbound\",\"timestamp\":\"2026-07-10T01:05:00Z\",\"from\":\"8613800000000\",\"to\":\"8613266259485\",\"text\":\"hi\",\"raw\":\"{}\"}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));
        store.mergeContacts("email:buyer@example.com", "chatapp:whatsapp:8613800000000");
        store.splitContact("email:buyer@example.com", "chatapp:whatsapp:8613800000000");

        assertEquals("2", Integer.toString(store.contacts().size()));
        assertEquals("1", Integer.toString(store.thread("email:buyer@example.com").size()));
        assertEquals("1", Integer.toString(store.thread("chatapp:whatsapp:8613800000000").size()));
    }

    private static void exposesWecomAdapterPlaceholderAsUnavailableChannel() throws Exception {
        Path dir = Files.createTempDirectory("message-center-wecom-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

        ChannelCapability capability = store.channelCapability("wecom");

        assertEquals("wecom", capability.channel);
        assertEquals("false", Boolean.toString(capability.available));
        assertContains(capability.reason, "reserved");
    }

    private static void chatAppStatusRecordsUpdateOriginalMessageInsteadOfCreatingMessages() throws Exception {
        Path dir = Files.createTempDirectory("message-center-status-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(chatData.resolve("messages.jsonl"), ""
                + "{\"id\":\"chat-1\",\"direction\":\"outbound\",\"timestamp\":\"2026-07-10T01:00:00Z\",\"from\":\"8613266259485\",\"to\":\"8613800000000\",\"text\":\"hello\",\"raw\":\"{}\"}\n"
                + "{\"id\":\"chat-1-status\",\"direction\":\"status\",\"timestamp\":\"2026-07-10T01:01:00Z\",\"from\":\"8613266259485\",\"to\":\"8613800000000\",\"text\":\"Status: Read\",\"raw\":\"{}\"}\n",
                StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

        List<UnifiedMessage> thread = store.thread("chatapp:whatsapp:8613800000000");
        assertEquals("1", Integer.toString(thread.size()));
        assertEquals("Read", thread.get(0).status);
        assertEquals("1", Integer.toString(store.contacts().size()));
    }

    private static void rendersChatAppTemplateMessagesFromTemplateCache() throws Exception {
        Path dir = Files.createTempDirectory("message-center-template-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(dir.resolve("templates.json"), "[{\"templateCode\":\"tpl-1\",\"templateName\":\"wl\",\"languageCode\":\"zh_CN\",\"body\":\"Hello $(text), address $(text1), contact $(text2)\"}]", StandardCharsets.UTF_8);
        Files.writeString(chatData.resolve("messages.jsonl"), "{"
                + "\"id\":\"chat-template-1\","
                + "\"direction\":\"outbound\","
                + "\"timestamp\":\"2026-07-10T01:00:00Z\","
                + "\"from\":\"8613266259485\","
                + "\"to\":\"8613800000000\","
                + "\"text\":\"\","
                + "\"raw\":\"{\\\"messageType\\\":\\\"TEMPLATE\\\",\\\"templateCode\\\":\\\"tpl-1\\\",\\\"languageCode\\\":\\\"zh_CN\\\",\\\"message\\\":\\\"{\\\\\\\"text\\\\\\\":\\\\\\\"Eva\\\\\\\",\\\\\\\"text1\\\\\\\":\\\\\\\"Shenzhen\\\\\\\",\\\\\\\"text2\\\\\\\":\\\\\\\"Support\\\\\\\"}\\\"}\""
                + "}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

        List<UnifiedMessage> thread = store.thread("chatapp:whatsapp:8613800000000");
        assertEquals("Hello Eva, address Shenzhen, contact Support", thread.get(0).text);
    }

    private static void rendersWebShellWithChineseCopyAndUnifiedSendActions() {
        String html = App.pageHtml();

        assertContains(html, "统一消息中心");
        assertContains(html, "发送邮件");
        assertContains(html, "发送 WhatsApp");
        assertContains(html, "企业微信 API 接入位已预留");
        assertNotContains(html, "缁熶竴");
        assertNotContains(html, "閭欢");
        assertNotContains(html, "宸插彂");
    }

    private static void chatAppTextMessagesUseMessageBodyAsContactPreview() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chat-preview-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(chatData.resolve("messages.jsonl"), "{"
                + "\"id\":\"chat-preview-1\","
                + "\"direction\":\"inbound\","
                + "\"timestamp\":\"2026-07-10T01:00:00Z\","
                + "\"from\":\"8613800000000\","
                + "\"to\":\"8613266259485\","
                + "\"text\":\"{\\\"text\\\":\\\"Actual WhatsApp body\\\"}\","
                + "\"raw\":\"{}\""
                + "}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

        List<UnifiedContact> contacts = store.contacts();
        assertEquals("Actual WhatsApp body", contacts.get(0).lastText);
    }

    private static UnifiedMessageStore testStore(Path dir, Path emailData, Path chatFile) {
        Config config = Config.forTests(dir, emailData, chatFile, dir.resolve("templates.json"));
        return new UnifiedMessageStore(config);
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "] but got [" + actual + "]");
        }
    }

    private static void assertContains(String value, String expected) {
        if (value == null || !value.contains(expected)) {
            throw new AssertionError("Expected [" + value + "] to contain [" + expected + "]");
        }
    }

    private static void assertNotContains(String value, String unexpected) {
        if (value != null && value.contains(unexpected)) {
            throw new AssertionError("Expected [" + value + "] not to contain [" + unexpected + "]");
        }
    }
}
