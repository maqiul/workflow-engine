package com.workflow.definition;

import com.workflow.enums.NodeType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 流程定义 - 不可变的流程元数据
 * 描述一个完整流程的节点拓扑、起始节点、出口转移映射
 *
 * 校验规则:
 *  1. 必须有且仅有一个 START 节点
 *  2. 所有节点 id 唯一
 *  3. 所有 Transition 的 from/to 必须指向已定义的节点
 */
public final class ProcessDefinition {
    private final String key;
    private final String name;
    private final int version;
    private final Map<String, NodeDefinition> nodes;
    private final Map<String, List<Transition>> outgoing;  // nodeId -> 出口
    private final String startNodeId;
    private final List<VariableDefinition> variableDefinitions;  // 变量定义（可为 null）
    private final String tenantId;  // 租户 ID（多租户隔离，可为 null 表示全局）

    /** 构造器 - 仅供 ProcessBuilder 调用(默认版本 1，无变量定义，无租户) */
    public ProcessDefinition(String key,
                             String name,
                             Map<String, NodeDefinition> nodes,
                             Map<String, List<Transition>> outgoing,
                             String startNodeId) {
        this(key, name, 1, nodes, outgoing, startNodeId, null, null);
    }

    /** 构造器 - 指定版本（无变量定义，无租户） */
    public ProcessDefinition(String key,
                             String name,
                             int version,
                             Map<String, NodeDefinition> nodes,
                             Map<String, List<Transition>> outgoing,
                             String startNodeId) {
        this(key, name, version, nodes, outgoing, startNodeId, null, null);
    }

    /** 构造器 - 完整参数（含变量定义，无租户） */
    public ProcessDefinition(String key,
                             String name,
                             int version,
                             Map<String, NodeDefinition> nodes,
                             Map<String, List<Transition>> outgoing,
                             String startNodeId,
                             List<VariableDefinition> variableDefinitions) {
        this(key, name, version, nodes, outgoing, startNodeId, variableDefinitions, null);
    }

    /** 构造器 - 完整参数（含变量定义 + 租户） */
    public ProcessDefinition(String key,
                             String name,
                             int version,
                             Map<String, NodeDefinition> nodes,
                             Map<String, List<Transition>> outgoing,
                             String startNodeId,
                             List<VariableDefinition> variableDefinitions,
                             String tenantId) {
        this.key = Objects.requireNonNull(key, "key 不能为空");
        this.name = name != null ? name : key;
        this.version = version > 0 ? version : 1;
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        this.outgoing = Collections.unmodifiableMap(outgoing);
        this.startNodeId = Objects.requireNonNull(startNodeId, "startNodeId 不能为空");
        this.variableDefinitions = variableDefinitions != null
                ? Collections.unmodifiableList(variableDefinitions)
                : null;
        this.tenantId = tenantId;  // 可为 null，表示全局流程定义
    }

    public String getKey() { return key; }
    public String getName() { return name; }
    public int getVersion() { return version; }
    public Map<String, NodeDefinition> getNodes() { return nodes; }
    public List<Transition> getOutgoing(String nodeId) {
        return outgoing.getOrDefault(nodeId, List.of());
    }
    public NodeDefinition getNode(String nodeId) {
        NodeDefinition n = nodes.get(nodeId);
        if (n == null) {
            throw new IllegalArgumentException("节点不存在: " + nodeId);
        }
        return n;
    }
    public String getStartNodeId() { return startNodeId; }
    public String getTenantId() { return tenantId; }

    public boolean hasNode(String nodeId) {
        return nodes.containsKey(nodeId);
    }

    /** 收集所有 USER_TASK 节点 */
    public List<NodeDefinition> getUserTasks() {
        return nodes.values().stream()
                .filter(n -> n.getType() == NodeType.USER_TASK)
                .toList();
    }

    /** 获取变量定义列表（可能为 null） */
    public List<VariableDefinition> getVariableDefinitions() {
        return variableDefinitions;
    }

    /** 是否定义了变量 schema */
    public boolean hasVariableDefinitions() {
        return variableDefinitions != null && !variableDefinitions.isEmpty();
    }

    @Override
    public String toString() {
        return "ProcessDefinition[" + key + "] nodes=" + nodes.size();
    }
}