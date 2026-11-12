package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 素材库的读口：列出某个 chatapp 账号名下<b>还能被模板引用</b>的素材。
 *
 * <h2>它补的是哪条断链</h2>
 * 素材一直是写进库里的（{@code template_media_assets}，每张图上传时留一行），但此前只有两条路
 * 能碰到它：上传时写入的那一条、模板引用时按 id 查的那一条。没有任何路径能回答
 * 「我这个账号下有哪些素材」—— 于是「素材库」只是数据层的一个形状，
 * 而助手能拿到的图只有「用户这一轮亲手发来的那一张」（见
 * {@code TemplateMediaCandidates} 里那段「为什么不给从素材库里挑这张路」）。
 *
 * <h2>为什么这里列出的是「能用」而不是「有过」</h2>
 * 口径与 {@code WhatsAppTemplateApplicationService#prepareMedia} 同源：只看
 * {@code UPLOADED}（见 {@code TemplateMediaAssetMapper#findUsableByChannelAccountId}）。
 * 一个只会返回注定失败选项的列表，最后一定以「助手说库里有、建出来报错」的形式出现在用户面前。
 *
 * <h2>归属在哪判</h2>
 * 在 SQL 里（{@code findByIdAndOwner}），与写路径
 * {@code WhatsAppTemplateApplicationService#requireOwnedAccount} 用的是同一条查询 ——
 * 这里不复制判据，也不做「先查出全部再在 Java 里筛」。
 *
 * <h2>刻意没有的东西</h2>
 * 没有删除、没有改名、没有「以素材为单位的去重」：素材是 CAMS 侧的不可变对象，
 * 我们这边这行记录只是账。要清理素材得在服务商控制台做 —— 这也是它没有写口的原因。
 */
@Service
public class WhatsAppTemplateMediaCatalogService {

    /**
     * 一次最多列出多少条。
     *
     * <p>有界是硬要求：这个列表会整段进提示词（候选清单），无界就等于让素材库的规模
     * 决定一次请求的上下文成本。20 条足够「挑一张来建模板」这个用途 ——
     * 真要重建，把最近的用完再说。
     */
    public static final int MAX_LIMIT = 20;

    private final ChannelAccountMapper accounts;
    private final TemplateMediaAssetMapper media;

    public WhatsAppTemplateMediaCatalogService(ChannelAccountMapper accounts, TemplateMediaAssetMapper media) {
        this.accounts = accounts;
        this.media = media;
    }

    /**
     * 列出一个账号下可用的素材，最近的在前。
     *
     * <p>账号不存在或不属于 {@code actorUserId} 时抛 {@code WHATSAPP_ACCOUNT_NOT_FOUND}
     * （与写路径同一个码、同一句话）：读一个别人的账号与读一个不存在的账号在这里不做区分，
     * 区分了就等于给「这个 id 存不存在」发了一个可以枚举的信号。
     */
    public List<MediaAsset> listForActor(UUID actorUserId, UUID accountId, int limit) {
        ChannelAccountEntity account = actorUserId == null || accountId == null ? null
                : accounts.findByIdAndOwner(accountId, actorUserId);
        if (account == null) {
            throw new WhatsAppTemplateException("WHATSAPP_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND,
                    "WhatsApp 账号不存在或不属于当前用户", Map.of(), null, false);
        }
        if (limit <= 0) {
            return List.of();
        }
        List<TemplateMediaAssetEntity> rows =
                media.findUsableByChannelAccountId(accountId, Math.min(limit, MAX_LIMIT));
        List<MediaAsset> assets = new ArrayList<>(rows.size());
        for (TemplateMediaAssetEntity row : rows) {
            assets.add(view(row));
        }
        return assets;
    }

    /**
     * 一条素材。
     *
     * <p>刻意<b>不带</b> {@code provider_url}（服务商侧那个地址）与内部错误字段：
     * 前者的唯一用途是「直接拿它去建模板」，而那正是本项目拦死的路
     * （{@code WhatsAppTemplateValidator} 只认内部素材 id）；把它交出去，
     * 等于给调用方递一把看起来能开锁、实际会把请求打回来的钥匙。
     */
    public record MediaAsset(UUID id, HeaderFormat format, String contentType, long sizeBytes, Instant createdAt) {
    }

    private static MediaAsset view(TemplateMediaAssetEntity asset) {
        return new MediaAsset(asset.getId(), HeaderFormat.valueOf(asset.getMediaFormat()),
                asset.getContentType(),
                // sizeBytes 在库里是 not null，实体上是 Long —— 一个装箱空值不该让整个列表消失。
                asset.getSizeBytes() == null ? 0L : asset.getSizeBytes(),
                asset.getCreatedAt());
    }
}
