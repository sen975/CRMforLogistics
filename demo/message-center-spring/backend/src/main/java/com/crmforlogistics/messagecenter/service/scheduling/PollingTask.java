package com.crmforlogistics.messagecenter.service.scheduling;

/**
 * 一个交给 {@link AdaptivePollingScheduler} 统一驱动的轮询任务。
 *
 * <p>实现方只负责"跑一轮并如实报告这一轮干了多少活"，节拍、空转退避与唤醒由调度器决定，
 * 实现方不再自己声明 {@code @Scheduled}。
 */
public interface PollingTask {

    /** 任务标识，用于注册、唤醒和日志；同一容器内必须唯一。 */
    String name();

    /**
     * 执行一轮。
     *
     * @return 本轮处理的工作项数量；返回 {@code 0} 表示空转，调度器会据此拉长下一次的间隔
     */
    int pollOnce();
}
