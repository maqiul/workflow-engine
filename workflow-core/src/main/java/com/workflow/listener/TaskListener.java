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
}
