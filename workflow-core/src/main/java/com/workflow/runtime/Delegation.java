package com.workflow.runtime;

import java.util.Objects;

/**
 * 委托关系 - A 委托 B 代为审批
 *
 * 字段说明:
 *  delegator   - 委托人（原审批人）
 *  delegate    - 代理人（代为审批的人）
 *  nodeId      - 可选，指定委托的节点（null 表示全局委托）
 *  processKey  - 可选，指定委托的流程（null 表示所有流程）
 */
public final class Delegation {
    private final String delegator;
    private final String delegate;
    private final String nodeId;      // 可选
    private final String processKey;  // 可选

    public Delegation(String delegator, String delegate) {
        this(delegator, delegate, null, null);
    }

    public Delegation(String delegator, String delegate, String nodeId, String processKey) {
        this.delegator = Objects.requireNonNull(delegator, "委托人不能为空");
        this.delegate = Objects.requireNonNull(delegate, "代理人不能为空");
        this.nodeId = nodeId;
        this.processKey = processKey;
    }

    public String getDelegator() { return delegator; }
    public String getDelegate() { return delegate; }
    public String getNodeId() { return nodeId; }
    public String getProcessKey() { return processKey; }

    /**
     * 检查该委托关系是否匹配指定任务
     */
    public boolean matches(String targetNodeId, String targetProcessKey) {
        // 节点匹配（null 表示全局）
        if (nodeId != null && !nodeId.equals(targetNodeId)) {
            return false;
        }
        // 流程匹配（null 表示所有流程）
        if (processKey != null && !processKey.equals(targetProcessKey)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "Delegation[" + delegator + " -> " + delegate +
                (nodeId != null ? " node=" + nodeId : "") +
                (processKey != null ? " process=" + processKey : "") + "]";
    }
}
