package com.workflow.listener;

import com.workflow.runtime.ProcessInstance;

/**
 * 执行监听器 - 监听流程实例的生命周期事件
 *
 * 用法:
 * <pre>
 * engine.addListener(new ExecutionListener() {
 *     public void onStarted(ProcessInstance instance) { ... }
 *     public void onCompleted(ProcessInstance instance) { ... }
 * });
 * </pre>
 */
public interface ExecutionListener {

    /** 流程实例启动时触发 */
    default void onStarted(ProcessInstance instance) {}

    /** 流程实例完成时触发 */
    default void onCompleted(ProcessInstance instance) {}

    /** 流程实例终止时触发 */
    default void onTerminated(ProcessInstance instance) {}

    /** 流程实例挂起时触发 */
    default void onSuspended(ProcessInstance instance) {}

    /** 流程实例恢复时触发 */
    default void onResumed(ProcessInstance instance) {}
}
