package com.workflow.runtime;

import com.workflow.enums.InstanceStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 流程实例 - 一次流程发起的完整运行时上下文
 *
 * 组成:
 *  processKey      - 关联的流程定义 key
 *  status          - 实例整体状态
 *  activeTokens    - 当前活跃的所有 Token(并行网关分裂时多个)
 *  tasks           - 该实例下产生的所有 TaskInstance
 *  variables       - 流程变量(动态上下文,贯穿整个流程)
 */
public final class ProcessInstance {
    private final String id;
    private final String processKey;
    /** 所属流程定义版本;0 表示未知(取最新版,兼容旧数据) */
    private final int processVersion;
    private final long createTime;
    private long endTime;
    private InstanceStatus status;
    private final Map<String, Token> activeTokens;
    private final List<TaskInstance> tasks;
    private final Map<String, Object> variables;
    /** 父流程上下文 - 子流程实例持有;普通实例为 null */
    private final String parentInstanceId;
    private final String parentTokenId;
    private final String parentNodeId;

    public ProcessInstance(String processKey) {
        this(processKey, 0);
    }

    public ProcessInstance(String processKey, int processVersion) {
        this(processKey, processVersion, null, null, null);
    }

    /** 构造器 - 子流程实例(携带父流程上下文) */
    public ProcessInstance(String processKey, int processVersion,
                           String parentInstanceId, String parentTokenId, String parentNodeId) {
        this.id = UUID.randomUUID().toString();
        this.processKey = Objects.requireNonNull(processKey);
        this.processVersion = processVersion;
        this.createTime = System.currentTimeMillis();
        this.status = InstanceStatus.RUNNING;
        this.activeTokens = new LinkedHashMap<>();
        this.tasks = new ArrayList<>();
        this.variables = new LinkedHashMap<>();
        this.parentInstanceId = parentInstanceId;
        this.parentTokenId = parentTokenId;
        this.parentNodeId = parentNodeId;
    }

    /**
     * 持久化层专用 - 从已加载的数据重建 ProcessInstance
     * 仓储层在单事务内调用,不需要反射覆盖 final 字段
     */
    public static ProcessInstance reconstruct(String id,
                                              String processKey,
                                              int processVersion,
                                              long createTime,
                                              Long endTime,
                                              InstanceStatus status,
                                              Map<String, Token> activeTokens,
                                              List<TaskInstance> tasks,
                                              Map<String, Object> variables,
                                              String parentInstanceId,
                                              String parentTokenId,
                                              String parentNodeId) {
        ProcessInstance instance = new ProcessInstance(processKey, processVersion,
                parentInstanceId, parentTokenId, parentNodeId);
        // 通过反射写 final 字段 - 这里 ProcessInstance 自己掌握,避免外部依赖反射 hack
        setFinal(instance, "id", id);
        setFinal(instance, "processKey", processKey);
        setFinal(instance, "processVersion", processVersion);
        setFinal(instance, "createTime", createTime);
        setFinal(instance, "activeTokens", new LinkedHashMap<>(activeTokens));
        setFinal(instance, "tasks", new ArrayList<>(tasks));
        setFinal(instance, "variables", new LinkedHashMap<>(variables));
        instance.status = status;
        if (endTime != null) {
            instance.endTime = endTime;
        }
        return instance;
    }

    /** 兼容旧签名 - 无父上下文 */
    public static ProcessInstance reconstruct(String id,
                                              String processKey,
                                              int processVersion,
                                              long createTime,
                                              Long endTime,
                                              InstanceStatus status,
                                              Map<String, Token> activeTokens,
                                              List<TaskInstance> tasks,
                                              Map<String, Object> variables) {
        return reconstruct(id, processKey, processVersion, createTime, endTime, status,
                activeTokens, tasks, variables, null, null, null);
    }

    /** 内部反射写 final 字段 - 用 VarHandle 友好的 setAccessible(true) 即可 */
    private static void setFinal(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception ex) {
            throw new RuntimeException("反射设置字段失败: " + fieldName, ex);
        }
    }

    public String getId() { return id; }
    public String getProcessKey() { return processKey; }
    public int getProcessVersion() { return processVersion; }
    public long getCreateTime() { return createTime; }
    public long getEndTime() { return endTime; }
    public InstanceStatus getStatus() { return status; }
    public String getParentInstanceId() { return parentInstanceId; }
    public String getParentTokenId() { return parentTokenId; }
    public String getParentNodeId() { return parentNodeId; }
    /** 是否为子流程实例 */
    public boolean isSubProcess() { return parentInstanceId != null; }
    public Map<String, Token> getActiveTokens() {
        return Collections.unmodifiableMap(activeTokens);
    }
    public List<TaskInstance> getTasks() {
        return Collections.unmodifiableList(tasks);
    }
    public Map<String, Object> getVariables() {
        return Collections.unmodifiableMap(variables);
    }

    public void addToken(Token token) {
        activeTokens.put(token.getId(), token);
    }

    public void consumeToken(String tokenId) {
        Token t = activeTokens.get(tokenId);
        if (t == null) {
            throw new IllegalStateException("Token 不存在或已消耗: " + tokenId);
        }
        t.setStatus(com.workflow.enums.TokenStatus.CONSUMED);
        activeTokens.remove(tokenId);
    }

    public void addTask(TaskInstance task) {
        tasks.add(task);
    }

    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }

    public Object getVariable(String key) {
        return variables.get(key);
    }

    public void markCompleted() {
        this.status = InstanceStatus.COMPLETED;
        this.endTime = System.currentTimeMillis();
    }

    public void markTerminated() {
        this.status = InstanceStatus.TERMINATED;
        this.endTime = System.currentTimeMillis();
    }

    public void suspend() {
        if (status != InstanceStatus.RUNNING) {
            throw new IllegalStateException("仅 RUNNING 实例可暂停,当前: " + status);
        }
        this.status = InstanceStatus.SUSPENDED;
    }

    public void resume() {
        if (status != InstanceStatus.SUSPENDED) {
            throw new IllegalStateException("仅 SUSPENDED 实例可恢复,当前: " + status);
        }
        this.status = InstanceStatus.RUNNING;
    }

    /** 当前是否已无活跃 Token(意味着流程结束或卡死) */
    public boolean isAllTokensConsumed() {
        return activeTokens.isEmpty();
    }

    @Override
    public String toString() {
        return "Instance[" + id.substring(0, 8) + " key=" + processKey + " status=" + status
                + " tokens=" + activeTokens.size() + " tasks=" + tasks.size() + "]";
    }
}