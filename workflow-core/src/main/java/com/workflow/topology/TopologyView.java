package com.workflow.topology;

import com.workflow.enums.NodeType;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 流程定义拓扑只读视图
 * 
 * <p>用于流程图渲染、待办列表显示、审批历史展示等场景。
 * 所有字段 final，返回不可变集合，可安全序列化。
 */
public final class TopologyView {
    
    private final String processKey;
    private final int version;
    private final String name;
    private final List<NodeView> nodes;
    private final List<TransitionView> transitions;
    
    public TopologyView(String processKey, int version, String name,
                       List<NodeView> nodes, List<TransitionView> transitions) {
        this.processKey = Objects.requireNonNull(processKey);
        this.version = version;
        this.name = name;
        this.nodes = Collections.unmodifiableList(Objects.requireNonNull(nodes));
        this.transitions = Collections.unmodifiableList(Objects.requireNonNull(transitions));
    }
    
    public String getProcessKey() { return processKey; }
    public int getVersion() { return version; }
    public String getName() { return name; }
    public List<NodeView> getNodes() { return nodes; }
    public List<TransitionView> getTransitions() { return transitions; }
    
    @Override
    public String toString() {
        return "TopologyView{key='" + processKey + "', v=" + version + 
               ", nodes=" + nodes.size() + ", transitions=" + transitions.size() + "}";
    }
}
