package com.workflow.enums;

/**
 * 异步作业类型。
 *
 * <p>决定作业由哪个 {@code JobHandler} 处理。同一种 type 的作业共享一个处理器。
 */
public enum JobType {
    /** 任务超时 —— payload: {@code {policy, targetUserId}}，超时后按策略动作。 */
    TIMEOUT,
    /** 异步服务任务 —— payload 由业务自定义，执行 delegate 后推进流程。 */
    SERVICE_TASK,
    /** 通知重试 —— payload: {@code {taskId, userId}}，外部通知失败后重投。 */
    NOTIFICATION_RETRY
}
