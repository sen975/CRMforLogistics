package com.crmforlogistics.messagecenter.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 助手编排模块的装配条件。照 {@code ConditionalOnWeComEnabled} 的先例做成组合注解，
 * 以免同一串表达式在多个类上各写一遍、改一处漏两处。
 *
 * <h2>为什么是「与」而不是只看 enabled</h2>
 * 三个条件缺一不可，各自拦的是一类不同的错误：
 *
 * <ul>
 *   <li>{@code assistant.enabled} —— 功能开关。默认 {@code false}，保证「先灰度后端、
 *       不发布前端」是默认状态，而不是需要记得去关的东西。</li>
 *   <li>{@code assistant.base-url} / {@code assistant.api-key} —— 缺凭据时**不装配服务**，
 *       端点返回 503。这条不是洁癖：若照常装配，每个请求都会打到
 *       {@code http://127.0.0.1} 之类的兜底地址上，然后以「连接被拒」的形式失败 ——
 *       把配置缺失伪装成网络故障，排障时最耗时的一类误导。</li>
 * </ul>
 *
 * <p>被这个注解守住的 bean 有：{@code AssistantModelClient}、{@code AssistantConversationService}、
 * {@code AssistantPendingActionService}。控制器不在此列 —— 它必须始终存在，才能在功能关闭时
 * 给出 503 而不是 404（404 会让前端以为是自己请求的路径写错了）。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression(
        "'${assistant.enabled:false}' == 'true'"
                + " and not '${assistant.base-url:}'.isBlank()"
                + " and not '${assistant.api-key:}'.isBlank()")
public @interface ConditionalOnAssistantEnabled {
}
