package com.workflow.builder;

import com.workflow.definition.Candidate;
import com.workflow.definition.MessageEvent;
import com.workflow.definition.NodeDefinition;
import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.SignalEvent;
import com.workflow.definition.TimerBoundaryEvent;
import com.workflow.definition.Transition;
import com.workflow.definition.VariableDefinition;
import com.workflow.definition.VariableType;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.NodeType;
import com.workflow.enums.TimeoutPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流程定义构建器 - 链式 DSL
 *
 * 用法示例:
 * <pre>{@code
 * ProcessDefinition pd = ProcessBuilder.create("leave", "请假审批")
 *     .start("start")
 *     .userTask("apply", "提交申请", Candidate.ofAny("employee"))
 *     .userTask("manager", "经理审批", Candidate.ofAny("managerA", "managerB"))
 *     .userTask("hr", "HR 审批", Candidate.ofAny("hr"))
 *     .end("end")
 *     .connect("start", "apply")
 *     .connect("apply", "manager")
 *     .connect("manager", "hr")
 *     .connect("hr", "end")
 *     .build();
 * }</pre>
 *
 * 设计原则:
 *  1. 构建期所有操作可重复(覆盖式),构建期不做合法性检查
 *  2. build() 时一次性校验全部完整性
 *  3. lastNode 游标简化链式调用,但 connect() 始终需要显式指定
 */
public class ProcessBuilder {

    private final String key;
    private final String name;
    private int version = 1;
    private final Map<String, NodeDefinition> nodes = new LinkedHashMap<>();
    private final List<Transition> transitions = new ArrayList<>();
    private final List<VariableDefinition> variableDefinitions = new ArrayList<>();
    private String startNodeId;

    private ProcessBuilder(String key, String name) {
        this.key = key;
        this.name = name;
    }

    public static ProcessBuilder create(String key) {
        return new ProcessBuilder(key, null);
    }

    public static ProcessBuilder create(String key, String name) {
        return new ProcessBuilder(key, name);
    }

    /** 指定流程版本(默认 1) */
    public ProcessBuilder version(int version) {
        if (version <= 0) {
            throw new IllegalArgumentException("版本号必须为正整数: " + version);
        }
        this.version = version;
        return this;
    }

    /** 注册起始节点 - 必须调用一次 */
    public ProcessBuilder start(String id) {
        checkDuplicate(id);
        nodes.put(id, NodeDefinition.start(id));
        this.startNodeId = id;
        return this;
    }

    /** 注册结束节点 */
    public ProcessBuilder end(String id) {
        checkDuplicate(id);
        nodes.put(id, NodeDefinition.end(id));
        return this;
    }

    /** 注册用户任务节点 */
    public ProcessBuilder userTask(String id, String displayName, Candidate candidate) {
        checkDuplicate(id);
        nodes.put(id, NodeDefinition.userTask(id, displayName, candidate));
        return this;
    }

    /** 注册用户任务节点（动态 assignee：运行时从变量取办理人） */
    public ProcessBuilder userTask(String id, String displayName, String assigneeVariable) {
        checkDuplicate(id);
        nodes.put(id, NodeDefinition.userTask(id, displayName, assigneeVariable));
        return this;
    }

    /** 注册服务任务节点（自动执行 delegate，执行完自动推进） */
    public ProcessBuilder serviceTask(String id, String displayName, String delegateKey) {
        checkDuplicate(id);
        nodes.put(id, NodeDefinition.serviceTask(id, displayName, delegateKey));
        return this;
    }

    /** 注册排他网关 */
    public ProcessBuilder exclusiveGateway(String id) {
        checkDuplicate(id);
        nodes.put(id, NodeDefinition.exclusiveGateway(id));
        return this;
    }

    /** 注册并行网关 */
    public ProcessBuilder parallelGateway(String id) {
        checkDuplicate(id);
        nodes.put(id, NodeDefinition.parallelGateway(id));
        return this;
    }

    /** 注册子流程节点 - 引用另一个流程定义的 key */
    public ProcessBuilder subProcess(String id, String displayName, String processKey) {
        checkDuplicate(id);
        nodes.put(id, NodeDefinition.subProcess(id, displayName, processKey));
        return this;
    }

    /**
     * 注册动态多实例节点 - 运行时根据变量动态创建多个任务
     *
     * @param id        节点 ID
     * @param displayName 节点显示名称
     * @param variable  运行时变量名，从该变量获取候选人列表（List<String>）
     * @param strategy  完成策略（ANY/ALL）
     */
    public ProcessBuilder dynamicParallel(String id, String displayName, String variable, CandidateStrategy strategy) {
        checkDuplicate(id);
        nodes.put(id, NodeDefinition.dynamicParallel(id, displayName, variable, strategy));
        return this;
    }

    /**
     * 多实例任务节点（OA 会签/或签）：运行时按集合变量为每个审批人各建一个独立任务。
     *
     * @param id           节点 ID
     * @param displayName  节点名称
     * @param variable     集合变量名（List，元素为审批人）
     * @param strategy     完成策略 ANY(或签)/ALL(会签)
     */
    public ProcessBuilder multiInstance(String id, String displayName, String variable, CandidateStrategy strategy) {
        checkDuplicate(id);
        nodes.put(id, NodeDefinition.multiInstance(id, displayName, variable, strategy));
        return this;
    }

    /**
     * 注册消息事件节点 - 等待外部消息触发
     *
     * @param id        节点 ID
     * @param displayName 节点显示名称
     * @param messageName 消息名称（用于路由）
     * @param correlationKeyExpression 关联键表达式（从流程变量中提取，用于匹配消息到实例）
     */
    public ProcessBuilder messageEvent(String id, String displayName, String messageName, String correlationKeyExpression) {
        checkDuplicate(id);
        nodes.put(id, NodeDefinition.messageEvent(id, displayName, 
            new MessageEvent(id, messageName, correlationKeyExpression)));
        return this;
    }

    /**
     * 注册信号事件节点 - 广播式信号，多个流程可以监听
     *
     * @param id        节点 ID
     * @param displayName 节点显示名称
     * @param signalName 信号名称（用于路由）
     */
    public ProcessBuilder signalEvent(String id, String displayName, String signalName) {
        checkDuplicate(id);
        nodes.put(id, NodeDefinition.signalEvent(id, displayName, 
            new SignalEvent(id, signalName)));
        return this;
    }

    /**
     * 注册定时器边界事件节点 - 附加在任务节点上，超时后触发
     *
     * @param id        节点 ID
     * @param displayName 节点显示名称
     * @param attachedToNodeId 附加到的任务节点 ID
     * @param durationMillis 超时时间（毫秒）
     * @param interrupting 是否中断任务（true=超时后取消任务，false=超时后触发分支但任务继续）
     */
    public ProcessBuilder timerBoundary(String id, String displayName, String attachedToNodeId, long durationMillis, boolean interrupting) {
        checkDuplicate(id);
        nodes.put(id, NodeDefinition.timerBoundary(id, displayName, 
            new TimerBoundaryEvent(id, attachedToNodeId, durationMillis, interrupting)));
        return this;
    }

    /**
     * 注册决策节点 - 基于 DMN 决策表进行业务规则决策
     *
     * @param id              节点 ID
     * @param displayName     节点显示名称
     * @param decisionTableId 决策表 ID
     */
    public ProcessBuilder decision(String id, String displayName, String decisionTableId) {
        checkDuplicate(id);
        nodes.put(id, NodeDefinition.decision(id, displayName, decisionTableId));
        return this;
    }

    /**
     * 为已注册的 UserTask 节点配置超时策略
     *
     * @param id        UserTask 节点 id
     * @param millis    超时毫秒数(>0 启用)
     * @param policy    超时策略(AUTO_APPROVE / AUTO_REJECT / AUTO_TERMINATE / AUTO_TRANSFER)
     */
    public ProcessBuilder timeout(String id, long millis, TimeoutPolicy policy) {
        return timeout(id, millis, policy, null);
    }

    /**
     * 为已注册的 UserTask 节点配置超时策略(含自动转办目标)
     */
    public ProcessBuilder timeout(String id, long millis, TimeoutPolicy policy, String targetUserId) {
        NodeDefinition old = nodes.get(id);
        if (old == null) {
            throw new IllegalStateException("节点不存在: " + id);
        }
        nodes.put(id, old.withTimeout(millis, policy, targetUserId));
        return this;
    }

    /** 添加无条件转移 */
    public ProcessBuilder connect(String from, String to) {
        transitions.add(new Transition(from, to));
        return this;
    }

    /** 添加带条件的转移(用于排他网关) */
    public ProcessBuilder connect(String from, String to, String condition) {
        transitions.add(new Transition(from, to, condition));
        return this;
    }

    /** 一次性添加多条转移 */
    public ProcessBuilder connectAll(String from, String... tos) {
        for (String to : tos) {
            transitions.add(new Transition(from, to));
        }
        return this;
    }

    /**
     * 定义流程变量（类型安全）
     *
     * @param name 变量名
     * @param type 变量类型
     * @return this（链式调用）
     */
    public ProcessBuilder variable(String name, VariableType type) {
        variableDefinitions.add(VariableDefinition.builder(name, type).build());
        return this;
    }

    /**
     * 定义流程变量（完整参数）
     *
     * @param varDef 变量定义
     * @return this（链式调用）
     */
    public ProcessBuilder variable(VariableDefinition varDef) {
        variableDefinitions.add(varDef);
        return this;
    }

    /**
     * 构建 ProcessDefinition - 一次性校验所有完整性
     */
    public ProcessDefinition build() {
        // 1. 必须有 start 节点
        if (startNodeId == null) {
            throw new IllegalStateException("流程 [" + key + "] 未定义起始节点,请先调用 start(id)");
        }

        // 2. 校验 transition 端点必须指向已存在节点
        Set<String> nodeIds = nodes.keySet();
        for (Transition t : transitions) {
            if (!nodeIds.contains(t.getFrom())) {
                throw new IllegalStateException("流程 [" + key + "] 转移起点不存在: " + t);
            }
            if (!nodeIds.contains(t.getTo())) {
                throw new IllegalStateException("流程 [" + key + "] 转移终点不存在: " + t);
            }
        }

        // 3. 校验 USER_TASK 节点必须有出口(避免卡死)
        Map<String, List<Transition>> outgoing = new LinkedHashMap<>();
        for (Transition t : transitions) {
            outgoing.computeIfAbsent(t.getFrom(), k -> new ArrayList<>()).add(t);
        }
        for (NodeDefinition n : nodes.values()) {
            if (n.getType() == NodeType.USER_TASK
                    && outgoing.getOrDefault(n.getId(), List.of()).isEmpty()) {
                throw new IllegalStateException(
                        "流程 [" + key + "] USER_TASK 节点 " + n.getId() + " 没有任何出口,会卡死");
            }
            if (n.getType() == NodeType.DYNAMIC_PARALLEL
                    && outgoing.getOrDefault(n.getId(), List.of()).isEmpty()) {
                throw new IllegalStateException(
                        "流程 [" + key + "] DYNAMIC_PARALLEL 节点 " + n.getId() + " 没有任何出口,会卡死");
            }
            if (n.getType() == NodeType.SUB_PROCESS && n.getSubProcessKey() == null) {
                throw new IllegalStateException(
                        "流程 [" + key + "] SUB_PROCESS 节点 " + n.getId() + " 未指定子流程 key");
            }
            if (n.getType() == NodeType.DYNAMIC_PARALLEL && n.getDynamicParallelVariable() == null) {
                throw new IllegalStateException(
                        "流程 [" + key + "] DYNAMIC_PARALLEL 节点 " + n.getId() + " 未指定变量名");
            }
            if (n.hasTimeout() && n.getType() != NodeType.USER_TASK) {
                throw new IllegalStateException(
                        "流程 [" + key + "] 只有 USER_TASK 节点支持超时配置: " + n.getId());
            }
            if (n.getTimeoutPolicy() == TimeoutPolicy.AUTO_TRANSFER
                    && (n.getTimeoutTargetUserId() == null || n.getTimeoutTargetUserId().isBlank())) {
                throw new IllegalStateException(
                        "流程 [" + key + "] AUTO_TRANSFER 超时策略必须指定目标用户: " + n.getId());
            }
            // USER_TASK 互斥校验：candidate 和 assigneeVariable 不能同时有值
            if (n.getType() == NodeType.USER_TASK) {
                boolean hasCandidate = n.getCandidate() != null;
                boolean hasAssigneeVar = n.getAssigneeVariable() != null;
                if (hasCandidate && hasAssigneeVar) {
                    throw new IllegalStateException(
                            "流程 [" + key + "] USER_TASK 节点 " + n.getId() + " 不能同时指定 candidate 和 assigneeVariable");
                }
                if (!hasCandidate && !hasAssigneeVar) {
                    throw new IllegalStateException(
                            "流程 [" + key + "] USER_TASK 节点 " + n.getId() + " 必须指定 candidate 或 assigneeVariable");
                }
            }
        }

        return new ProcessDefinition(key, name, version, nodes, outgoing, startNodeId,
                variableDefinitions.isEmpty() ? null : new ArrayList<>(variableDefinitions));
    }

    private void checkDuplicate(String id) {
        if (nodes.containsKey(id)) {
            throw new IllegalStateException("节点 id 重复: " + id);
        }
    }
}