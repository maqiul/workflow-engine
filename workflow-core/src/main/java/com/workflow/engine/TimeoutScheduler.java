package com.workflow.engine;

import com.workflow.enums.TimeoutPolicy;

/**
 * 超时调度器 - 任务超时后自动触发策略动作
 *
 * 由 WorkflowEngine 内部持有:创建任务时 schedule,任务完成/驳回/转办时 cancel。
 * 超时回调在调度器线程执行,回调内部会重新查仓储判断任务是否仍 PENDING,
 * 保证与用户手动操作的并发安全性(幂等)。
 */
public interface TimeoutScheduler {

    /**
     * 注册一个任务的超时调度
     *
     * @param taskId         任务 id
     * @param instanceId     实例 id
     * @param timeoutMillis  超时毫秒数(>0)
     * @param policy         超时策略(不能为 NONE)
     * @param targetUserId   策略为 AUTO_TRANSFER 时的目标用户,其它策略传 null
     */
    void schedule(String taskId, String instanceId, long timeoutMillis,
                  TimeoutPolicy policy, String targetUserId);

    /**
     * 取消任务的超时调度(任务被手动完成/驳回/转办/终止时调用)
     */
    void cancel(String taskId);

    /**
     * 关闭调度器,释放线程资源
     */
    void shutdown();
}
