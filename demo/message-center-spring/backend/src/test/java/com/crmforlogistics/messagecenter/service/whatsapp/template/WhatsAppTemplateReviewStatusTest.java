package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ReviewStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CAMS 的 {@code AuditStatus} 是一个自由字符串，映射表漏掉一个取值，整条链路就会「安静地说错话」。
 *
 * <p>2026-09-29 的真实报障：CAMS 对审核未通过的模板返回 {@code sendFail}（官方文档里没有这个值），
 * 而两处映射都只认 {@code pass / fail / auditing / unaudit} ⇒ 落到 {@code UNKNOWN} ⇒
 * 界面把「审核被拒」显示成灰色的「未知」，用户以为还能「恢复发送」，点下去只收到
 * 409 {@code TEMPLATE_NOT_APPROVED} 外带一句英文提示，真正的原因（模板内容违规）一个字都没露出来。
 *
 * <p>这组断言就是钉住那个缺口：<b>已知取值一个都不许落到 {@link ReviewStatus#UNKNOWN}</b>。
 */
class WhatsAppTemplateReviewStatusTest {

    /** 官方文档列出的全部取值，外加线上实测出现过、而文档里没有的 {@code sendFail}。 */
    private static final List<String> KNOWN_PROVIDER_VALUES = List.of(
            "pass", "fail", "auditing", "unaudit", "disabled", "paused", "sendFail");

    @Test
    void everyKnownProviderValueMapsToSomethingOtherThanUnknown() {
        assertThat(KNOWN_PROVIDER_VALUES)
                .allSatisfy(raw -> assertThat(ReviewStatus.fromProviderAuditStatus(raw))
                        .as("CAMS auditStatus=%s 落到了 UNKNOWN —— 映射表少了一个取值", raw)
                        .isNotEqualTo(ReviewStatus.UNKNOWN));
    }

    @Test
    void sendFailIsTreatedAsRejected() {
        assertThat(ReviewStatus.fromProviderAuditStatus("sendFail")).isEqualTo(ReviewStatus.REJECTED);
    }

    @Test
    void disabledAndPausedAreSuspendedRatherThanUnknown() {
        assertThat(ReviewStatus.fromProviderAuditStatus("disabled")).isEqualTo(ReviewStatus.SUSPENDED);
        assertThat(ReviewStatus.fromProviderAuditStatus("paused")).isEqualTo(ReviewStatus.SUSPENDED);
    }

    @Test
    void auditStatusIsMatchedCaseInsensitivelyAndAfterTrimming() {
        assertThat(ReviewStatus.fromProviderAuditStatus("  Pass ")).isEqualTo(ReviewStatus.APPROVED);
        assertThat(ReviewStatus.fromProviderAuditStatus("SENDFAIL")).isEqualTo(ReviewStatus.REJECTED);
        assertThat(ReviewStatus.fromProviderAuditStatus("Auditing")).isEqualTo(ReviewStatus.PENDING);
    }

    @Test
    void anUnrecognisedOrMissingValueStaysUnknownInsteadOfBeingGuessed() {
        assertThat(ReviewStatus.fromProviderAuditStatus(null)).isEqualTo(ReviewStatus.UNKNOWN);
        assertThat(ReviewStatus.fromProviderAuditStatus("")).isEqualTo(ReviewStatus.UNKNOWN);
        assertThat(ReviewStatus.fromProviderAuditStatus("something-new-from-cams")).isEqualTo(ReviewStatus.UNKNOWN);
    }
}
