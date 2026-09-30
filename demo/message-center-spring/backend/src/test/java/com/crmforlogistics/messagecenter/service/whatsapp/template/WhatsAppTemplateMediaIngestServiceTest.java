package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService.MediaAssetView;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService.UploadResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.MediaAssetStatus;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「用户写下的地址 → 素材库里的一行」这一段的粘合。
 *
 * <p>钉两件事，它们的共同点是<b>错了都不会报错</b>：归属在抓取之前就判过
 * （否则一个登录用户能让服务端去访问任意账号名下的地址），
 * 以及幂等键取自内容而不是地址（取错了，同一张图会被存成好几条，或换图时撞冲突）。
 */
class WhatsAppTemplateMediaIngestServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final String URL = "https://cdn.example.com/quote.png";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3};

    private final ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
    private final TemplateMediaLinkFetcher fetcher = mock(TemplateMediaLinkFetcher.class);
    private final WhatsAppTemplateMediaUploadService uploads = mock(WhatsAppTemplateMediaUploadService.class);
    private final WhatsAppTemplateMediaIngestService ingest =
            new WhatsAppTemplateMediaIngestService(accounts, fetcher, uploads);

    /**
     * 账号不是自己的 ⇒ 在<b>任何网络动作之前</b>就拒。
     *
     * <p>顺序才是这条用例的重点：先抓后判的话，一个登录用户就能借服务端去访问
     * 任意地址（哪怕最后素材不落库，请求已经发出去了 —— SSRF 的危害在于「访问」本身）。
     */
    @Test
    void anAccountThatIsNotYoursIsRefusedBeforeAnyFetch() {
        assertThatThrownBy(() -> ingest.ingestFromLink(USER, ACCOUNT, URL, "t-1"))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting(error -> ((WhatsAppTemplateException) error).code())
                .isEqualTo("WHATSAPP_ACCOUNT_NOT_FOUND");

        verify(fetcher, never()).fetch(anyString());
        verify(uploads, never()).upload(any(), any(), any(), anyLong(), anyString(),
                anyString(), anyString(), any(), anyString());
    }

    @Test
    void theFetchedBytesAreHandedToTheUploadPathAsAnImage() {
        when(accounts.findByIdAndOwner(ACCOUNT, USER)).thenReturn(account());
        when(fetcher.fetch(URL)).thenReturn(
                new TemplateMediaLinkFetcher.Fetched(PNG, "image/png", "quote.png"));
        when(uploads.upload(any(), any(), any(), anyLong(), anyString(),
                anyString(), anyString(), any(), anyString())).thenReturn(uploadResult());

        ingest.ingestFromLink(USER, ACCOUNT, URL, "t-1");

        verify(uploads).upload(eq(ACCOUNT), eq(HeaderFormat.IMAGE), any(InputStream.class),
                eq((long) PNG.length), eq("quote.png"), eq("image/png"),
                eq(WhatsAppTemplateMediaIngestService.requestIdOf(PNG)), eq(USER), eq("t-1"));
    }

    /**
     * 幂等键取自<b>内容</b>。
     *
     * <p>用地址当键的话，「地址没变而图变了」（CDN 换图、用户重传）会撞上
     * 「键已绑定到不同内容」的冲突 —— 而那条报错对用户毫无意义：他确实是想存现在这张图。
     */
    @Test
    void theIdempotencyKeyFollowsTheBytesNotTheAddress() {
        byte[] other = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 9, 9, 9};

        assertThat(WhatsAppTemplateMediaIngestService.requestIdOf(PNG))
                .isEqualTo(WhatsAppTemplateMediaIngestService.requestIdOf(PNG.clone()))
                .isNotEqualTo(WhatsAppTemplateMediaIngestService.requestIdOf(other));
        // 形状要满足上传服务对 clientRequestId 的约束 [A-Za-z0-9._~:-]{1,255}。
        assertThat(WhatsAppTemplateMediaIngestService.requestIdOf(PNG)).matches("link-[0-9a-f]{32}");
    }

    private static ChannelAccountEntity account() {
        ChannelAccountEntity entity = new ChannelAccountEntity();
        entity.setId(ACCOUNT);
        return entity;
    }

    private static UploadResult uploadResult() {
        return new UploadResult(new MediaAssetView(UUID.randomUUID(), "link-x", HeaderFormat.IMAGE,
                "image/png", PNG.length, "sha", "https://provider.example/x.png",
                MediaAssetStatus.UPLOADED, null, null, "t-1"), true);
    }
}
