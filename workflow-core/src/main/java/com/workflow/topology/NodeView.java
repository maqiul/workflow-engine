package com.workflow.topology;

import com.workflow.enums.NodeType;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 节点只读视图
 * 
 * <p>只暴露必要字段，不暴露内部实现细节（如 Candidate 对象）。
 *
 * <p>{@code userIds} 与 {@code groupIds} 是<b>两条独立的线</b>：前者是具体审批人，
 * 后者是候选组名（运行时由调用方注入的 GroupResolver 展开成具体用户）。
 * 早先把组名并进 userIds，视图上就再也分不清"这是个人还是个组"。
 */
public final class NodeView {
    
    private final String id;
    private final String name;
    private final NodeType type;
    private final List<String> userIds;            // 仅 USER_TASK 有值（审批人列表）
    private final List<String> groupIds;           // 仅 USER_TASK 有值（候选组名，尚未展开）
    private final String assigneeVariable;         // 仅动态 assignee 有值
    private final String delegateKey;              // 仅 SERVICE_TASK 有值
    
    public NodeView(String id, String name, NodeType type, List<String> userIds,
                    List<String> groupIds, String assigneeVariable, String delegateKey) {
        this.id = Objects.requireNonNull(id);
        this.name = name;
        this.type = Objects.requireNonNull(type);
        this.userIds = userIds != null ? Collections.unmodifiableList(userIds) : Collections.emptyList();
        this.groupIds = groupIds != null ? Collections.unmodifiableList(groupIds) : Collections.emptyList();
        this.assigneeVariable = assigneeVariable;
        this.delegateKey = delegateKey;
    }
    
    public String getId() { return id; }
    public String getName() { return name; }
    public NodeType getType() { return type; }
    public List<String> getUserIds() { return userIds; }
    public List<String> getGroupIds() { return groupIds; }
    public String getAssigneeVariable() { return assigneeVariable; }
    public String getDelegateKey() { return delegateKey; }
    
    @Override
    public String toString() {
        return "NodeView{id='" + id + "', name='" + name + "', type=" + type + "}";
    }
}
