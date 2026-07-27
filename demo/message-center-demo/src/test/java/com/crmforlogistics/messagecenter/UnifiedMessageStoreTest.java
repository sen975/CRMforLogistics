package com.crmforlogistics.messagecenter;

import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import com.aliyun.sdk.service.cams20200606.models.SendChatappMessageRequest;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class UnifiedMessageStoreTest {
    public static void main(String[] args) throws Exception {
        mergesEmailAndChatAppContactPointsIntoOneTimeline();
        storesContactGroupProfileWithoutLosingMergedPoints();
        splitsMergedContactPointWithoutDeletingRawMessages();
        splitsLegacyEmailContactGroupWhenOnlyOnePointRemains();
        threadPageReturnsRecentTenMessagesAndCursor();
        databaseThreadPageUsesLimitPlusOneAndUuidCursor();
        threadPageRejectsNonEmptyCursorMissingRequiredFields();
        exposesWecomAdapterPlaceholderAsUnavailableChannel();
        chatAppStatusRecordsUpdateOriginalMessageInsteadOfCreatingMessages();
        chatAppMediaJsonMessagesRenderCaptionAndAttachment();
        chatAppMediaPlaceholderTextShowsCaptionWithoutImagePrefix();
        chatAppMediaMessagesExposeProxyMetadata();
        chatAppMediaMessagesExtractUrlFromNestedRawMessageJson();
        mediaGatewayBuildsFreshOssSignedUrlFromStoredObjectKey();
        mediaGatewayPrefersCamsPresignedUrlBeforeOssUrl();
        mediaGatewayNormalizesProtocolRelativePresignedUrl();
        mediaGatewaySkipsSlowCamsPresignAndFallsBackToFreshSignedOssUrl();
        mediaGatewayTriesFreshSignedOssUrlBeforeExpiredMediaUrlWhenCamsFails();
        mediaGatewaySummarizesOssXmlErrorCode();
        mediaGatewayReportsCamsAndFallbackDownloadFailures();
        mediaGatewayCachesDownloadedMediaLocally();
        rendersChatAppTemplateMessagesFromTemplateCache();
        chatAppTemplateMessagesUseTemplateRequestType();
        chatAppTemplateRequestsOmitMessageType();
        rendersWebShellWithChineseCopyAndUnifiedSendActions();
        rendersWebShellWithPagedThreadRequestContract();
        apiThreadsRouteReturnsPagedObjectAndParsesCursorLimit();
        rendersWebShellWithOlderThreadScrollLoader();
        chatAppTextMessagesUseMessageBodyAsContactPreview();
        emailInboxWriterStoresImapMessagesInLegacyInboxJsonlFormat();
        chatAppHistoryStoreDeduplicatesAndFeedsUnifiedTimeline();
        chatAppHistoryStoreRefreshesExpiredMediaUrlForExistingMessage();
        chatAppHistorySyncDefaultsToShortFirstRunWindow();
        chatAppHistorySyncUsesLatestLocalTimestampForIncrementalWindow();
        chatAppHistorySyncQueuesMediaPrecacheByDefaultWithoutBlockingMessageSync();
        chatAppHistorySyncPreCachesMediaWithoutBlockingMessageSync();
        chatAppHistorySyncDoesNotRetryUnchangedMediaByDefault();
        chatAppHistorySyncExtractsCamsUrlFieldForMediaCache();
    }

    private static void chatAppTemplateMessagesUseTemplateRequestType() {
        Config config = new Config(Map.of("CHATAPP_TYPE", "message"));

        assertEquals("template", ChatAppSender.templateRequestType(config));
    }

    private static void chatAppTemplateRequestsOmitMessageType() {
        Config config = new Config(Map.of(
                "CUST_SPACE_ID", "space-1",
                "CHATAPP_FROM", "8613000000000",
                "CHATAPP_TYPE", "message",
                "CHATAPP_MESSAGE_TYPE", "text",
                "CHATAPP_TEMPLATE_MESSAGE_TYPE", "text"
        ));

        SendChatappMessageRequest request = ChatAppSender.buildTemplateRequest(config, "8613111111111", "tpl-1",
                "shipping_notice", "zh_CN", Map.of("text", "Alex"), "task-1");

        assertEquals("template", request.getType());
        assertNull(request.getMessageType(), "template request must not send messageType");
        assertEquals("tpl-1", request.getTemplateCode());
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
        assertEquals("inbound", contacts.get(0).lastDirection);
        assertEquals("chatapp", contacts.get(0).lastChannel);

        List<UnifiedMessage> thread = store.thread("email:buyer@example.com");
        assertEquals("2", Integer.toString(thread.size()));
        assertEquals("email", thread.get(0).channel);
        assertEquals("chatapp", thread.get(1).channel);
        assertEquals("WhatsApp hello", thread.get(1).text);
    }

    private static void storesContactGroupProfileWithoutLosingMergedPoints() throws Exception {
        Path dir = Files.createTempDirectory("message-center-remark-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(emailData.resolve("inbox.jsonl"), "{\"id\":\"mail-1\",\"direction\":\"in\",\"contactEmail\":\"buyer@example.com\",\"contactName\":\"Buyer\",\"subject\":\"Need quote\",\"sentDate\":\"2026-07-10T01:00:00Z\",\"bodyText\":\"Body\"}\n", StandardCharsets.UTF_8);
        Files.writeString(chatData.resolve("messages.jsonl"), "{\"id\":\"chat-1\",\"direction\":\"inbound\",\"timestamp\":\"2026-07-10T01:05:00Z\",\"from\":\"8613800000000\",\"to\":\"8613266259485\",\"text\":\"hi\",\"raw\":\"{}\"}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));
        store.mergeContacts("email:buyer@example.com", "chatapp:whatsapp:8613800000000");
        store.updateContactProfile("email:buyer@example.com", "华南买家", List.of("VIP", "需跟进"));

        List<UnifiedContact> contacts = new UnifiedMessageStore(testConfig(dir, emailData, chatData.resolve("messages.jsonl"))).contacts();
        assertEquals("1", Integer.toString(contacts.size()));
        assertEquals("华南买家", contacts.get(0).remark);
        assertEquals("华南买家", contacts.get(0).displayName);
        assertEquals("2", Integer.toString(contacts.get(0).points.size()));
        assertEquals("VIP,需跟进", String.join(",", contacts.get(0).tags));
        assertEquals("email:buyer@example.com", contacts.get(0).points.get(0).id);
        assertEquals("chatapp:whatsapp:8613800000000", contacts.get(0).points.get(1).id);
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

    private static void splitsLegacyEmailContactGroupWhenOnlyOnePointRemains() throws Exception {
        Path dir = Files.createTempDirectory("message-center-legacy-email-split-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(emailData.resolve("inbox.jsonl"), ""
                + "{\"id\":\"mail-1\",\"direction\":\"in\",\"contactEmail\":\"a@example.com\",\"contactName\":\"A\",\"subject\":\"A\",\"sentDate\":\"2026-07-10T01:00:00Z\",\"bodyText\":\"A\"}\n"
                + "{\"id\":\"mail-2\",\"direction\":\"in\",\"contactEmail\":\"b@example.com\",\"contactName\":\"B\",\"subject\":\"B\",\"sentDate\":\"2026-07-10T01:01:00Z\",\"bodyText\":\"B\"}\n",
                StandardCharsets.UTF_8);
        Files.writeString(emailData.resolve("contact-groups.jsonl"),
                "{\"primaryEmail\":\"a@example.com\",\"emails\":[\"a@example.com\",\"b@example.com\"]}\n",
                StandardCharsets.UTF_8);
        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

        assertEquals("1", Integer.toString(store.contacts().size()));

        store.splitContact("email:a@example.com", "email:b@example.com");

        assertEquals("2", Integer.toString(store.contacts().size()));
        assertEquals("1", Integer.toString(store.contactGroup("email:a@example.com").size()));
        assertEquals("email:a@example.com", store.contactGroup("email:a@example.com").get(0));
        assertEquals("1", Integer.toString(store.contactGroup("email:b@example.com").size()));
        assertEquals("email:b@example.com", store.contactGroup("email:b@example.com").get(0));
    }

    private static void threadPageReturnsRecentTenMessagesAndCursor() throws Exception {
        Path dir = Files.createTempDirectory("message-center-thread-page-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        StringBuilder jsonl = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            jsonl.append("{")
                    .append("\"id\":\"chat-").append(i).append("\",")
                    .append("\"direction\":\"inbound\",")
                    .append("\"timestamp\":\"2026-07-10T01:")
                    .append(String.format("%02d", i)).append(":00Z\",")
                    .append("\"from\":\"8613800000000\",")
                    .append("\"to\":\"8613266259485\",")
                    .append("\"text\":\"message-").append(i).append("\",")
                    .append("\"raw\":\"{}\"")
                    .append("}\n");
        }
        Files.writeString(chatData.resolve("messages.jsonl"), jsonl.toString(), StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));
        UnifiedMessageStore.ThreadPage firstPage = store.threadPage("chatapp:whatsapp:8613800000000", "", 10);

        assertEquals(10, firstPage.items.size());
        assertEquals("message-16", firstPage.items.get(0).text);
        assertEquals("message-25", firstPage.items.get(firstPage.items.size() - 1).text);
        assertTrue(firstPage.nextCursor != null && !firstPage.nextCursor.isBlank(),
                "first page must expose nextCursor for older messages");

        UnifiedMessageStore.ThreadPage secondPage = store.threadPage(
                "chatapp:whatsapp:8613800000000", firstPage.nextCursor, 10);
        assertEquals(10, secondPage.items.size());
        assertEquals("message-6", secondPage.items.get(0).text);
        assertEquals("message-15", secondPage.items.get(secondPage.items.size() - 1).text);
        assertTrue(secondPage.nextCursor != null && !secondPage.nextCursor.isBlank(),
                "second page must expose nextCursor for the oldest remaining messages");

        UnifiedMessageStore.ThreadPage thirdPage = store.threadPage(
                "chatapp:whatsapp:8613800000000", secondPage.nextCursor, 10);
        assertEquals(5, thirdPage.items.size());
        assertEquals("message-1", thirdPage.items.get(0).text);
        assertEquals("message-5", thirdPage.items.get(thirdPage.items.size() - 1).text);
        assertNull(thirdPage.nextCursor, "oldest page must not expose nextCursor");
    }

    private static void databaseThreadPageUsesLimitPlusOneAndUuidCursor() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID contactId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID oldestId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID cursorId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID newestId = UUID.fromString("00000000-0000-0000-0000-000000000012");
        Instant cursorTimestamp = Instant.parse("2026-07-10T01:01:00Z");
        List<Integer> receivedLimits = new ArrayList<>();
        List<MessageCursor> receivedCursors = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        MessageRepository repository = new MessageRepository() {
            @Override
            public List<UnifiedMessage> unifiedTimeline(
                    UUID receivedUserId, UUID receivedContactId, MessageCursor cursor, int limit) {
                assertEquals(userId.toString(), receivedUserId.toString());
                assertEquals(contactId.toString(), receivedContactId.toString());
                receivedLimits.add(limit);
                receivedCursors.add(cursor);
                if (calls.getAndIncrement() == 0) {
                    return List.of(
                            threadMessage(oldestId, "2026-07-10T01:00:00Z", "oldest-extra"),
                            threadMessage(cursorId, cursorTimestamp.toString(), "page-one-first"),
                            threadMessage(newestId, cursorTimestamp.toString(), "page-one-last"));
                }
                return List.of(
                        threadMessage(oldestId, "2026-07-10T01:00:00Z", "page-two-first"),
                        threadMessage(UUID.fromString("00000000-0000-0000-0000-000000000009"),
                                "2026-07-10T01:00:00Z", "page-two-last"));
            }

            @Override public UUID getOrCreateConversation(UUID accountId, UUID identityId) { throw unsupported(); }
            @Override public MessageWriteResult insert(MessageDraft draft) { throw unsupported(); }
            @Override public void appendStatus(UUID messageId, MessageStatusEvent event) { throw unsupported(); }
            @Override public List<UnifiedMessage> thread(UUID uid, UUID conversationId, MessageCursor cursor, int limit) { throw unsupported(); }
            @Override public Optional<UnifiedMessage> findAuthorized(UUID uid, UUID messageId) { throw unsupported(); }
        };
        ContactRepository contacts = (ContactRepository) java.lang.reflect.Proxy.newProxyInstance(
                ContactRepository.class.getClassLoader(), new Class<?>[]{ContactRepository.class},
                (proxy, method, args) -> { throw unsupported(); });
        UnifiedMessageStore store = new UnifiedMessageStore(contacts, repository, userId);

        UnifiedMessageStore.ThreadPage firstPage = store.threadPage(contactId.toString(), "", 2);

        assertEquals(3, receivedLimits.get(0));
        assertNull(receivedCursors.get(0), "first database page must not have a repository cursor");
        assertEquals(2, firstPage.items.size());
        assertEquals(cursorId.toString(), firstPage.items.get(0).id);
        assertEquals(newestId.toString(), firstPage.items.get(firstPage.items.size() - 1).id);
        assertTrue(firstPage.nextCursor != null && !firstPage.nextCursor.isBlank(),
                "database page with an extra row must expose nextCursor");

        UnifiedMessageStore.ThreadPage secondPage = store.threadPage(contactId.toString(), firstPage.nextCursor, 2);

        assertEquals(3, receivedLimits.get(receivedLimits.size() - 1));
        assertEquals(cursorTimestamp.toString(), receivedCursors.get(receivedCursors.size() - 1).occurredAt().toString());
        assertEquals(cursorId.toString(), receivedCursors.get(receivedCursors.size() - 1).id().toString());
        assertEquals(2, secondPage.items.size());
        assertNull(secondPage.nextCursor, "final database page must not expose nextCursor");
    }

    private static void threadPageRejectsNonEmptyCursorMissingRequiredFields() throws Exception {
        Path dir = Files.createTempDirectory("message-center-invalid-thread-cursor-test");
        Path emailData = dir.resolve("email");
        Path chatFile = dir.resolve("chatapp/messages.jsonl");
        Files.createDirectories(emailData);
        Files.createDirectories(chatFile.getParent());
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"timestamp\":\"2026-07-10T01:00:00Z\"}".getBytes(StandardCharsets.UTF_8));

        try {
            testStore(dir, emailData, chatFile).threadPage("chatapp:whatsapp:8613800000000", cursor, 2);
            throw new AssertionError("non-empty cursor missing id must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("invalid thread cursor", expected.getMessage());
        }
    }

    private static UnifiedMessage threadMessage(UUID id, String timestamp, String text) {
        UnifiedMessage message = new UnifiedMessage();
        message.id = id.toString();
        message.timestamp = timestamp;
        message.text = text;
        return message;
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not used by thread page test");
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

    private static void chatAppMediaJsonMessagesRenderCaptionAndAttachment() throws Exception {
        Path dir = Files.createTempDirectory("message-center-media-json-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(chatData.resolve("messages.jsonl"), "{"
                + "\"id\":\"chat-media-1\","
                + "\"direction\":\"outbound\","
                + "\"timestamp\":\"2026-07-10T01:00:00Z\","
                + "\"from\":\"8613266259485\","
                + "\"to\":\"8613800000000\","
                + "\"text\":\"{\\\"caption\\\":\\\"这是我们的系统\\\",\\\"mediaType\\\":\\\"image\\\",\\\"url\\\":\\\"https://example.com/a.png\\\"}\","
                + "\"raw\":\"{}\""
                + "}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

        List<UnifiedMessage> thread = store.thread("chatapp:whatsapp:8613800000000");
        assertEquals("", thread.get(0).title);
        assertEquals("这是我们的系统", thread.get(0).text);
        assertEquals("image", thread.get(0).mediaType);
        assertEquals("https://example.com/a.png", thread.get(0).mediaUrl);
        assertNotContains(thread.get(0).text, "\"caption\"");
    }

    private static void chatAppMediaPlaceholderTextShowsCaptionWithoutImagePrefix() throws Exception {
        Path dir = Files.createTempDirectory("message-center-media-placeholder-caption-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(chatData.resolve("messages.jsonl"), "{"
                + "\"id\":\"chat-media-placeholder-caption\","
                + "\"direction\":\"outbound\","
                + "\"timestamp\":\"2026-07-10T01:00:00Z\","
                + "\"from\":\"8613266259485\","
                + "\"to\":\"8613800000000\","
                + "\"text\":\"[image] 这是我们的营业执照\","
                + "\"mediaType\":\"image\","
                + "\"mediaUrl\":\"https://oss.example.com/license.png\","
                + "\"raw\":\"{}\""
                + "}\n", StandardCharsets.UTF_8);

        UnifiedMessage message = testStore(dir, emailData, chatData.resolve("messages.jsonl"))
                .thread("chatapp:whatsapp:8613800000000").get(0);

        assertEquals("这是我们的营业执照", message.text);
        assertEquals("https://oss.example.com/license.png", message.mediaUrl);
    }

    private static void chatAppMediaMessagesExposeProxyMetadata() throws Exception {
        Path dir = Files.createTempDirectory("message-center-media-proxy-metadata-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(chatData.resolve("messages.jsonl"), "{"
                + "\"id\":\"chat-media-2\","
                + "\"direction\":\"inbound\","
                + "\"timestamp\":\"2026-07-10T01:00:00Z\","
                + "\"from\":\"8613800000000\","
                + "\"to\":\"8613266259485\","
                + "\"text\":\"{\\\"caption\\\":\\\"带附件\\\",\\\"mediaType\\\":\\\"image\\\",\\\"link\\\":\\\"https://oss.example.com/file.png\\\",\\\"objectKey\\\":\\\"cams/file.png\\\",\\\"mimeType\\\":\\\"image/png\\\",\\\"fileName\\\":\\\"file.png\\\"}\","
                + "\"mediaUrl\":\"https://oss.example.com/file.png\","
                + "\"objectKey\":\"cams/file.png\","
                + "\"mimeType\":\"image/png\","
                + "\"fileName\":\"file.png\","
                + "\"raw\":\"{\\\"objectKey\\\":\\\"cams/raw-file.png\\\",\\\"mimeType\\\":\\\"image/png\\\"}\""
                + "}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

        UnifiedMessage message = store.thread("chatapp:whatsapp:8613800000000").get(0);
        assertEquals("cams/file.png", message.objectKey);
        assertEquals("image/png", message.mimeType);
        assertEquals("file.png", message.fileName);
    }

    private static void chatAppMediaMessagesExtractUrlFromNestedRawMessageJson() throws Exception {
        Path dir = Files.createTempDirectory("message-center-media-nested-raw-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(chatData.resolve("messages.jsonl"), "{"
                + "\"id\":\"chat-media-nested-raw\","
                + "\"direction\":\"outbound\","
                + "\"timestamp\":\"2026-07-10T08:28:34Z\","
                + "\"from\":\"8613266259485\","
                + "\"to\":\"8613428277520\","
                + "\"text\":\"[image]\","
                + "\"mediaType\":\"image\","
                + "\"raw\":\"{\\\"messageTypeName\\\":\\\"image\\\",\\\"message\\\":\\\"{\\\\\\\"caption\\\\\\\":\\\\\\\"营业执照\\\\\\\",\\\\\\\"mediaType\\\\\\\":\\\\\\\"image\\\\\\\",\\\\\\\"url\\\\\\\":\\\\\\\"https://oss.example.com/nested.jpg\\\\\\\",\\\\\\\"mimeType\\\\\\\":\\\\\\\"image/jpeg\\\\\\\",\\\\\\\"fileName\\\\\\\":\\\\\\\"nested.jpg\\\\\\\"}\\\"}\""
                + "}\n", StandardCharsets.UTF_8);

        UnifiedMessage message = testStore(dir, emailData, chatData.resolve("messages.jsonl"))
                .thread("chatapp:whatsapp:8613428277520").get(0);

        assertEquals("营业执照", message.text);
        assertEquals("https://oss.example.com/nested.jpg", message.mediaUrl);
        assertEquals("image/jpeg", message.mimeType);
        assertEquals("nested.jpg", message.fileName);
    }

    private static void mediaGatewayBuildsFreshOssSignedUrlFromStoredObjectKey() throws Exception {
        UnifiedMessage message = new UnifiedMessage();
        message.mediaUrl = "https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/old/file.png"
                + "?OSSAccessKeyId=STS.expired&Expires=1800000000&Signature=old";
        message.objectKey = "cams/file.png";
        Config config = new Config(Map.of(
                "ALIYUN_ACCESS_KEY_ID", "akid",
                "ALIYUN_ACCESS_KEY_SECRET", "secret"
        ));

        String signed = MediaGateway.signedOssUrl(config, message, 1800000000L);

        assertContains(signed, "https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/cams/file.png?");
        assertContains(signed, "OSSAccessKeyId=akid");
        assertContains(signed, "Expires=1800000000");
        assertContains(signed, "Signature=uEvbSub8UDbDVmppyeQWAQp0xHA%3D");
        assertNotContains(signed, "STS.expired");
    }

    private static void mediaGatewayPrefersCamsPresignedUrlBeforeOssUrl() throws Exception {
        Path dir = Files.createTempDirectory("message-center-cams-presigned-media-test");
        AtomicReference<String> requestedFilePath = new AtomicReference<>("");
        AtomicReference<String> downloadedUrl = new AtomicReference<>("");
        UnifiedMessage message = new UnifiedMessage();
        message.id = "chatapp:cams-media-1";
        message.mediaType = "image";
        message.mediaUrl = "https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/expired.jpg?OSSAccessKeyId=STS.old";
        MediaGateway gateway = new MediaGateway(new Config(Map.of(
                "MEDIA_CACHE_DIR", dir.resolve("media-cache").toString()
        )), url -> {
            downloadedUrl.set(url);
            return new MediaGateway.MediaResponse("cached".getBytes(StandardCharsets.UTF_8), "image/jpeg", "fresh.jpg");
        }, filePath -> {
            requestedFilePath.set(filePath);
            return "https://cams.example.com/fresh-download.jpg";
        });

        gateway.fetch(message);

        assertEquals("100003592550/expired.jpg", requestedFilePath.get());
        assertEquals("https://cams.example.com/fresh-download.jpg", downloadedUrl.get());
    }

    private static void mediaGatewayNormalizesProtocolRelativePresignedUrl() throws Exception {
        Path dir = Files.createTempDirectory("message-center-cams-relative-url-test");
        AtomicReference<String> downloadedUrl = new AtomicReference<>("");
        UnifiedMessage message = new UnifiedMessage();
        message.id = "chatapp:cams-relative-media";
        message.mediaType = "image";
        message.mediaUrl = "https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/expired.jpg";
        MediaGateway gateway = new MediaGateway(new Config(Map.of(
                "MEDIA_CACHE_DIR", dir.resolve("media-cache").toString()
        )), url -> {
            downloadedUrl.set(url);
            return new MediaGateway.MediaResponse("cached".getBytes(StandardCharsets.UTF_8), "image/jpeg", "fresh.jpg");
        }, filePath -> "//bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/fresh.jpg");

        gateway.fetch(message);

        assertEquals("https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/fresh.jpg",
                downloadedUrl.get());
    }

    private static void mediaGatewayTriesFreshSignedOssUrlBeforeExpiredMediaUrlWhenCamsFails() throws Exception {
        Path dir = Files.createTempDirectory("message-center-signed-before-expired-media-test");
        List<String> attempts = new ArrayList<>();
        UnifiedMessage message = new UnifiedMessage();
        message.id = "chatapp:signed-before-expired";
        message.mediaType = "image";
        message.mediaUrl = "https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/expired.jpg"
                + "?OSSAccessKeyId=STS.old&Expires=1&Signature=old";
        MediaGateway gateway = new MediaGateway(new Config(Map.of(
                "MEDIA_CACHE_DIR", dir.resolve("media-cache").toString(),
                "ALIYUN_ACCESS_KEY_ID", "akid",
                "ALIYUN_ACCESS_KEY_SECRET", "secret"
        )), url -> {
            attempts.add(url);
            if (url.contains("OSSAccessKeyId=akid")) {
                return new MediaGateway.MediaResponse("fresh".getBytes(StandardCharsets.UTF_8), "image/jpeg", "fresh.jpg");
            }
            throw new MediaGateway.MediaUnavailableException("expired");
        }, filePath -> {
            throw new IOException("CAMS timeout");
        });

        gateway.fetch(message);

        assertEquals("1", Integer.toString(attempts.size()));
        assertContains(attempts.get(0), "OSSAccessKeyId=akid");
        assertNotContains(attempts.get(0), "OSSAccessKeyId=STS.old");
    }

    private static void mediaGatewaySkipsSlowCamsPresignAndFallsBackToFreshSignedOssUrl() throws Exception {
        Path dir = Files.createTempDirectory("message-center-slow-cams-media-test");
        AtomicReference<String> downloadedUrl = new AtomicReference<>("");
        UnifiedMessage message = new UnifiedMessage();
        message.id = "chatapp:slow-cams-media";
        message.mediaType = "image";
        message.mediaUrl = "https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/slow.jpg"
                + "?OSSAccessKeyId=STS.old&Expires=1&Signature=old";
        MediaGateway gateway = new MediaGateway(new Config(Map.of(
                "MEDIA_CACHE_DIR", dir.resolve("media-cache").toString(),
                "ALIYUN_ACCESS_KEY_ID", "akid",
                "ALIYUN_ACCESS_KEY_SECRET", "secret",
                "CHATAPP_MEDIA_PRESIGNED_TIMEOUT_SECONDS", "1"
        )), url -> {
            downloadedUrl.set(url);
            return new MediaGateway.MediaResponse("fresh".getBytes(StandardCharsets.UTF_8), "image/jpeg", "fresh.jpg");
        }, filePath -> {
            Thread.sleep(3000);
            return "https://cams.example.com/slow-download.jpg";
        });

        long started = System.nanoTime();
        gateway.fetch(message);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 2000, "slow CAMS presign blocked media fetch for " + elapsedMillis + "ms");
        assertContains(downloadedUrl.get(), "OSSAccessKeyId=akid");
    }

    private static void mediaGatewaySummarizesOssXmlErrorCode() {
        String message = MediaGateway.unavailableMessage(403,
                "<Error><Code>AccessDenied</Code><Message>Access denied.</Message><RequestId>abc</RequestId></Error>");

        assertContains(message, "OSS 返回 HTTP 403");
        assertContains(message, "AccessDenied");
        assertNotContains(message, "RequestId");
    }

    private static void mediaGatewayReportsCamsAndFallbackDownloadFailures() throws Exception {
        Path dir = Files.createTempDirectory("message-center-cams-failure-summary-test");
        UnifiedMessage message = new UnifiedMessage();
        message.id = "chatapp:cams-media-fail";
        message.mediaType = "image";
        message.mediaUrl = "https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/expired.jpg?OSSAccessKeyId=STS.old&Signature=secret";
        MediaGateway gateway = new MediaGateway(new Config(Map.of(
                "MEDIA_CACHE_DIR", dir.resolve("media-cache").toString()
        )), url -> {
            throw new MediaGateway.MediaUnavailableException("OSS 返回 HTTP 403");
        }, filePath -> {
            throw new IOException("No permission for filePath " + filePath);
        });

        try {
            gateway.fetch(message);
            throw new AssertionError("Expected media fetch to fail");
        } catch (MediaGateway.MediaUnavailableException ex) {
            assertContains(ex.getMessage(), "CAMS GeneratePresignedUrl 失败：No permission for filePath 100003592550/expired.jpg");
            assertContains(ex.getMessage(), "bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/expired.jpg");
            assertContains(ex.getMessage(), "OSS 返回 HTTP 403");
            assertNotContains(ex.getMessage(), "OSSAccessKeyId=STS.old");
            assertNotContains(ex.getMessage(), "Signature=secret");
        }
    }

    private static void mediaGatewayCachesDownloadedMediaLocally() throws Exception {
        Path dir = Files.createTempDirectory("message-center-media-cache-test");
        Path cacheDir = dir.resolve("media-cache");
        AtomicInteger hits = new AtomicInteger();
        UnifiedMessage message = new UnifiedMessage();
        message.id = "chatapp:media-cache-1";
        message.mediaType = "image";
        message.mimeType = "image/png";
        message.fileName = "photo.png";
        message.mediaUrl = "https://oss.example.com/photo.png";
        MediaGateway gateway = new MediaGateway(new Config(Map.of(
                "MEDIA_CACHE_DIR", cacheDir.toString(),
                "MEDIA_MAX_BYTES", "1024"
        )), url -> {
            hits.incrementAndGet();
            return new MediaGateway.MediaResponse("image-bytes".getBytes(StandardCharsets.UTF_8),
                    "image/png", "photo.png");
        });

        MediaGateway.MediaResponse first = gateway.fetch(message);
        MediaGateway.MediaResponse second = gateway.fetch(message);

        assertEquals("image-bytes", new String(first.bytes, StandardCharsets.UTF_8));
        assertEquals("image-bytes", new String(second.bytes, StandardCharsets.UTF_8));
        assertEquals("1", Integer.toString(hits.get()));
        try (Stream<Path> files = Files.list(cacheDir)) {
            assertEquals("1", Long.toString(files
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(".type"))
                    .count()));
        }
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
        assertEquals("", thread.get(0).title);
        assertEquals("Hello Eva, address Shenzhen, contact Support", thread.get(0).text);
    }

    private static void rendersWebShellWithChineseCopyAndUnifiedSendActions() {
        String html = App.pageHtml();

        assertContains(html, "统一消息中心");
        assertContains(html, "发送邮件");
        assertContains(html, "发送 WhatsApp");
        assertContains(html, "收取邮件");
        assertContains(html, "同步 WhatsApp");
        assertContains(html, "刷新 ${result.updated || 0}");
        assertContains(html, "附件 ${result.mediaCached || 0}");
        assertContains(html, "排队 ${result.mediaQueued || 0}");
        assertContains(html, "失败 ${result.mediaFailed || 0}");
        assertContains(html, "页 ${result.pages || 0}");
        assertContains(html, "耗时 ${formatDuration(result.durationMillis)}");
        assertContains(html, "function formatDuration");
        assertContains(html, "result.mediaFailures?.[0]?.reason");
        assertContains(html, "/api/sync/email");
        assertContains(html, "/api/sync/chatapp");
        assertContains(html, "workspace-topbar");
        assertContains(html, "contact-points-line");
        assertContains(html, "sync-actions");
        assertContains(html, "utility-actions");
        assertContains(html, "aria-label=\"刷新\"");
        assertContains(html, "detailToggleBtn");
        assertContains(html, "contactDetailPanel");
        assertContains(html, "account-list");
        assertContains(html, "account-actions");
        assertContains(html, "account-split-button");
        assertContains(html, "data-split-account");
        assertContains(html, "tool-icon split");
        assertContains(html, "/api/contact-groups/split");
        assertContains(html, "profile-readonly");
        assertContains(html, "profile-readonly-title\">联系人资料");
        assertContains(html, "readonly-row");
        assertContains(html, "profile-tags-readonly");
        assertContains(html, "thread-title-row");
        assertContains(html, "threadTitleText");
        assertContains(html, "editProfileBtn");
        assertContains(html, "openProfileModal");
        assertContains(html, "profileModal");
        assertContains(html, "profileNicknameInput");
        assertContains(html, "profileTagsInput");
        assertContains(html, "profileSaveBtn");
        assertContains(html, "accountSelectHtml");
        assertContains(html, "bindAccountSelect");
        assertContains(html, "data-account-select");
        assertContains(html, "unreadByContact");
        assertContains(html, "contact-unread-dot");
        assertContains(html, "contact-unread-count");
        assertContains(html, "/api/media?id=");
        assertContains(html, "mediaPreviewHtml(m)");
        assertContains(html, "msg-media-preview");
        assertContains(html, "msg-media-loading");
        assertContains(html, "正在拉取图片");
        assertContains(html, "mediaLoaded(this)");
        assertContains(html, "mediaFailed(this)");
        assertContains(html, "previewImageModal");
        assertContains(html, "previewImageEl");
        assertContains(html, "openImagePreview(event");
        assertContains(html, "closeImagePreview");
        assertContains(html, "data-preview-image");
        assertContains(html, "object-fit:cover");
        assertContains(html, "cursor:zoom-in");
        assertContains(html, "image-preview-backdrop");
        assertContains(html, "image-preview-frame");
        assertContains(html, "msg-media-unavailable");
        assertContains(html, "附件暂不可用");
        assertContains(html, "data-open-media");
        assertContains(html, "openAttachment");
        assertContains(html, "URL.createObjectURL");
        assertContains(html, "附件打开失败");
        assertContains(html, "media-open-link");
        assertContains(html, "data-unread");
        assertContains(html, "reconcileUnreadContacts");
        assertContains(html, "isUnreadSource");
        assertContains(html, "clearContactUnread");
        assertContains(html, "c.id !== state.selectedPointId");
        assertContains(html, "aria-label=\"有未读消息\"");
        assertContains(html, "state.selectedMessageId || profileModalOpen() || detailEditing()");
        assertContains(html, "async function refreshSelectedContactViews");
        assertContains(html, "await refreshSelectedContactViews(false);");
        assertContains(html, "toast('联系人已合并')");
        assertContains(html, "scrollbar-color:transparent transparent");
        assertContains(html, "scrollbar-gutter:stable");
        assertContains(html, "scrollbar-width:thin");
        assertContains(html, "::-webkit-scrollbar { width:6px; height:6px; }");
        assertContains(html, "::-webkit-scrollbar-thumb { background:transparent");
        assertContains(html, ".contact-list.scrolling");
        assertContains(html, ".thread.scrolling");
        assertContains(html, "bindScrollSurfaces");
        assertContains(html, "markScrollSurfaceScrolling");
        assertContains(html, "sendPointForChannel");
        assertContains(html, "selectedPointByChannel");
        assertContains(html, "profileDirty");
        assertContains(html, "markProfileDirty");
        assertContains(html, "profileSaveState");
        assertContains(html, "/api/contact-groups/profile");
        assertContains(html, "detail-collapsed");
        assertContains(html, "transition:grid-template-columns");
        assertContains(html, "contactsRenderKey");
        assertContains(html, "threadRenderKeyByContact");
        assertContains(html, "if (silent && key === state.contactsRenderKey)");
        assertContains(html, "if (keepScroll && key === state.threadRenderKeyByContact[id])");
        assertContains(html, "if (silent && isUserScrolling())");
        assertContains(html, "state.pendingSilentRefresh = true");
        assertContains(html, "finishScrollSurfaceScrolling");
        assertContains(html, "message-row");
        assertContains(html, "msg-avatar");
        assertContains(html, "avatar-icon");
        assertContains(html, "align-items:start");
        assertContains(html, ".msg { width:fit-content; max-width:100%; min-width:0;");
        assertContains(html, ".msg.has-media { width:min(320px, 100%); }");
        assertContains(html, "hasMedia(m) ? 'has-media' : ''");
        assertContains(html, "msg-meta-line");
        assertContains(html, "status-icon");
        assertContains(html, "composer compact");
        assertContains(html, "height:260px");
        assertContains(html, "composer-tabs");
        assertContains(html, "aspect-ratio:var(--media-ratio, 4 / 3)");
        assertContains(html, "msg-media media-visual");
        assertContains(html, "detailEditing()");
        assertContains(html, "composer-editor");
        assertContains(html, "composer-toolbar");
        assertContains(html, "tool-icon");
        assertTrue(emojiSetSize(html) >= 48, "emoji picker should expose a practical emoji set");
        assertContains(html, "'😂'");
        assertContains(html, "'👌'");
        assertContains(html, "'🎉'");
        assertContains(html, "'🧾'");
        assertContains(html, "message-detail-standalone");
        assertContains(html, "$('detail').innerHTML = `<div class=\"message-detail-panel message-detail-standalone\"");
        assertContains(html, ".contact { position:relative; display:grid; grid-template-columns:38px 1fr; gap:9px; padding:8px 12px; cursor:pointer; min-height:58px;");
        assertContains(html, ".recipient-control { display:block; }");
        assertContains(html, "appearance:none");
        assertNotContains(html, ".contact { display:grid; grid-template-columns:42px 1fr; gap:10px; padding:12px; border-bottom:1px solid #edf0f5; cursor:pointer; }");
        assertNotContains(html, "data-account-input");
        assertNotContains(html, "grid-template-columns:minmax(118px, .42fr) minmax(0, 1fr)");
        assertNotContains(html, "const target = $('messageDetailPanel')");
        assertNotContains(html, "点击一条消息查看完整内容");
        assertNotContains(html, "<div class=\"brand\">联系人资料</div>");
        assertNotContains(html, "账号、昵称和标签");
        assertNotContains(html, "点击联系人查看融合账号");
        assertNotContains(html, "data-use-account");
        assertNotContains(html, "selectSendPoint");
        assertNotContains(html, "当前发送");
        assertNotContains(html, "用于发送");
        assertNotContains(html, "mode-tabs");
        assertNotContains(html, "<input id=\"nicknameInput\"");
        assertNotContains(html, "<input id=\"tagsInput\"");
        assertNotContains(html, "id=\"saveProfileBtn\"");
        assertNotContains(html, "确认保存");
        assertNotContains(html, ".msg { width:100%;");
        assertNotContains(html, "href=\"${esc(m.mediaUrl)}\"");
        assertNotContains(html, "target=\"_blank\" rel=\"noreferrer\">打开附件</a>");
        assertNotContains(html, "::-webkit-scrollbar { width:0; height:0; }");
        assertContains(html, "企业微信 API 接入位已预留");
        assertNotContains(html, "缁熶竴");
        assertNotContains(html, "閭欢");
        assertNotContains(html, "宸插彂");
    }

    private static void rendersWebShellWithPagedThreadRequestContract() {
        String html = App.pageHtml();

        assertContains(html, "const THREAD_PAGE_SIZE = 10;");
        assertContains(html, "const page = await api(threadPageUrl(id));");
        assertContains(html, "const messages = page.items || [];");
        assertContains(html, "nextCursor: page.nextCursor || null");
        assertContains(html, "function threadPageUrl(id, cursor = '')");
        assertContains(html, "'/api/threads?contactPointId=' + encodeURIComponent(id) + '&limit=' + THREAD_PAGE_SIZE");
    }

    private static void apiThreadsRouteReturnsPagedObjectAndParsesCursorLimit() throws Exception {
        Path dir = Files.createTempDirectory("message-center-thread-route-test");
        Path emailData = dir.resolve("email");
        Path chatFile = dir.resolve("chatapp/messages.jsonl");
        Files.createDirectories(emailData);
        Files.createDirectories(chatFile.getParent());
        Config config = testConfig(dir, emailData, chatFile);
        RecordingThreadPageStore store = new RecordingThreadPageStore(config);
        FakeHttpExchange exchange = new FakeHttpExchange(
                "GET", "/api/threads?contactPointId=contact-1&limit=12&cursor=cursor-1");

        invokeRoute(exchange, config, store);

        assertEquals(200, exchange.responseCode);
        assertEquals("contact-1", store.contactPointId);
        assertEquals("cursor-1", store.cursor);
        assertEquals(12, store.limit);
        assertContains(exchange.responseText(), "\"items\"");
        assertContains(exchange.responseText(), "\"nextCursor\": \"older-cursor\"");
        assertContains(exchange.responseText(), "\"text\": \"hello\"");

        FakeHttpExchange invalidLimit = new FakeHttpExchange(
                "GET", "/api/threads?contactPointId=contact-2&limit=bad");
        invokeRoute(invalidLimit, config, store);

        assertEquals(200, invalidLimit.responseCode);
        assertEquals("contact-2", store.contactPointId);
        assertEquals("", store.cursor);
        assertEquals(10, store.limit);
    }

    private static void rendersWebShellWithOlderThreadScrollLoader() {
        String html = App.pageHtml();

        assertContains(html, "loadOlderThreadMessages();");
        assertContains(html, "async function loadOlderThreadMessages()");
        assertContains(html, "if (!page || !page.nextCursor || page.isLoadingOlder) return;");
        assertContains(html, "const oldScrollHeight = threadEl.scrollHeight;");
        assertContains(html, "page.items = mergeThreadMessages([...(older.items || []), ...page.items]);");
        assertContains(html, "threadEl.scrollTop = threadEl.scrollHeight - oldScrollHeight + oldScrollTop;");
        assertContains(html, "if (state.selectedPointId !== id || state.threadPages[id] !== page) return;");
        assertContains(html, "const contactMessageCount = Number(contact?.messageCount || 0);");
        assertContains(html, "const previousContactMessageCount = existing ? existing.contactMessageCount || 0 : 0;");
        assertContains(html, "contactMessageCount,");
        assertContains(html, "if (existing && keepScroll && existing.nextCursor && previousContactMessageCount === contactMessageCount) state.threadPages[id].nextCursor = existing.nextCursor;");
        assertContains(html, "if (existing && keepScroll && !existing.nextCursor && contact && Number(contact.messageCount || 0) <= merged.length) state.threadPages[id].nextCursor = null;");
        assertContains(html, "function mergeThreadMessages(messages)");
        assertContains(html, "const seen = new Set();");
        assertContains(html, "return merged;");
        assertNotContains(html, ".sort((a, b) =>");
        assertContains(html, "renderThreadMessages(contact, page.items);");
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

    private static void emailInboxWriterStoresImapMessagesInLegacyInboxJsonlFormat() throws Exception {
        Path dir = Files.createTempDirectory("message-center-email-sync-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"));
        EmailInboxWriter writer = new EmailInboxWriter(config);

        MimeMessage incoming = mailMessage(
                "Buyer",
                "buyer@example.com",
                "seller@example.com",
                "Need quote",
                "Please send price.",
                "<mail-1@example.com>",
                "2026-07-10T01:00:00Z"
        );
        MimeMessage sent = mailMessage(
                "Demo Seller",
                "seller@example.com",
                "Buyer <buyer@example.com>",
                "Quote sent",
                "Here is the quote.",
                "<mail-2@example.com>",
                "2026-07-10T01:05:00Z"
        );

        assertEquals("true", Boolean.toString(writer.append(incoming, "in")));
        assertEquals("false", Boolean.toString(writer.append(incoming, "in")));
        assertEquals("true", Boolean.toString(writer.append(sent, "out")));

        List<String> lines = Files.readAllLines(emailData.resolve("inbox.jsonl"), StandardCharsets.UTF_8);
        assertEquals("2", Integer.toString(lines.size()));
        assertContains(lines.get(0), "\"direction\":\"in\"");
        assertContains(lines.get(0), "\"contactEmail\":\"buyer@example.com\"");
        assertContains(lines.get(0), "\"bodyText\":\"Please send price.\"");
        assertContains(lines.get(1), "\"direction\":\"out\"");
        assertContains(lines.get(1), "\"to\":\"Buyer <buyer@example.com>\"");
    }

    private static void chatAppHistoryStoreDeduplicatesAndFeedsUnifiedTimeline() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-sync-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"));
        ChatAppHistoryStore historyStore = new ChatAppHistoryStore(config);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("mediaType", "text");

        assertEquals("true", Boolean.toString(historyStore.append("chat-sync-1", "inbound",
                "8613800000000", "8613266259485", "Synced hello", "Read", "{}", extra)));
        assertEquals("false", Boolean.toString(historyStore.append("chat-sync-1", "inbound",
                "8613800000000", "8613266259485", "Synced hello", "Read", "{}", extra)));

        List<String> lines = Files.readAllLines(chatData.resolve("messages.jsonl"), StandardCharsets.UTF_8);
        assertEquals("1", Integer.toString(lines.size()));
        List<UnifiedMessage> thread = new UnifiedMessageStore(config).thread("chatapp:whatsapp:8613800000000");
        assertEquals("1", Integer.toString(thread.size()));
        assertEquals("Synced hello", thread.get(0).text);
        assertEquals("Read", thread.get(0).status);
    }

    private static void chatAppHistoryStoreRefreshesExpiredMediaUrlForExistingMessage() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-media-refresh-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"));
        ChatAppHistoryStore historyStore = new ChatAppHistoryStore(config);
        Map<String, String> oldExtra = new LinkedHashMap<>();
        oldExtra.put("mediaType", "image");
        oldExtra.put("mediaUrl", "https://oss.example.com/expired.png");
        oldExtra.put("fileName", "photo.png");
        Map<String, String> newExtra = new LinkedHashMap<>();
        newExtra.put("mediaType", "image");
        newExtra.put("mediaUrl", "https://oss.example.com/fresh.png");
        newExtra.put("fileName", "photo.png");

        assertEquals("true", Boolean.toString(historyStore.append("chat-media-refresh-1", "inbound",
                "8613800000000", "8613266259485", "[image] photo.png", "Read", "{\"link\":\"https://oss.example.com/expired.png\"}", oldExtra)));
        assertEquals("true", Boolean.toString(historyStore.append("chat-media-refresh-1", "inbound",
                "8613800000000", "8613266259485", "[image] photo.png", "Read", "{\"link\":\"https://oss.example.com/fresh.png\"}", newExtra)));

        List<String> lines = Files.readAllLines(chatData.resolve("messages.jsonl"), StandardCharsets.UTF_8);
        assertEquals("1", Integer.toString(lines.size()));
        assertContains(lines.get(0), "https://oss.example.com/fresh.png");
        assertNotContains(lines.get(0), "https://oss.example.com/expired.png");
        List<UnifiedMessage> thread = new UnifiedMessageStore(config).thread("chatapp:whatsapp:8613800000000");
        assertEquals("https://oss.example.com/fresh.png", thread.get(0).mediaUrl);
    }

    private static void chatAppHistorySyncUsesLatestLocalTimestampForIncrementalWindow() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-sync-incremental-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"), Map.of(
                "SYNC_INCREMENTAL", "true",
                "SYNC_OVERLAP_MINUTES", "30",
                "SYNC_LOOKBACK_DAYS", "10"
        ));
        ChatAppHistoryStore historyStore = new ChatAppHistoryStore(config);
        historyStore.append("old-message", "inbound", "8613800000000", "8613266259485",
                "old", "Read", "2026-07-01T00:00:00Z", "{}", Map.of());
        historyStore.append("latest-message", "inbound", "8613800000000", "8613266259485",
                "latest", "Read", "2026-07-10T08:00:00Z", "{}", Map.of());
        ChatAppHistorySyncService service = new ChatAppHistorySyncService(config, historyStore,
                new TemplateStore(config.chatappTemplateFile()), message -> {});
        java.lang.reflect.Method syncStartTime = ChatAppHistorySyncService.class.getDeclaredMethod("syncStartTime");
        syncStartTime.setAccessible(true);

        long startTime = (Long) syncStartTime.invoke(service);

        assertEquals("2026-07-10T07:30:00Z", Instant.ofEpochMilli(startTime).toString());
    }

    private static void chatAppHistorySyncDefaultsToShortFirstRunWindow() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-sync-default-window-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"));
        ChatAppHistorySyncService service = new ChatAppHistorySyncService(config, new ChatAppHistoryStore(config),
                new TemplateStore(config.chatappTemplateFile()), message -> {});
        java.lang.reflect.Method syncStartTime = ChatAppHistorySyncService.class.getDeclaredMethod("syncStartTime");
        syncStartTime.setAccessible(true);

        long earliest = Instant.now().minus(Duration.ofHours(25)).toEpochMilli();
        long latest = Instant.now().minus(Duration.ofHours(23)).toEpochMilli();
        long startTime = (Long) syncStartTime.invoke(service);

        assertTrue(startTime >= earliest && startTime <= latest,
                "default first sync should look back about 1 day, got " + Instant.ofEpochMilli(startTime));
    }

    private static void chatAppHistorySyncPreCachesMediaWithoutBlockingMessageSync() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-sync-media-cache-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"), Map.of(
                "CHATAPP_MEDIA_PRECACHE_MODE", "inline"
        ));
        AtomicInteger cached = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ChatAppHistorySyncService service = new ChatAppHistorySyncService(config, new ChatAppHistoryStore(config),
                new TemplateStore(config.chatappTemplateFile()), message -> {
                    if (message.mediaUrl.contains("fail")) {
                        failed.incrementAndGet();
                        throw new IOException("download denied");
                    }
                    cached.incrementAndGet();
                });
        SyncResult result = new SyncResult("chatapp");

        service.appendAndPrecache(projectedMedia("chat-media-cache-ok", "https://oss.example.com/ok.png"), result);
        service.appendAndPrecache(projectedMedia("chat-media-cache-fail", "https://oss.example.com/fail.png"), result);

        assertEquals("2", Integer.toString(result.saved));
        assertEquals("1", Integer.toString(result.mediaCached));
        assertEquals("1", Integer.toString(result.mediaFailed));
        assertEquals("1", Integer.toString(cached.get()));
        assertEquals("1", Integer.toString(failed.get()));
        @SuppressWarnings("unchecked")
        List<Object> failures = (List<Object>) SyncResult.class.getField("mediaFailures").get(result);
        assertEquals("1", Integer.toString(failures.size()));
        assertContains(failures.get(0).toString(), "chat-media-cache-fail");
        assertContains(failures.get(0).toString(), "download denied");
        List<String> lines = Files.readAllLines(chatData.resolve("messages.jsonl"), StandardCharsets.UTF_8);
        assertEquals("2", Integer.toString(lines.size()));
        assertContains(lines.get(0), "https://oss.example.com/ok.png");
        assertContains(lines.get(1), "https://oss.example.com/fail.png");
    }

    private static void chatAppHistorySyncQueuesMediaPrecacheByDefaultWithoutBlockingMessageSync() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-sync-media-background-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"));
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger cacheAttempts = new AtomicInteger();
        ChatAppHistorySyncService service = new ChatAppHistorySyncService(config, new ChatAppHistoryStore(config),
                new TemplateStore(config.chatappTemplateFile()), message -> {
                    release.await(2, TimeUnit.SECONDS);
                    cacheAttempts.incrementAndGet();
                });
        SyncResult result = new SyncResult("chatapp");

        long started = System.nanoTime();
        service.appendAndPrecache(projectedMedia("chat-media-cache-background", "https://oss.example.com/background.png"), result);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        release.countDown();

        assertTrue(elapsedMillis < 500, "media cache blocked sync for " + elapsedMillis + "ms");
        assertEquals("1", Integer.toString(result.saved));
        assertEquals("1", Integer.toString(result.mediaQueued));
        assertEquals("0", Integer.toString(result.mediaCached));
    }

    private static void chatAppHistorySyncDoesNotRetryUnchangedMediaByDefault() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-sync-media-skip-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"), Map.of(
                "CHATAPP_MEDIA_PRECACHE_MODE", "inline"
        ));
        AtomicInteger cacheAttempts = new AtomicInteger();
        ChatAppHistorySyncService service = new ChatAppHistorySyncService(config, new ChatAppHistoryStore(config),
                new TemplateStore(config.chatappTemplateFile()), message -> cacheAttempts.incrementAndGet());
        SyncResult result = new SyncResult("chatapp");

        service.appendAndPrecache(projectedMedia("chat-media-cache-stable", "https://oss.example.com/stable.png"), result);
        service.appendAndPrecache(projectedMedia("chat-media-cache-stable", "https://oss.example.com/stable.png"), result);

        assertEquals("1", Integer.toString(result.saved));
        assertEquals("1", Integer.toString(result.skipped));
        assertEquals("1", Integer.toString(result.mediaCached));
        assertEquals("1", Integer.toString(cacheAttempts.get()));
    }

    private static void chatAppHistorySyncExtractsCamsUrlFieldForMediaCache() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-sync-cams-url-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"), Map.of(
                "CHATAPP_MEDIA_PRECACHE_MODE", "inline"
        ));
        AtomicReference<String> cachedUrl = new AtomicReference<>("");
        ChatAppHistorySyncService service = new ChatAppHistorySyncService(config, new ChatAppHistoryStore(config),
                new TemplateStore(config.chatappTemplateFile()), message -> cachedUrl.set(message.mediaUrl));
        ListChatappMessageResponseBody.Data row = ListChatappMessageResponseBody.Data.builder()
                .messageId("chat-cams-url-1")
                .businessNumber("8613266259485")
                .userNumber("8613428277520")
                .eventAction("DOWN")
                .messageSource("api")
                .messageTypeName("image")
                .messageStatusName("Success")
                .sendTime("2026-07-10T08:28:34.836+00:00")
                .message("{\"caption\":\"营业执照\",\"mediaType\":\"image\",\"url\":\"https://oss.example.com/fresh.png\",\"fileName\":\"fresh.png\",\"mimeType\":\"image/png\"}")
                .build();
        java.lang.reflect.Method project = ChatAppHistorySyncService.class.getDeclaredMethod("project", ListChatappMessageResponseBody.Data.class);
        project.setAccessible(true);
        ChatAppHistorySyncService.ProjectedChatAppMessage projected =
                (ChatAppHistorySyncService.ProjectedChatAppMessage) project.invoke(service, row);
        SyncResult result = new SyncResult("chatapp");

        service.appendAndPrecache(projected, result);

        assertEquals("1", Integer.toString(result.mediaCached));
        assertEquals("https://oss.example.com/fresh.png", cachedUrl.get());
        List<String> lines = Files.readAllLines(chatData.resolve("messages.jsonl"), StandardCharsets.UTF_8);
        assertContains(lines.get(0), "\"mediaUrl\":\"https://oss.example.com/fresh.png\"");
        assertContains(lines.get(0), "\"mimeType\":\"image/png\"");
        assertContains(lines.get(0), "\"fileName\":\"fresh.png\"");
    }

    private static ChatAppHistorySyncService.ProjectedChatAppMessage projectedMedia(String id, String mediaUrl) {
        ChatAppHistorySyncService.ProjectedChatAppMessage message = new ChatAppHistorySyncService.ProjectedChatAppMessage();
        message.id = id;
        message.direction = "outbound";
        message.from = "8613266259485";
        message.to = "8613428277520";
        message.text = "[image]";
        message.status = "Success";
        message.timestamp = "2026-07-10T08:28:34Z";
        message.raw = "{\"messageTypeName\":\"image\"}";
        message.extra.put("mediaType", "image");
        message.extra.put("mediaUrl", mediaUrl);
        message.extra.put("mimeType", "image/png");
        message.extra.put("fileName", id + ".png");
        return message;
    }

    private static UnifiedMessageStore testStore(Path dir, Path emailData, Path chatFile) {
        return new UnifiedMessageStore(testConfig(dir, emailData, chatFile));
    }

    private static Config testConfig(Path dir, Path emailData, Path chatFile) {
        return testConfig(dir, emailData, chatFile, Map.of());
    }

    private static Config testConfig(Path dir, Path emailData, Path chatFile, Map<String, String> overrides) {
        Map<String, String> values = new HashMap<>();
        values.put("DATA_DIR", dir.toString());
        values.put("EMAIL_DATA_DIR", emailData.toString());
        values.put("CHATAPP_DATA_FILE", chatFile.toString());
        values.put("CHATAPP_TEMPLATE_FILE", dir.resolve("templates.json").toString());
        values.put("CONTACT_GROUP_FILE", dir.resolve("contact-groups.jsonl").toString());
        values.put("EMAIL_CONTACT_GROUP_FILE", emailData.resolve("contact-groups.jsonl").toString());
        values.putAll(overrides);
        return new Config(values);
    }

    private static MimeMessage mailMessage(
            String fromName,
            String fromEmail,
            String to,
            String subject,
            String body,
            String messageId,
            String sentAt
    ) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(fromEmail, fromName, "UTF-8"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
        message.setSubject(subject, "UTF-8");
        message.setText(body, "UTF-8");
        message.setSentDate(Date.from(Instant.parse(sentAt)));
        message.saveChanges();
        message.setHeader("Message-ID", messageId);
        return message;
    }

    private static void invokeRoute(FakeHttpExchange exchange, Config config, UnifiedMessageStore store) throws Exception {
        java.lang.reflect.Method route = Stream.of(App.class.getDeclaredMethods())
                .filter(method -> "route".equals(method.getName()))
                .findFirst()
                .orElseThrow();
        route.setAccessible(true);
        Object[] args = new Object[route.getParameterCount()];
        args[0] = exchange;
        args[1] = config;
        args[2] = store;
        try {
            route.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) throw checked;
            if (cause instanceof Error error) throw error;
            throw exception;
        }
    }

    private static class RecordingThreadPageStore extends UnifiedMessageStore {
        String contactPointId;
        String cursor;
        int limit;

        RecordingThreadPageStore(Config config) {
            super(config);
        }

        @Override
        public ThreadPage threadPage(String contactPointId, String cursor, int limit) {
            this.contactPointId = contactPointId;
            this.cursor = cursor;
            this.limit = limit;
            UnifiedMessage message = new UnifiedMessage();
            message.id = "message-1";
            message.text = "hello";
            return new ThreadPage(List.of(message), "older-cursor");
        }
    }

    private static class FakeHttpExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayInputStream requestBody = new ByteArrayInputStream(new byte[0]);
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        int responseCode;

        FakeHttpExchange(String method, String uri) {
            this.method = method;
            this.uri = URI.create(uri);
        }

        String responseText() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return requestBody; }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int responseCode, long responseLength) { this.responseCode = responseCode; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(0); }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress(0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream input, OutputStream output) {}
        @Override public HttpPrincipal getPrincipal() { return null; }
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "] but got [" + actual + "]");
        }
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
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

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static int emojiSetSize(String html) {
        String marker = "const emojiSet = [";
        int start = html.indexOf(marker);
        if (start < 0) {
            return 0;
        }
        int end = html.indexOf("];", start);
        if (end < 0) {
            return 0;
        }
        String body = html.substring(start + marker.length(), end).trim();
        if (body.isEmpty()) {
            return 0;
        }
        return body.split(",").length;
    }

    private static void assertNull(Object value, String message) {
        if (value != null) {
            throw new AssertionError(message + ": " + value);
        }
    }
}
