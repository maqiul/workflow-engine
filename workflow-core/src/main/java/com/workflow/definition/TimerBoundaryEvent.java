package com.workflow.definition;

/**
 * 定时器边界事件定义
 * 
 * 定时器边界事件附加在 USER_TASK 上，当任务超时后触发。
 * 
 * @param nodeId 节点 ID
 * @param attachedToNodeId 附加到的任务节点 ID
 * @param durationMillis 超时时间（毫秒）
 * @param interrupting 是否中断任务（true=超时后取消任务，false=超时后触发分支但任务继续）
 */
public record TimerBoundaryEvent(
    String nodeId,
    String attachedToNodeId,
    long durationMillis,
    boolean interrupting
) {
    public TimerBoundaryEvent {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId 不能为空");
        }
        if (attachedToNodeId == null || attachedToNodeId.isBlank()) {
            throw new IllegalArgumentException("attachedToNodeId 不能为空");
        }
        if (durationMillis <= 0) {
            throw new IllegalArgumentException("durationMillis 必须大于 0");
        }
    }
}
