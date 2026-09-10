package com.workflow.definition;

import com.alibaba.fastjson2.annotation.JSONCreator;
import com.alibaba.fastjson2.annotation.JSONField;
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
    /** 仅 MESSAGE_EVENT 节点有值 - 消息事件定义 */
    private final MessageEvent messageEvent;
    /** 仅 SIGNAL_EVENT 节点有值 - 信号事件定义 */
    private final SignalEvent signalEvent;
    /** 仅 TIMER_BOUNDARY 节点有值 - 定时器边界事件定义 */
    private final TimerBoundaryEvent timerBoundaryEvent;
    /** 仅 DECISION 节点有值 - 决策表 ID */
    private final String decisionTableId;

    /** 构造器 - 普通节点(无子流程引用、无超时、无动态多实例) */
    private NodeDefinition(String id, String name, NodeType type, Candidate candidate) {
        this(id, name, type, candidate, null, 0, TimeoutPolicy.NONE, null, null, null, null, null, null, null);
    }

    /** 构造器 - 含子流程引用,无超时(兼容旧序列化数据) */
    private NodeDefinition(String id, String name, NodeType type, Candidate candidate, String subProcessKey) {
        this(id, name, type, candidate, subProcessKey, 0, TimeoutPolicy.NONE, null, null, null, null, null, null, null);
    }

    /** 构造器 - 含超时,无动态多实例(兼容旧序列化数据) */
    private NodeDefinition(String id, String name, NodeType type, Candidate candidate,
                           String subProcessKey, long timeoutMillis,
                           TimeoutPolicy timeoutPolicy, String timeoutTargetUserId) {
        this(id, name, type, candidate, subProcessKey, timeoutMillis, timeoutPolicy, timeoutTargetUserId, null, null, null, null, null, null);
    }

    /**
     * 构造器 - 含动态多实例(兼容旧序列化数据)
     */
    private NodeDefinition(String id, String name, NodeType type, Candidate candidate,
                           String subProcessKey, long timeoutMillis,
                           TimeoutPolicy timeoutPolicy, String timeoutTargetUserId,
                           String dynamicParallelVariable, CandidateStrategy dynamicParallelStrategy) {
        this(id, name, type, candidate, subProcessKey, timeoutMillis, timeoutPolicy, timeoutTargetUserId, 
             dynamicParallelVariable, dynamicParallelStrategy, null, null, null, null);
    }

    /**
     * 构造器 - 含事件(兼容旧序列化数据)
     */
    private NodeDefinition(String id, String name, NodeType type, Candidate candidate,
                           String subProcessKey, long timeoutMillis,
                           TimeoutPolicy timeoutPolicy, String timeoutTargetUserId,
                           String dynamicParallelVariable, CandidateStrategy dynamicParallelStrategy,
                           MessageEvent messageEvent, SignalEvent signalEvent, TimerBoundaryEvent timerBoundaryEvent) {
        this(id, name, type, candidate, subProcessKey, timeoutMillis, timeoutPolicy, timeoutTargetUserId,
             dynamicParallelVariable, dynamicParallelStrategy, messageEvent, signalEvent, timerBoundaryEvent, null);
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
                           String dynamicParallelVariable, CandidateStrategy dynamicParallelStrategy,
                           MessageEvent messageEvent, SignalEvent signalEvent, TimerBoundaryEvent timerBoundaryEvent,
                           String decisionTableId) {
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
        this.messageEvent = messageEvent;
        this.signalEvent = signalEvent;
        this.timerBoundaryEvent = timerBoundaryEvent;
        this.decisionTableId = decisionTableId;
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
        return new NodeDefinition(id, name, NodeType.DYNAMIC_PARALLEL, null, null, 0, TimeoutPolicy.NONE, null, variable, strategy, null, null, null, null);
    }

    /**
     * 创建消息事件节点
     *
     * @param id        节点 ID
     * @param name      节点名称
     * @param messageEvent 消息事件定义
     */
    public static NodeDefinition messageEvent(String id, String name, MessageEvent messageEvent) {
        Objects.requireNonNull(messageEvent, "messageEvent 不能为空");
        return new NodeDefinition(id, name, NodeType.MESSAGE_EVENT, null, null, 0, TimeoutPolicy.NONE, null, null, null, messageEvent, null, null, null);
    }

    /**
     * 创建信号事件节点
     *
     * @param id        节点 ID
     * @param name      节点名称
     * @param signalEvent 信号事件定义
     */
    public static NodeDefinition signalEvent(String id, String name, SignalEvent signalEvent) {
        Objects.requireNonNull(signalEvent, "signalEvent 不能为空");
        return new NodeDefinition(id, name, NodeType.SIGNAL_EVENT, null, null, 0, TimeoutPolicy.NONE, null, null, null, null, signalEvent, null, null);
    }

    /**
     * 创建定时器边界事件节点
     *
     * @param id        节点 ID
     * @param name      节点名称
     * @param timerBoundaryEvent 定时器边界事件定义
     */
    public static NodeDefinition timerBoundary(String id, String name, TimerBoundaryEvent timerBoundaryEvent) {
        Objects.requireNonNull(timerBoundaryEvent, "timerBoundaryEvent 不能为空");
        return new NodeDefinition(id, name, NodeType.TIMER_BOUNDARY, null, null, 0, TimeoutPolicy.NONE, null, null, null, null, null, timerBoundaryEvent, null);
    }

    /**
     * 创建决策节点
     *
     * @param id              节点 ID
     * @param name            节点名称
     * @param decisionTableId 决策表 ID
     */
    public static NodeDefinition decision(String id, String name, String decisionTableId) {
        Objects.requireNonNull(decisionTableId, "决策表 ID 不能为空");
        return new NodeDefinition(id, name, NodeType.DECISION, null, null, 0, TimeoutPolicy.NONE, null, null, null, null, null, null, decisionTableId);
    }

    /**
     * 创建多实例任务节点（OA 会签/或签）—— 运行时按集合变量展开为「每人一个独立任务」。
     *
     * <p>与 {@link #dynamicParallel} 的区别：dynamicParallel 把整个候选人集合挂到<b>一个</b>任务上；
     * 多实例为集合里<b>每个元素各建一个单候选人任务</b>，可逐人完成/加签/减签，
     * 完成判定按 {@code strategy}（ALL=全部完成、ANY=任一完成即取消其余）。
     *
     * @param id           节点 ID
     * @param name         节点名称
     * @param collectionVar 运行时集合变量名（List，元素为审批人）
     * @param strategy     完成策略 ANY/ALL
     */
    public static NodeDefinition multiInstance(String id, String name, String collectionVar, CandidateStrategy strategy) {
        Objects.requireNonNull(collectionVar, "多实例集合变量名不能为空");
        Objects.requireNonNull(strategy, "多实例完成策略不能为空");
        return new NodeDefinition(id, name, NodeType.MULTI_INSTANCE, null, null, 0, TimeoutPolicy.NONE, null, collectionVar, strategy, null, null, null, null);
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
            return new NodeDefinition(id, name, type, candidate, subProcessKey, 0, TimeoutPolicy.NONE, null, dynamicParallelVariable, dynamicParallelStrategy, messageEvent, signalEvent, timerBoundaryEvent, decisionTableId);
        }
        return new NodeDefinition(id, name, type, candidate, subProcessKey, millis, policy, targetUserId, dynamicParallelVariable, dynamicParallelStrategy, messageEvent, signalEvent, timerBoundaryEvent, decisionTableId);
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

    /** 消息事件定义 - 仅 MESSAGE_EVENT 节点有值 */
    public MessageEvent getMessageEvent() { return messageEvent; }

    /** 信号事件定义 - 仅 SIGNAL_EVENT 节点有值 */
    public SignalEvent getSignalEvent() { return signalEvent; }

    /** 定时器边界事件定义 - 仅 TIMER_BOUNDARY 节点有值 */
    public TimerBoundaryEvent getTimerBoundaryEvent() { return timerBoundaryEvent; }

    /** 决策表 ID - 仅 DECISION 节点有值 */
    public String getDecisionTableId() { return decisionTableId; }

    /** 是否为消息事件节点 */
    @JSONField(serialize = false)
    public boolean isMessageEvent() {
        return type == NodeType.MESSAGE_EVENT && messageEvent != null;
    }

    /** 是否为信号事件节点 */
    @JSONField(serialize = false)
    public boolean isSignalEvent() {
        return type == NodeType.SIGNAL_EVENT && signalEvent != null;
    }

    /** 是否为定时器边界事件节点 */
    @JSONField(serialize = false)
    public boolean isTimerBoundary() {
        return type == NodeType.TIMER_BOUNDARY && timerBoundaryEvent != null;
    }

    /** 是否为决策节点 */
    @JSONField(serialize = false)
    public boolean isDecision() {
        return type == NodeType.DECISION && decisionTableId != null;
    }

    /** 多实例集合变量名 - 仅 MULTI_INSTANCE 节点有值（复用 dynamicParallelVariable 字段） */
    public String getMultiInstanceCollection() { return dynamicParallelVariable; }

    /** 多实例完成策略 - 仅 MULTI_INSTANCE 节点有值（复用 dynamicParallelStrategy 字段） */
    public CandidateStrategy getMultiInstanceStrategy() { return dynamicParallelStrategy; }

    /** 是否为多实例任务节点 */
    @JSONField(serialize = false)
    public boolean isMultiInstance() {
        return type == NodeType.MULTI_INSTANCE && dynamicParallelVariable != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(type).append("[").append(id);
        if (candidate != null) sb.append(" ").append(candidate);
        if (subProcessKey != null) sb.append(" ->").append(subProcessKey);
        if (hasTimeout()) sb.append(" timeout=").append(timeoutMillis).append("ms/").append(getTimeoutPolicy());
        if (isDynamicParallel()) sb.append(" dynamic=").append(dynamicParallelVariable).append("/").append(dynamicParallelStrategy);
        if (isMessageEvent()) sb.append(" msg=").append(messageEvent.messageName());
        if (isSignalEvent()) sb.append(" sig=").append(signalEvent.signalName());
        if (isTimerBoundary()) sb.append(" timer=").append(timerBoundaryEvent.durationMillis()).append("ms");
        if (isDecision()) sb.append(" decision=").append(decisionTableId);
        sb.append("]");
        return sb.toString();
    }
}
