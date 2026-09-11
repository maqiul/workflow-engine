package com.workflow.topology;

import com.workflow.enums.InstanceStatus;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 运行中实例的拓扑视图（含当前 Token 位置）
 * 
 * <p>用于流程图高亮：activeNodeIds 是当前 Token 所在节点，completedNodeIds 是已完成节点。
 */
public final class InstanceTopologyView {
    
    private final TopologyView topology;
    private final List<String> activeNodeIds;     // 当前 Token 所在节点（高亮）
    private final List<String> completedNodeIds;  // 已完成节点（历史路径）
    private final InstanceStatus status;
    
    public InstanceTopologyView(TopologyView topology, List<String> activeNodeIds,
                               List<String> completedNodeIds, InstanceStatus status) {
        this.topology = Objects.requireNonNull(topology);
        this.activeNodeIds = Collections.unmodifiableList(Objects.requireNonNull(activeNodeIds));
        this.completedNodeIds = Collections.unmodifiableList(Objects.requireNonNull(completedNodeIds));
        this.status = Objects.requireNonNull(status);
    }
    
    public TopologyView getTopology() { return topology; }
    public List<String> getActiveNodeIds() { return activeNodeIds; }
    public List<String> getCompletedNodeIds() { return completedNodeIds; }
    public InstanceStatus getStatus() { return status; }
    
    @Override
    public String toString() {
        return "InstanceTopologyView{status=" + status + 
               ", active=" + activeNodeIds + ", completed=" + completedNodeIds + "}";
    }
}
