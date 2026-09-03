package com.workflow.engine;

import com.workflow.definition.NodeDefinition;
import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.Transition;
import com.workflow.enums.NodeType;

/**
 * 辅助判定并行网关是 fork 还是 join
 *
 * 简化规则(假设流程图合法):
 *  - fork: 一个入口 Token,需要分裂成多个出口 Token
 *    => 出口数 > 1(并行分裂)
 *  - join: 多个入口 Token,需要消耗后才能往下走
 *    => 入口数 > 1(并行汇聚)
 *
 * 注:对于单纯"过路"的并行网关(进出都 = 1)按 fork 处理,分裂一次再消耗一次,等价于 pass-through
 */
final class GatewayKind {
    static final String UNKNOWN = "UNKNOWN";

    private GatewayKind() {}

    static boolean isFork(ProcessDefinition def, String gatewayId) {
        NodeDefinition n = def.getNode(gatewayId);
        if (n.getType() != NodeType.PARALLEL_GATEWAY) return false;
        return def.getOutgoing(gatewayId).size() > 1;
    }

    static boolean isJoin(ProcessDefinition def, String gatewayId) {
        NodeDefinition n = def.getNode(gatewayId);
        if (n.getType() != NodeType.PARALLEL_GATEWAY) return false;
        // join: 找反向 - 有多少其他节点的出口指向自己
        int inbound = 0;
        for (NodeDefinition other : def.getNodes().values()) {
            for (Transition t : def.getOutgoing(other.getId())) {
                if (t.getTo().equals(gatewayId)) inbound++;
            }
        }
        return inbound > 1;
    }
}