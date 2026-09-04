package com.workflow.definition;

/**
 * 消息事件定义
 * 
 * 消息事件用于等待外部系统发送消息。消息通过 correlationKey 匹配到具体的流程实例。
 * 
 * @param nodeId 节点 ID
 * @param messageName 消息名称（用于路由）
 * @param correlationKeyExpression 关联键表达式（从流程变量中提取，用于匹配消息到实例）
 */
public record MessageEvent(
    String nodeId,
    String messageName,
    String correlationKeyExpression
) {
    public MessageEvent {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId 不能为空");
        }
        if (messageName == null || messageName.isBlank()) {
            throw new IllegalArgumentException("messageName 不能为空");
        }
        if (correlationKeyExpression == null || correlationKeyExpression.isBlank()) {
            throw new IllegalArgumentException("correlationKeyExpression 不能为空");
        }
    }
}
