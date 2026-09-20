package com.crmforlogistics.messagecenter.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构边界门禁 —— 对应 {@code docs/代码板块地图.md} 第四节「越界依赖清单」。
 *
 * <h2>当前状态：硬门禁，无冻结基线</h2>
 * 项目约定的依赖方向是「业务板块 → 基础设施」单向。历史上这条规则的反方向存在 51 条越界，
 * 分四步清理，已于 2026-09-20 归零（51 → 40 → 32 → 0）。基线归零后，
 * {@code FreezingArchRule} 这层「过渡脚手架」已拆除：本类现在是一条 <b>普通硬规则</b>，
 * 任何一条越界都会让构建当场变红。
 *
 * <h2>为什么曾经用 FreezingArchRule，又为什么拆掉</h2>
 * 引入门禁时若直接写死规则，构建当场变红、无法合入；若因此放弃门禁，越界就会继续增长。
 * 于是当时用 {@code FreezingArchRule} 把「已有越界」冻结成基线快照，规则只对
 * <b>新增</b> 越界失败 —— 门禁可以立即合入，且真正起到拦截作用。
 * 四步清理走完后快照归零，就换成现在这条硬规则。
 * <p>
 * 拆掉的理由：留一个 <b>空的</b> 快照等于留一个语义模糊的终点 ——
 * 既让人误以为还剩债，又让 {@code allowStoreCreation} 的边界行为变得不直观。
 * 冻结机制只在「有历史债要带着走」时才成立，债还完就该拆。随本次收口一并删除的还有
 * {@code src/test/resources/archunit.properties} 与 {@code archunit_store/}。
 *
 * <h2>改这个文件前务必先读的坑</h2>
 * <ol>
 *   <li><b>不能用 {@code @AnalyzeClasses} + {@code @ArchTest}。</b>
 *       那是 ArchUnit 的自定义 TestEngine，在 surefire + {@code -Dtest=} 过滤下，
 *       engine 产出的 TEST 级 descriptor 会被 surefire 丢弃，表现为
 *       {@code Tests run: 0} 且规则失败<b>不会</b>让构建变红 —— 门禁看起来在跑，实际完全失效。
 *       因此这里改用普通 JUnit Jupiter {@code @Test} 手动驱动，走 surefire 最常规的路径。</li>
 *   <li><b>包标识符必须写全限定前缀，不能用 {@code ..channel..}。</b>
 *       {@code ..channel..} 会同时匹配 {@code com.…messagecenter.channel}（顶层渠道包）
 *       和 {@code com.…messagecenter.service.channel}（渠道编排层），把
 *       {@code service.whatsapp → service.channel.ChatAppCapabilityGate} 这类
 *       <i>合法</i> 的编排层依赖误判成越界。误伤比漏判更伤门禁的公信力。</li>
 *   <li><b>（历史）{@code allowStoreCreation} 默认是 {@code false} —— 别信「默认 true」的说法。</b>
 *       实测（ArchUnit 1.3.0）：源码常量 {@code ALLOW_STORE_CREATION_DEFAULT = "false"}，
 *       且 {@code ArchConfiguration.PROPERTY_DEFAULTS} 里没有任何 {@code freeze.store} 项；
 *       不设该属性 + store 目录不存在 → {@code StoreInitializationFailedException}。
 *       即<b>基线缺失时默认就报错</b>（fail loud），并不存在「静默冻结成新基线」这回事。
 *       真实风险在反方向：把 {@code allowStoreCreation=true} 固化进 {@code archunit.properties}
 *       再提交，基线一旦丢失才会被静默重建。故首次冻结应改用命令行显式开，且前缀不能漏：
 *       {@code -Darchunit.freeze.store.default.allowStoreCreation=true}。</li>
 *   <li><b>（历史）基线会自动收缩，且是沉默写盘。</b>
 *       实测 {@code ALLOW_STORE_UPDATE_DEFAULT = "true"}（项目当时未覆盖），
 *       {@code FreezingArchRule.removeObsoleteViolationsFromStore()} 会在跑测试时
 *       直接回写快照、把不再发生的越界删掉。当时提交前必须单独 diff 快照、
 *       确认「只减不增」。现已无快照可写。</li>
 * </ol>
 *
 * <h2>不要做什么</h2>
 * 规则已经是硬的：<b>修越界请改代码，不要为了让构建变绿而把越界包加进
 * {@link #CHANNEL_AWARE_SERVICE_PACKAGES}</b>。白名单只容纳「同属一个板块」或
 * 「职责本身就是依赖渠道」的包，每条都必须在下面写明理由。
 */
class ArchitectureBoundaryTest {

    /** 顶层渠道包（渠道适配层）。刻意写全限定名，避免匹配到 {@code service.channel}。 */
    private static final String CHANNEL_PACKAGE = "com.crmforlogistics.messagecenter.channel..";

    /** 业务域包根。 */
    private static final String SERVICE_PACKAGE = "com.crmforlogistics.messagecenter.service..";

    /**
     * 允许依赖 {@link #CHANNEL_PACKAGE} 的 service 子包。准入标准：要么与对应渠道同属一个板块，
     * 要么其职责本身就是协调渠道。逐条理由：
     *
     * <ul>
     *   <li>{@code …service.channel} —— 渠道编排层，职责就是协调各渠道，依赖本身合法。
     *       它曾经的实现问题（写死 {@code switch(channelType)} 与凭证字段表，导致新增渠道必须改核心）
     *       已于 2026-09-20 由「渠道注册表」修掉（{@code ChannelType} + {@code ChannelTypeRegistry}）；
     *       那本来就是实现方式问题而非依赖方向问题，本规则不应误伤。</li>
     *   <li>{@code …service.wecom} —— 企业微信业务层，与 {@code channel.wecom} 同属「企业微信」板块
     *       （板块地图第五节）。两者双向依赖是刻意的黏合，算板块内部结构。</li>
     *   <li>{@code …service.chatapp} —— chatapp 业务层，与 {@code channel.chatapp} 同属「chatapp」板块
     *       （板块地图第七节）。</li>
     * </ul>
     */
    private static final String[] CHANNEL_AWARE_SERVICE_PACKAGES = {
            "com.crmforlogistics.messagecenter.service.channel..",
            "com.crmforlogistics.messagecenter.service.wecom..",
            "com.crmforlogistics.messagecenter.service.chatapp.."
    };

    /**
     * 规则一：业务域不得依赖渠道实现。
     *
     * <p>业务域（消息核心、联系人、会话、话题、通话记录、账号……）访问渠道时必须经由
     * {@code service.channel} 编排层或对应板块的内部结构，不得直接 import {@code channel} 包下的
     * 网关、凭证解析器或实体。直接依赖会让渠道实现细节穿透进业务域，渠道换代或新增渠道时
     * 被迫修改核心代码 —— 这正是板块地图第四节记录的那类架构泄漏。
     *
     * <p>注意本规则只约束「业务域 → 顶层 channel 包」这一个方向。{@code channel} 包内部混有业务逻辑
     * （如 {@code EmailSendService}、{@code WeComSendService}）导致反方向也存在依赖，那是包组织
     * 问题，属另一条改造线，刻意不在本轮门禁范围内。
     */
    private static final ArchRule BUSINESS_DOMAINS_MUST_NOT_DEPEND_ON_CHANNEL_IMPLEMENTATIONS =
            noClasses()
                    .that().resideInAPackage(SERVICE_PACKAGE)
                    .and().resideOutsideOfPackages(CHANNEL_AWARE_SERVICE_PACKAGES)
                    .should().dependOnClassesThat().resideInAPackage(CHANNEL_PACKAGE)
                    .because("业务域应经由渠道编排层访问渠道，不得直接依赖 channel 包下的网关/实体。"
                            + "请改走 service.channel 或对应渠道板块；"
                            + "若认为该依赖合法，请先确认它属于「同板块内部结构」或"
                            + "「职责本身就是协调渠道」，再在 CHANNEL_AWARE_SERVICE_PACKAGES 里"
                            + "补条目并写明理由，不要为了绕过门禁而加白名单。"
                            + "依据：docs/代码板块地图.md 第四节。");

    @Test
    void businessDomainsMustNotDependOnChannelImplementations() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.crmforlogistics.messagecenter");

        BUSINESS_DOMAINS_MUST_NOT_DEPEND_ON_CHANNEL_IMPLEMENTATIONS.check(classes);
    }
}
