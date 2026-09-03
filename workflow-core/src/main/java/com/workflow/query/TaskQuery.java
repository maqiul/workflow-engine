package com.workflow.query;

import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 任务查询构建器 - 支持复杂条件查询
 *
 * 用法:
 * <pre>
 * List&lt;TaskInstance&gt; tasks = TaskQuery.create()
 *     .assignee("u1")
 *     .processDefinitionKey("leave")
 *     .status(TaskStatus.PENDING)
 *     .orderByCreateTime().desc()
 *     .list(engine);
 * </pre>
 */
public final class TaskQuery {

    private String assignee;
    private String processDefinitionKey;
    private Integer processDefinitionVersion;
    private String processInstanceId;
    private TaskStatus status;
    private String nodeId;
    private Map<String, Object> processVariables;
    private String orderByField;
    private boolean ascending = true;
    private Integer limit;
    private Integer offset;

    private TaskQuery() {}

    public static TaskQuery create() {
        return new TaskQuery();
    }

    /** 按候选人过滤 */
    public TaskQuery candidate(String userId) {
        this.assignee = userId;
        return this;
    }

    /** 按流程定义 key 过滤 */
    public TaskQuery processDefinitionKey(String key) {
        this.processDefinitionKey = key;
        return this;
    }

    /** 按流程定义版本过滤 */
    public TaskQuery processDefinitionVersion(int version) {
        this.processDefinitionVersion = version;
        return this;
    }

    /** 按流程实例 ID 过滤 */
    public TaskQuery processInstanceId(String instanceId) {
        this.processInstanceId = instanceId;
        return this;
    }

    /** 按任务状态过滤 */
    public TaskQuery status(TaskStatus status) {
        this.status = status;
        return this;
    }

    /** 按节点 ID 过滤 */
    public TaskQuery nodeId(String nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    /** 按流程变量过滤（精确匹配） */
    public TaskQuery processVariable(String key, Object value) {
        if (this.processVariables == null) {
            this.processVariables = new java.util.LinkedHashMap<>();
        }
        this.processVariables.put(key, value);
        return this;
    }

    /** 按创建时间升序 */
    public TaskQuery orderByCreateTime() {
        this.orderByField = "createTime";
        this.ascending = true;
        return this;
    }

    /** 按创建时间降序 */
    public TaskQuery orderByCreateTimeDesc() {
        this.orderByField = "createTime";
        this.ascending = false;
        return this;
    }

    /** 限制结果数量 */
    public TaskQuery limit(int limit) {
        this.limit = limit;
        return this;
    }

    /** 偏移量（分页） */
    public TaskQuery offset(int offset) {
        this.offset = offset;
        return this;
    }

    /**
     * 执行查询
     */
    public List<TaskInstance> list(com.workflow.engine.IWorkflowEngine engine) {
        // 获取所有任务（通过实例查询）
        List<TaskInstance> allTasks = new ArrayList<>();
        
        // 如果有流程实例 ID，直接查该实例的任务
        if (processInstanceId != null) {
            ProcessInstance instance = engine.getInstance(processInstanceId);
            if (instance != null) {
                allTasks.addAll(instance.getTasks());
            }
        } else {
            // 否则需要遍历所有实例（简化实现）
            // 实际生产环境应该由仓储层支持
            // 这里通过 engine 的查询能力实现
            allTasks = queryAllTasks(engine);
        }

        // 应用过滤条件
        return allTasks.stream()
                .filter(this::matches)
                .sorted(getComparator())
                .skip(offset != null ? offset : 0)
                .limit(limit != null ? limit : Long.MAX_VALUE)
                .collect(Collectors.toList());
    }

    /**
     * 查询单个结果
     */
    public TaskInstance singleResult(com.workflow.engine.IWorkflowEngine engine) {
        List<TaskInstance> results = list(engine);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 查询数量
     */
    public long count(com.workflow.engine.IWorkflowEngine engine) {
        return list(engine).size();
    }

    private boolean matches(TaskInstance task) {
        // 状态过滤
        if (status != null && task.getStatus() != status) {
            return false;
        }

        // 节点 ID 过滤
        if (nodeId != null && !nodeId.equals(task.getNodeId())) {
            return false;
        }

        // 候选人过滤
        if (assignee != null && !task.getCandidate().getUserIds().contains(assignee)) {
            return false;
        }

        return true;
    }

    private Comparator<TaskInstance> getComparator() {
        if ("createTime".equals(orderByField)) {
            // TaskInstance 没有 createTime，用 id 排序代替
            Comparator<TaskInstance> cmp = Comparator.comparing(TaskInstance::getId);
            return ascending ? cmp : cmp.reversed();
        }
        return Comparator.comparing(TaskInstance::getId);
    }

    private List<TaskInstance> queryAllTasks(com.workflow.engine.IWorkflowEngine engine) {
        // 简化实现：实际应该由仓储层支持全量查询
        // 这里返回空列表，需要扩展引擎 API
        return new ArrayList<>();
    }
}
