package com.workflow.query;

import com.workflow.engine.IWorkflowEngine;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 任务查询构建器 - 支持复杂条件查询
 *
 * <pre>
 * List&lt;TaskInstance&gt; tasks = TaskQuery.create()
 *     .candidate("u1")
 *     .processDefinitionKey("leave")
 *     .status(TaskStatus.PENDING)
 *     .orderByCreateTimeDesc()
 *     .list(engine);
 * </pre>
 *
 * <p>修复的四个问题：
 * <ol>
 *   <li>{@code queryAllTasks()} 原先直接 {@code return new ArrayList<>()} ——
 *       不带 processInstanceId 的查询<b>永远返回空</b>。现走 {@code engine.allTasks()}。</li>
 *   <li>{@code processDefinitionKey} / {@code processDefinitionVersion} /
 *       {@code processVariable} 三个条件字段原先定义了却从未参与 {@code matches()}，
 *       即过滤条件是空转的。现按任务的实例关联补齐。</li>
 *   <li>{@code orderByCreateTime()} 原先按 id 排序。真正原因是 domain
 *       {@code TaskInstance} 缺 {@code createTime} 字段（DB 侧一直有 {@code create_time}
 *       列，读回时被丢弃），补齐字段后排序才成立。</li>
 *   <li>{@code count()} 原先复用 {@code list()}，而 list 末尾带 skip/limit ——
 *       于是 {@code limit(5).count()} 最多返回 5，是个静默错数。现 count 走同一条
 *       过滤链但不经分页，得到真实命中数。</li>
 * </ol>
 */
public final class TaskQuery {

    private String assignee;
    private String candidateGroup;
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

    /** 按候选人过滤（我被直接列为候选人的任务） */
    public TaskQuery candidate(String userId) {
        this.assignee = userId;
        return this;
    }

    /**
     * 按候选组过滤（"我所在组的待办"）。
     *
     * <p>引擎不持有组织架构，所以这里传的是<b>组名</b>，不是用户 ID ——
     * 调用方先从自己的用户中心取出"我属于哪些组"，再对每个组各查一次。
     *
     * <p>与 {@link #candidate(String)} 的分工：后者匹配候选人的 {@code userIds}
     * （任务直接列了谁），本方法匹配 {@code groupIds}（任务的候选组里有没有这个组）。
     * 组在任务创建时已展开成具体用户，但原始组名会保留在候选信息里，
     * 因此两条路能查到同一批任务。
     */
    public TaskQuery candidateGroup(String groupId) {
        this.candidateGroup = groupId;
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

    /** 按流程变量过滤（精确匹配；数值类型按数值相等比较） */
    public TaskQuery processVariable(String key, Object value) {
        if (this.processVariables == null) {
            this.processVariables = new LinkedHashMap<>();
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

    // ========== 执行 ==========

    /**
     * 执行查询并返回分页后的结果。
     */
    public List<TaskInstance> list(IWorkflowEngine engine) {
        return sortedIfRequested(applyFilters(engine))
                .skip(offset != null ? offset : 0)
                .limit(limit != null ? limit : Long.MAX_VALUE)
                .collect(Collectors.toList());
    }

    /** 查询单个结果；无匹配返回 null，多条匹配抛异常（避免静默取第一条）。 */
    public TaskInstance singleResult(IWorkflowEngine engine) {
        List<TaskInstance> results = sortedIfRequested(applyFilters(engine))
                .limit(limit != null ? limit : Long.MAX_VALUE)
                .collect(Collectors.toList());
        if (results.isEmpty()) {
            return null;
        }
        if (results.size() > 1) {
            throw new IllegalStateException(
                    "singleResult 命中 " + results.size() + " 条，请收窄条件或改用 list()");
        }
        return results.get(0);
    }

    /**
     * 命中总数 —— <b>不受 {@code limit}/{@code offset} 影响</b>。
     *
     * <p>旧实现是 {@code list(engine).size()}，于是加了 limit 之后计数被一起截断，
     * 分页组件会据此少算总页数。
     */
    public long count(IWorkflowEngine engine) {
        return applyFilters(engine).count();
    }

    // ========== 内部 ==========

    /** 只应用过滤条件，不分页不排序：list() 与 count() 共用的前半段。 */
    private Stream<TaskInstance> applyFilters(IWorkflowEngine engine) {
        Map<String, ProcessInstance> instanceCache = new HashMap<>();
        return sourceTasks(engine)
                .filter(task -> matches(engine, instanceCache, task));
    }

    /**
     * 待过滤的任务来源。
     *
     * <p>指定了实例 id 就只扫该实例（多数场景），否则全量扫描。
     */
    private Stream<TaskInstance> sourceTasks(IWorkflowEngine engine) {
        if (processInstanceId != null) {
            ProcessInstance instance = engine.getInstance(processInstanceId);
            return instance.getTasks().stream();
        }
        return engine.allTasks().stream();
    }

    /**
     * 单条任务的过滤判定。
     *
     * <p>{@code instanceCache} 只在本次查询内有效：按 key / 版本 / 变量过滤需要
     * 任务的所属实例，而任务表本身不冗余这些字段。缓存避免 N 次重复回查，
     * 但不要把该 map 外传 —— 它反映的是查询开始时的快照。
     */
    private boolean matches(IWorkflowEngine engine,
                            Map<String, ProcessInstance> instanceCache,
                            TaskInstance task) {
        if (status != null && task.getStatus() != status) {
            return false;
        }
        if (nodeId != null && !nodeId.equals(task.getNodeId())) {
            return false;
        }
        if (assignee != null && !task.getCandidate().getUserIds().contains(assignee)) {
            return false;
        }
        if (candidateGroup != null && !task.getCandidate().getGroupIds().contains(candidateGroup)) {
            return false;
        }
        if (processInstanceId != null && !processInstanceId.equals(task.getInstanceId())) {
            return false;
        }

        boolean needInstance = processDefinitionKey != null
                || processDefinitionVersion != null
                || processVariables != null && !processVariables.isEmpty();
        if (!needInstance) {
            return true;
        }

        ProcessInstance instance = instanceCache.computeIfAbsent(
                task.getInstanceId(), engine::getInstance);
        if (instance == null) {
            return false;
        }
        if (processDefinitionKey != null && !processDefinitionKey.equals(instance.getProcessKey())) {
            return false;
        }
        if (processDefinitionVersion != null
                && processDefinitionVersion != instance.getProcessVersion()) {
            return false;
        }
        if (processVariables != null) {
            for (Map.Entry<String, Object> expected : processVariables.entrySet()) {
                Object actual = instance.getVariable(expected.getKey());
                if (!variableEquals(expected.getValue(), actual)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 流程变量比较。
     *
     * <p>数值要按数值相等判断而非按 {@code equals}：JSON 往返后 {@code 500} 可能变成
     * {@code Long} 或 {@code Double}，{@code Objects.equals(Integer, Long)} 直接为 false，
     * 会让"看起来一样"的过滤条件永远查不到东西。
     */
    private static boolean variableEquals(Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            return true;
        }
        if (expected instanceof Number en && actual instanceof Number an) {
            return en.doubleValue() == an.doubleValue();
        }
        return expected != null && actual != null
                && expected.getClass().isEnum() == actual.getClass().isEnum()
                && expected.toString().equals(actual.toString())
                && expected.getClass() == actual.getClass();
    }

    /** 未指定排序字段时保持仓储返回顺序，不擅自重排。 */
    private Stream<TaskInstance> sortedIfRequested(Stream<TaskInstance> source) {
        if (!"createTime".equals(orderByField)) {
            return source;
        }
        Comparator<TaskInstance> cmp =
                Comparator.comparingLong(TaskInstance::getCreateTime)
                        // 同一毫秒内用 id 兜底，保证结果稳定可复现
                        .thenComparing(TaskInstance::getId);
        return source.sorted(ascending ? cmp : cmp.reversed());
    }
}
