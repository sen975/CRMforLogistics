package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComAppEventCodec;
import com.crmforlogistics.messagecenter.channel.wecom.WeComContactEventEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.mapper.WeComContactEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * 客户联系事件的落库服务。
 *
 * <p><b>本类的核心取舍是「先持久化再 ack」。</b>回包超过 1 秒企微会屏蔽该事件一段时间，所以很想
 * 先丢进内存队列立刻返回；但入队后进程崩溃就等于永久丢失（企微不会为已 ack 的事件重推）。
 * 因此这里在请求线程上做一次**有界同步 INSERT**，只有提交成功、或撞上 {@code dedupe_key}
 * 唯一约束（重复投递）时才返回成功；数据库不可用返回 503，把重试机会留给企微。
 *
 * <p>首期不建以内存队列为真源的 worker。后续动作从这张表的 {@code RECEIVED} 行领取
 * （带租约、重试次数与 {@code DEAD_LETTER}），进程重启后能恢复。
 */
@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComContactEventService {
    /** 首期只接客户关系事件；V81 的 CHECK 约束也是这个值。 */
    static final String EVENT_CHANGE_EXTERNAL_CONTACT = "change_external_contact";
    private static final String STATUS_RECEIVED = "RECEIVED";

    /** 规范化 JSON 只用默认配置：不排序键、不忽略空值 —— 键序由 LinkedHashMap 固定。 */
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper();

    private final WeComContactEventMapper mapper;
    private final WeComInstallationService installationService;
    private final AppConfig config;
    private final Clock clock;

    public WeComContactEventService(WeComContactEventMapper mapper,
                                    WeComInstallationService installationService,
                                    AppConfig config,
                                    Clock clock) {
        this.mapper = mapper;
        this.installationService = installationService;
        this.config = config;
        this.clock = clock;
    }

    /**
     * 落库一条已解码的客户联系事件。
     *
     * @return {@link IngestResult}；只有 {@link IngestResult#acked()} 为真才允许向企微返回 success
     */
    public IngestResult ingest(WeComAppEventCodec.DecodedAppEvent event) {
        if (!config.wecomContactEventEnabled()) {
            // 默认关闭时不 ack：让企微保留重试机会，避免「功能没开」期间静默丢事件。
            return IngestResult.DISABLED;
        }
        WeComInstallationEntity installation;
        try {
            installation = installationService.find(config.wecomSuiteId(), event.toUserName());
        } catch (WeComException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new WeComException("WECOM_CONTACT_EVENT_INSTALLATION_LOOKUP_FAILED", 503,
                    "企业微信客户联系事件安装信息查询失败", exception);
        }
        if (installation == null || installation.getId() == null) {
            // 授权竞态或配置错误都可能走到这里，返回 retry 让企微重推。
            return IngestResult.INSTALLATION_UNKNOWN;
        }
        try {
            return mapper.insertIgnore(newEntity(installation, event)) == 1
                    ? IngestResult.ACCEPTED
                    : IngestResult.DUPLICATE;
        } catch (RuntimeException exception) {
            throw new WeComException("WECOM_CONTACT_EVENT_INGEST_FAILED", 503,
                    "企业微信客户联系事件落库失败", exception);
        }
    }

    /** 某个安装下的动态时间线；调用方负责先解析出 installation 并校验访问权限。 */
    public List<WeComContactEventEntity> listTimeline(UUID installationId, Instant since,
                                                      String changeType, int limit) {
        if (installationId == null) {
            throw new IllegalArgumentException("installationId is required");
        }
        return mapper.listTimeline(installationId, since, changeType, Math.max(1, Math.min(limit, 200)));
    }

    private WeComContactEventEntity newEntity(WeComInstallationEntity installation,
                                              WeComAppEventCodec.DecodedAppEvent event) {
        Instant receivedAt = clock.instant();
        WeComContactEventEntity entity = new WeComContactEventEntity();
        entity.setId(UUID.randomUUID());
        entity.setInstallationId(installation.getId());
        entity.setSuiteId(installation.getSuiteId());
        // 密文 corpid 大小写敏感：这里以及后续任何地方都不得做大小写归一化。
        entity.setAuthCorpId(installation.getAuthCorpId());
        entity.setEvent(event.event());
        entity.setChangeType(event.changeType());
        entity.setWecomUserId(event.userId());
        entity.setExternalUserId(event.externalUserId());
        entity.setChatId(event.chatId());
        entity.setState(event.state());
        entity.setWelcomeCode(event.welcomeCode());
        entity.setFailReason(event.failReason());
        entity.setProviderSource(event.providerSource());
        entity.setProviderCreatedAt(event.providerCreatedAt());
        entity.setReceivedAt(receivedAt);
        entity.setDedupeKey(dedupeKey(installation.getId(), event.event(), event.changeType(),
                event.userId(), event.externalUserId(), event.chatId(), event.state(),
                event.welcomeCode(), event.failReason(), event.providerSource(),
                event.providerCreatedAt()));
        entity.setIngestStatus(STATUS_RECEIVED);
        entity.setAttemptCount(0);
        return entity;
    }

    /**
     * 自造去重键 —— <b>企微事件没有 EventId</b>，官方事件 XML 只有 {@code CreateTime}，
     * 所以不能用「平台给的事件 ID」做幂等（{@code chatapp_webhook_inbox} 那套先例在这里不成立）。
     *
     * <p>键 = {@code sha256(规范化 JSON)}，输入是事件的**全部语义标识**。三条规范化规则：
     * <ul>
     *   <li>键序固定（{@code LinkedHashMap}），空值保留成 {@code null} 而不是省略 ——
     *       否则「没有 state」和「state 为空串」会撞成同一条；</li>
     *   <li>**不做大小写归一化**：密文 ID 与 welcome code 都区分大小写，{@code toLowerCase()}
     *       会把两个不同事件判成同一条；</li>
     *   <li>用 {@code provider_created_at} 而不是本地 {@code received_at}：企微重推时前者不变、
     *       后者每次都变，用接收时刻做键等于完全没有幂等。</li>
     * </ul>
     *
     * <p>已知误差：同一秒内同一成员对同一客户产生两次同类型事件会被判成重复。这个方向是
     * 「宁可少记也不重复记」，与关系流水的语义一致。
     */
    static String dedupeKey(UUID installationId, String event, String changeType, String wecomUserId,
                            String externalUserId, String chatId, String state, String welcomeCode,
                            String failReason, String providerSource, Instant providerCreatedAt) {
        LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("installation_id", installationId == null ? null : installationId.toString());
        canonical.put("event", event);
        canonical.put("change_type", changeType);
        canonical.put("wecom_user_id", wecomUserId);
        canonical.put("external_user_id", externalUserId);
        canonical.put("chat_id", chatId);
        canonical.put("state", state);
        canonical.put("welcome_code", welcomeCode);
        canonical.put("fail_reason", failReason);
        canonical.put("provider_source", providerSource);
        canonical.put("provider_created_at",
                providerCreatedAt == null ? null : providerCreatedAt.getEpochSecond());
        try {
            return sha256Hex(CANONICAL_JSON.writeValueAsString(canonical));
        } catch (Exception exception) {
            throw new IllegalStateException("contact event dedupe key cannot be serialized", exception);
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** 落库结果。只有 {@code ACCEPTED} / {@code DUPLICATE} 允许 ack，其余一律 503 让企微重推。 */
    public enum IngestResult {
        /** 本次真的写入了一行。 */
        ACCEPTED,
        /** 撞上 {@code dedupe_key} 唯一约束：企微重推或并发重复投递，已有同一条记录。 */
        DUPLICATE,
        /** 功能开关关闭，不 ack。 */
        DISABLED,
        /** 当前 suite 下没有这个密文 corpid 的安装记录，不 ack。 */
        INSTALLATION_UNKNOWN;

        public boolean acked() {
            return this == ACCEPTED || this == DUPLICATE;
        }
    }
}
