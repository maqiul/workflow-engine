package com.workflow.listener;

import com.workflow.runtime.TaskInstance;

/**
 * 任务监听器 - 监听任务的生命周期事件
 *
 * 用法:
 * <pre>
 * engine.addListener(new TaskListener() {
 *     public void onCreated(TaskInstance task) { ... }
 *     public void onCompleted(TaskInstance task, String userId) { ... }
 * });
 * </pre>
 */
public interface TaskListener {

    /** 任务创建时触发 */
    default void onCreated(TaskInstance task) {}

    /** 任务完成时触发 */
    default void onCompleted(TaskInstance task, String userId) {}

    /** 任务驳回时触发 */
    default void onRejected(TaskInstance task, String userId, String reason) {}

    /** 任务转办时触发 */
    default void onTransferred(TaskInstance task, String fromUser, String toUser) {}

    /** 任务撤回时触发 */
    default void onWithdrawn(TaskInstance task) {}

    /**
     * 任务被取消时触发 —— 区别于 {@link #onCompleted 正常办结}。
     *
     * <p>在<b>非办结</b>导致待办被销毁的路径统一派发：实例终止、改道跳转
     * （{@code jumpToNode} / {@code jumpTokenToNode}）、减签。接入方据此清理待办投影，
     * 避免出现"任务已消失、待办列表仍显示可办"的脏行。
     *
     * @param reason 取消来源：{@code terminated} / {@code jumped} / {@code sign-removed}
     */
    default void onCancelled(TaskInstance task, String reason) {}

    /**
     * 任务被认领 / 指派办理人时触发（{@code claim} / {@code setAssignee}）。
     *
     * <p>接入方据此更新待办投影的可见性列 —— 认领后仅 assignee 可办。
     */
    default void onAssigned(TaskInstance task, String assignee) {}
}
