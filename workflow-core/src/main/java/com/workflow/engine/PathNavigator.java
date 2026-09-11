package com.workflow.engine;

import com.workflow.definition.NodeDefinition;
import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.Transition;
import com.workflow.enums.NodeType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 路径导航器 - 用于找"上一个 UserTask"
 *
 * 简化算法:
 *  从当前节点反向 BFS,跳过非 UserTask 节点(START/END/网关),
 *  直到找到最近的 USER_TASK 节点。
 *  若当前节点已经是 UserTask,则找它前面的那个 UserTask。
 *
 * 注:这里不处理排他网关的分支语义,假定流程是单线串行 + 并行网关。
 *     如果以后需要支持复杂分支的回退,这里要重写。
 */
final class PathNavigator {

    private PathNavigator() {}

    /**
     * 找当前节点之前的最近一个 USER_TASK 节点
     * @return userTaskNodeId,若找不到返回 null
     */
    static String findPreviousUserTask(ProcessDefinition def, String fromNodeId) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        // 入参节点的反向入口(谁连到 fromNodeId)
        for (NodeDefinition n : def.getNodes().values()) {
            for (Transition t : def.getOutgoing(n.getId())) {
                if (t.getTo().equals(fromNodeId) && visited.add(n.getId())) {
                    queue.add(n.getId());
                }
            }
        }

        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (visited.contains(cur) && !cur.equals(fromNodeId)) {
                // 已访问的防御
            }
            NodeDefinition nd = def.getNode(cur);
            if (nd.getType() == NodeType.USER_TASK) {
                return cur;
            }
            // 继续向前
            for (NodeDefinition n : def.getNodes().values()) {
                for (Transition t : def.getOutgoing(n.getId())) {
                    if (t.getTo().equals(cur) && visited.add(n.getId())) {
                        queue.add(n.getId());
                    }
                }
            }
        }
        return null;
    }
}