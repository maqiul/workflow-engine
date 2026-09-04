package com.workflow.definition;

/**
 * 信号事件定义
 * 
 * 信号事件是广播式的，多个流程实例可以监听同一个信号。
 * 当信号发出时，所有监听了该信号的流程实例都会被触发。
 * 
 * @param nodeId 节点 ID
 * @param signalName 信号名称（用于路由）
 */
public record SignalEvent(
    String nodeId,
    String signalName
) {
    public SignalEvent {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId 不能为空");
        }
        if (signalName == null || signalName.isBlank()) {
            throw new IllegalArgumentException("signalName 不能为空");
        }
    }
}
