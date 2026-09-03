package com.workflow.definition;

import com.alibaba.fastjson2.annotation.JSONCreator;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.NodeType;
import com.workflow.enums.TimeoutPolicy;

import java.util.Objects;

/**
 * 节点定义 - 不可变的流程节点元数据
 * 由 ProcessBuilder 构建,流程启动后固化
 */
public final class NodeDefinition {
    private final String id;
    private final String name;
    private final NodeType type;
    /** 仅 USER_TASK 节点有值 */
    private final Candidate candidate;
    /** 仅 SUB_PROCESS 节点有值 - 引用的子流程定义 key */
    private final String subProcessKey;
    /** 超时毫秒数 - 0 表示不启用超时(仅 USER_TASK 有意义) */
    private final long timeoutMillis;
    /** 超时策略 - NONE 表示不启用超时 */
    private final TimeoutPolicy timeoutPolicy;
    /** 超时策略为 AUTO_TRANSFER 时的目标用户 */
    private final String timeoutTargetUserId;
    /** 仅 DYNAMIC_PARALLEL 节点有值 - 运行时从该变量获取候选人列表 */
    private final String dynamicParallelVariable;
    /** 仅 DYNAMIC_PARALLEL 节点有值 - 完成策略(ANY/ALL) */
    private final CandidateStrategy dynamicParallelStrategy;

    /** 构造器 - 普通节点(无子流程引用、无超时、无动态多实例) */
    private NodeDefinition(String id, String name, NodeType type, Candidate candidate) {
        this(id, name, type, candidate, null, 0, TimeoutPolicy.NONE, null, null, null);
    }

    /** 构造器 - 含子流程引用,无超时(兼容旧序列化数据) */
    private NodeDefinition(String id, String name, NodeType type, Candidate candidate, String subProcessKey) {
        this(id, name, type, candidate, subProcessKey, 0, TimeoutPolicy.NONE, null, null, null);
    }

    /** 构造器 - 含超时,无动态多实例(兼容旧序列化数据) */
    private NodeDefinition(String id, String name, NodeType type, Candidate candidate,
                           String subProcessKey, long timeoutMillis,
                           TimeoutPolicy timeoutPolicy, String timeoutTargetUserId) {
        this(id, name, type, candidate, subProcessKey, timeoutMillis, timeoutPolicy, timeoutTargetUserId, null, null);
    }

    /**
     * 构造器 - 完整字段
     * 用 @JSONCreator 让 fastjson2 明确按参数名反序列化
     * 旧数据缺少字段时 fastjson2 传默认值,getter 兜底
     */
    @JSONCreator
    private NodeDefinition(String id, String name, NodeType type, Candidate candidate,
                           String subProcessKey, long timeoutMillis,
                           TimeoutPolicy timeoutPolicy, String timeoutTargetUserId,
                           String dynamicParallelVariable, CandidateStrategy dynamicParallelStrategy) {
        this.id = Objects.requireNonNull(id, "id 不能为空");
        this.name = name != null ? name : id;
        this.type = Objects.requireNonNull(type, "type 不能为空");
        this.candidate = candidate;
        this.subProcessKey = subProcessKey;
        this.timeoutMillis = Math.max(0, timeoutMillis);
        this.timeoutPolicy = timeoutPolicy;
        this.timeoutTargetUserId = timeoutTargetUserId;
        this.dynamicParallelVariable = dynamicParallelVariable;
        this.dynamicParallelStrategy = dynamicParallelStrategy;
    }

    public static NodeDefinition start(String id) {
        return new NodeDefinition(id, id, NodeType.START, null);
    }

    public static NodeDefinition end(String id) {
        return new NodeDefinition(id, id, NodeType.END, null);
    }

    public static NodeDefinition userTask(String id, String name, Candidate candidate) {
        return new NodeDefinition(id, name, NodeType.USER_TASK, candidate);
    }

    public static NodeDefinition exclusiveGateway(String id) {
        return new NodeDefinition(id, id, NodeType.EXCLUSIVE_GATEWAY, null);
    }

    public static NodeDefinition parallelGateway(String id) {
        return new NodeDefinition(id, id, NodeType.PARALLEL_GATEWAY, null);
    }

    public static NodeDefinition subProcess(String id, String name, String processKey) {
        Objects.requireNonNull(processKey, "子流程引用 key 不能为空");
        return new NodeDefinition(id, name, NodeType.SUB_PROCESS, null, processKey);
    }

    /**
     * 创建动态多实例节点
     *
     * @param id        节点 ID
     * @param name      节点名称
     * @param variable  运行时变量名，从该变量获取候选人列表（List<String>）
     * @param strategy  完成策略（ANY/ALL）
     */
    public static NodeDefinition dynamicParallel(String id, String name, String variable, CandidateStrategy strategy) {
        Objects.requireNonNull(variable, "动态多实例变量名不能为空");
        Objects.requireNonNull(strategy, "动态多实例策略不能为空");
        return new NodeDefinition(id, name, NodeType.DYNAMIC_PARALLEL, null, null, 0, TimeoutPolicy.NONE, null, variable, strategy);
    }

    /**
     * 返回带超时配置的节点副本(不可变风格)
     */
    public NodeDefinition withTimeout(long millis, TimeoutPolicy policy) {
        return withTimeout(millis, policy, null);
    }

    /**
     * 返回带超时配置(含转办目标用户)的节点副本(不可变风格)
     */
    public NodeDefinition withTimeout(long millis, TimeoutPolicy policy, String targetUserId) {
        if (millis < 0) {
            throw new IllegalArgumentException("超时毫秒数不能为负数: " + millis);
        }
        if (policy == null || policy == TimeoutPolicy.NONE) {
            return new NodeDefinition(id, name, type, candidate, subProcessKey, 0, TimeoutPolicy.NONE, null, dynamicParallelVariable, dynamicParallelStrategy);
        }
        return new NodeDefinition(id, name, type, candidate, subProcessKey, millis, policy, targetUserId, dynamicParallelVariable, dynamicParallelStrategy);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public NodeType getType() { return type; }
    public Candidate getCandidate() { return candidate; }
    public String getSubProcessKey() { return subProcessKey; }
    public long getTimeoutMillis() { return timeoutMillis; }

    /** 超时策略 - null(旧数据)兜底为 NONE */
    public TimeoutPolicy getTimeoutPolicy() {
        return timeoutPolicy != null ? timeoutPolicy : TimeoutPolicy.NONE;
    }

    public String getTimeoutTargetUserId() { return timeoutTargetUserId; }

    /** 是否启用了超时(毫秒 > 0 且策略非 NONE) */
    public boolean hasTimeout() {
        return timeoutMillis > 0 && getTimeoutPolicy() != TimeoutPolicy.NONE;
    }

    /** 动态多实例变量名 - 仅 DYNAMIC_PARALLEL 节点有值 */
    public String getDynamicParallelVariable() { return dynamicParallelVariable; }

    /** 动态多实例完成策略 - 仅 DYNAMIC_PARALLEL 节点有值 */
    public CandidateStrategy getDynamicParallelStrategy() { return dynamicParallelStrategy; }

    /** 是否为动态多实例节点 */
    public boolean isDynamicParallel() {
        return type == NodeType.DYNAMIC_PARALLEL && dynamicParallelVariable != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(type).append("[").append(id);
        if (candidate != null) sb.append(" ").append(candidate);
        if (subProcessKey != null) sb.append(" ->").append(subProcessKey);
        if (hasTimeout()) sb.append(" timeout=").append(timeoutMillis).append("ms/").append(getTimeoutPolicy());
        if (isDynamicParallel()) sb.append(" dynamic=").append(dynamicParallelVariable).append("/").append(dynamicParallelStrategy);
        sb.append("]");
        return sb.toString();
    }
}
