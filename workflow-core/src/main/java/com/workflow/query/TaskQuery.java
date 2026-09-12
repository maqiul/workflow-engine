package com.workflow.query;

import com.workflow.engine.IWorkflowEngine;
import com.workflow.enums.TaskStatus;
import com.workflow.repository.TaskFilter;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 *       不带 processInstanceId 的查询<b>永远返回空</b>。现走仓储查询。</li>
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
 *
 * <p><b>条件下推</b>：本类不再把整张任务表拉进内存过滤。可由 SQL 表达的条件
 * （实例 id / 状态 / 节点 / 候选人）交给 {@link TaskFilter} 下推到仓储；
 * 只有流程 key、定义版本、流程变量这三个「长在实例上」的条件才在内存里补做 ——
 * 任务表没有冗余这三列，下推不了。分页也随之下沉，但只在<b>全部条件都能下推</b>时
 * 才让仓储截断，否则会漏数据。
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
        return execute(engine, true);
    }

    /** 查询单个结果；无匹配返回 null，多条匹配抛异常（避免静默取第一条）。 */
    public TaskInstance singleResult(IWorkflowEngine engine) {
        List<TaskInstance> results = execute(engine, false);
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
        if (canPushDownCompletely()) {
            return engine.countTasks(buildFilter());
        }
        return filterByInstance(engine, engine.findTasks(buildFilter())).size();
    }

    // ========== 内部 ==========

    /**
     * 查询执行：能下推的条件交给仓储，下推不了的在内存里补。
     *
     * <p>仓储侧已经完成过滤、排序（{@code findTasks} 的契约），所以这里只在
     * 「还有条件下推不了」时补一道内存过滤，并补上分页。
     *
     * @param paged 是否应用 {@code offset}。{@code singleResult} 刻意不分页 ——
     *              它要回答的是「到底命中几条」而不是「第一页有几条」，
     *              带上 offset 会把本该报出来的多条冲突藏掉
     */
    private List<TaskInstance> execute(IWorkflowEngine engine, boolean paged) {
        if (canPushDownCompletely()) {
            return engine.findTasks(buildFilter()
                    .limit(limit)
                    .offset(paged ? offset : null));
        }
        List<TaskInstance> tasks = filterByInstance(engine, engine.findTasks(buildFilter()));
        if (paged) {
            return buildFilter().limit(limit).offset(offset).finish(tasks);
        }
        return limit == null ? tasks : tasks.stream().limit(limit).toList();
    }

    /**
     * 构造可下推条件。
     *
     * <p>{@code limit}/{@code offset} 刻意<b>不</b>在这里带上：调用方要在
     * {@link #execute} 里决定给不给。仓储实现自己也不会把 limit 下推到 SQL ——
     * 除非没有候选人条件（见 {@link TaskFilter#canPushDownLimit()}）。
     */
    private TaskFilter buildFilter() {
        TaskFilter filter = TaskFilter.create()
                .instanceId(processInstanceId)
                .status(status)
                .nodeId(nodeId)
                .candidateUser(assignee)
                .candidateGroup(candidateGroup);
        if ("createTime".equals(orderByField)) {
            filter = ascending ? filter.orderByCreateTimeAsc() : filter.orderByCreateTimeDesc();
        }
        return filter;
    }

    /**
     * 是否全部条件都能下推。
     *
     * <p>流程 key、定义版本、流程变量这三者长在实例上，任务表没有冗余这些列 ——
     * 只要用到其中任何一个，就必须回到内存里按实例补齐，分页也就不能提前截断。
     */
    private boolean canPushDownCompletely() {
        return processDefinitionKey == null
                && processDefinitionVersion == null
                && (processVariables == null || processVariables.isEmpty());
    }

    /**
     * 内存补过滤：只处理下推不了的那三个条件。
     *
     * <p>可下推条件（状态 / 节点 / 实例 / 候选人）已经由仓储过滤过了，这里不再重复判定 ——
     * 重复判定本身无害，但会让「哪个条件归谁管」变得含糊，改动时容易两边不一致。
     */
    private List<TaskInstance> filterByInstance(IWorkflowEngine engine,
                                                List<TaskInstance> tasks) {
        Map<String, ProcessInstance> instanceCache = new HashMap<>();
        List<TaskInstance> out = new ArrayList<>(tasks.size());
        for (TaskInstance task : tasks) {
            if (matchesInstanceConditions(engine, instanceCache, task)) {
                out.add(task);
            }
        }
        return out;
    }

    /**
     * 实例相关条件的判定。
     *
     * <p>{@code instanceCache} 只在本次查询内有效：按 key / 版本 / 变量过滤需要
     * 任务的所属实例，而任务表本身不冗余这些字段。缓存避免 N 次重复回查，
     * 但不要把该 map 外传 —— 它反映的是查询开始时的快照。
     */
    private boolean matchesInstanceConditions(IWorkflowEngine engine,
                                              Map<String, ProcessInstance> instanceCache,
                                              TaskInstance task) {
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
}
