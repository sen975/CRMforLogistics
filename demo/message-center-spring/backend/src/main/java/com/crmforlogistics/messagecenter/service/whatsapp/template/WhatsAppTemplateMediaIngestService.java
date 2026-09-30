package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService.UploadResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * 把用户写下的一个图片地址收进素材库：抓下来 → 按图片上传 → 落一条素材记录。
 *
 * <h2>为什么这是独立的一步，而不是上传服务里的一个重载</h2>
 * 上传服务收到的是一段<b>已经在本地的字节</b>（HTTP 请求体里的 {@code MultipartFile}），
 * 它不需要知道那些字节从哪来。而这一步要先做一件性质完全不同的事：
 * <b>让服务端去访问一个用户给的地址</b>（见 {@link TemplateMediaLinkFetcher} 的类注释）。
 * 两者的失败面、防护面、可测面都不一样，混在一起会让上传链路平白多出一份网络依赖。
 *
 * <h2>幂等键取自内容，不取自地址</h2>
 * 同一个地址重复提交时应当得到同一条素材（用户说两遍不该存两遍）。但拿地址当键有个坏处：
 * 地址没变而图变了（CDN 换图、用户重传）会撞上「键已绑定到不同内容」的冲突，
 * 而那条报错对用户毫无意义 —— 他确实是想把<b>现在这张</b>图存进来。
 * 所以键取下载内容的 SHA-256：同图必同键（去重），换图必新键（能存）。
 * 收益是连「两个不同地址指向同一张图」也会自动合成一条。
 */
@Service
public class WhatsAppTemplateMediaIngestService {

    private final ChannelAccountMapper accounts;
    private final TemplateMediaLinkFetcher fetcher;
    private final WhatsAppTemplateMediaUploadService uploads;

    public WhatsAppTemplateMediaIngestService(ChannelAccountMapper accounts,
                                              TemplateMediaLinkFetcher fetcher,
                                              WhatsAppTemplateMediaUploadService uploads) {
        this.accounts = accounts;
        this.fetcher = fetcher;
        this.uploads = uploads;
    }

    /**
     * 抓取地址并把它作为图片素材存进这个账号。
     *
     * <p>归属判据与写路径同一条 SQL（{@code findByIdAndOwner}）：账号不属于调用方时
     * 报「不存在或不属于当前用户」—— 素材会落到账号的空间里，
     * 所以「能往哪个账号传」与「能往哪个账号建模板」必须是同一个答案。
     *
     * <p>整段刻意<b>不在事务里</b>：抓取是一次可能十几秒的网络往返，
     * 把它包进事务等于让一个数据库连接陪它等。上传那一步自己有事务边界（见 upload 服务）。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UploadResult ingestFromLink(UUID actorUserId, UUID accountId, String url, String traceId) {
        ChannelAccountEntity account = actorUserId == null || accountId == null ? null
                : accounts.findByIdAndOwner(accountId, actorUserId);
        if (account == null) {
            throw new WhatsAppTemplateException("WHATSAPP_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND,
                    "WhatsApp 账号不存在或不属于当前用户", Map.of(), null, false);
        }
        TemplateMediaLinkFetcher.Fetched fetched = fetcher.fetch(url);
        return uploads.upload(accountId, HeaderFormat.IMAGE, new ByteArrayInputStream(fetched.bytes()),
                fetched.bytes().length, fetched.fileName(), fetched.contentType(),
                requestIdOf(fetched.bytes()), actorUserId, traceId);
    }

    /**
     * 幂等键：{@code link-} + 内容摘要前 32 位。
     *
     * <p>形状要满足上传服务对 {@code clientRequestId} 的约束
     * （{@code [A-Za-z0-9._~:-]{1,255}}），hex 天然满足。前缀 {@code link-} 只是为了让库里
     * 一眼能看出这条素材是从外链收进来的、与前端直传的那批区分开。
     */
    static String requestIdOf(byte[] bytes) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return "link-" + digest.substring(0, 32);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

}
