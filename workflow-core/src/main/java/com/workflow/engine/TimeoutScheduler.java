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
     * <p><b>参数是绝对到期时刻，不是相对延时</b>（epoch millis）。这一点很重要：
     * 系统里持久化的只有任务的 {@code createTime} 与节点上的超时配置，二者相加即得
     * 到期时刻。重启恢复时按同一公式重算，得到的就是同一个时刻 —— 因此<b>不需要</b>
     * 再落库一份 dueAt，避免出现「第二真相来源」。
     *
     * <p>实现方按 {@code delay = max(0, dueAt - now)} 调度；已经过期的任务（delay 为 0）
     * 应在调度线程立即触发，这正是进程重启后的补偿路径。
     *
     * @param taskId         任务 id
     * @param instanceId     实例 id
     * @param dueAt          到期时刻(epoch millis)；&le; 0 表示未配置超时，忽略
     * @param policy         超时策略(不能为 NONE)
     * @param targetUserId   策略为 AUTO_TRANSFER 时的目标用户,其它策略传 null
     */
    void schedule(String taskId, String instanceId, long dueAt,
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
