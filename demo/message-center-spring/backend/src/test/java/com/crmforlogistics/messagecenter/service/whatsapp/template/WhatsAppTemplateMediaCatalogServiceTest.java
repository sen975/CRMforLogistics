package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 素材库的读口。
 *
 * <p>三件事都会「错了也不报错」，所以只能靠断言钉住：归属判在 SQL 用的那条查询上
 * （否则能读到别人的素材）、读的是<b>账号</b>维度而不是全局、
 * 以及条数上限真的被夹住了（这个列表整段进提示词）。
 */
class WhatsAppTemplateMediaCatalogServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID ASSET = UUID.randomUUID();

    private final ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
    private final TemplateMediaAssetMapper media = mock(TemplateMediaAssetMapper.class);
    private final WhatsAppTemplateMediaCatalogService catalog =
            new WhatsAppTemplateMediaCatalogService(accounts, media);

    @Test
    void anAssetOfTheUsersOwnAccountIsListed() {
        when(accounts.findByIdAndOwner(ACCOUNT, USER)).thenReturn(account());
        when(media.findUsableByChannelAccountId(eq(ACCOUNT), anyInt()))
                .thenReturn(List.of(asset()));

        List<WhatsAppTemplateMediaCatalogService.MediaAsset> found = catalog.listForActor(USER, ACCOUNT, 5);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).id()).isEqualTo(ASSET);
        assertThat(found.get(0).format()).isEqualTo(WhatsAppTemplateModels.HeaderFormat.IMAGE);
        assertThat(found.get(0).contentType()).isEqualTo("image/png");
        assertThat(found.get(0).sizeBytes()).isEqualTo(2048L);
        // 只有账号是「我的」才会去查素材：断言到这一步，单看返回空集无法区分
        // 「复核过但没命中」与「根本没复核」。
        verify(media).findUsableByChannelAccountId(eq(ACCOUNT), anyInt());
    }

    /**
     * 账号不是自己的 ⇒ 与「账号不存在」同一个回答，而且<b>一条素材都不查</b>。
     *
     * <p>两个 id 合用一个错误码是刻意的：分开就等于给「这个账号 id 存不存在」发了一个
     * 可以枚举的信号，而素材是这个账号空间里的东西。
     */
    @Test
    void anAccountThatIsNotYoursIsRefusedBeforeAnyAssetQuery() {
        assertThatThrownBy(() -> catalog.listForActor(USER, ACCOUNT, 5))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting(error -> ((WhatsAppTemplateException) error).code())
                .isEqualTo("WHATSAPP_ACCOUNT_NOT_FOUND");

        verify(media, never()).findUsableByChannelAccountId(eq(ACCOUNT), anyInt());
    }

    @Test
    void aLimitAboveTheCeilingIsCappedAtTheCeiling() {
        when(accounts.findByIdAndOwner(ACCOUNT, USER)).thenReturn(account());

        catalog.listForActor(USER, ACCOUNT, 500);

        verify(media).findUsableByChannelAccountId(ACCOUNT, WhatsAppTemplateMediaCatalogService.MAX_LIMIT);
    }

    @Test
    void aNonPositiveLimitSkipsTheQuery() {
        when(accounts.findByIdAndOwner(ACCOUNT, USER)).thenReturn(account());

        assertThat(catalog.listForActor(USER, ACCOUNT, 0)).isEmpty();

        verify(media, never()).findUsableByChannelAccountId(eq(ACCOUNT), anyInt());
    }

    /** {@code size_bytes} 实体上是 {@code Long}：一个装箱空值不该让整个列表消失。 */
    @Test
    void aNullSizeBecomesZeroInsteadOfBlowingUp() {
        TemplateMediaAssetEntity row = asset();
        row.setSizeBytes(null);
        when(accounts.findByIdAndOwner(ACCOUNT, USER)).thenReturn(account());
        when(media.findUsableByChannelAccountId(eq(ACCOUNT), anyInt())).thenReturn(List.of(row));

        assertThat(catalog.listForActor(USER, ACCOUNT, 5).get(0).sizeBytes()).isZero();
    }

    private static ChannelAccountEntity account() {
        ChannelAccountEntity entity = new ChannelAccountEntity();
        entity.setId(ACCOUNT);
        return entity;
    }

    private static TemplateMediaAssetEntity asset() {
        TemplateMediaAssetEntity entity = new TemplateMediaAssetEntity();
        entity.setId(ASSET);
        entity.setMediaFormat("IMAGE");
        entity.setContentType("image/png");
        entity.setSizeBytes(2048L);
        entity.setCreatedAt(Instant.parse("2026-09-29T02:00:00Z"));
        return entity;
    }
}
