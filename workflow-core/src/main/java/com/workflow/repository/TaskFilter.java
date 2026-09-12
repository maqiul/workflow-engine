package com.workflow.repository;

import com.workflow.enums.TaskStatus;
import com.workflow.runtime.TaskInstance;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 任务查询的「可下推条件」—— 让仓储把过滤、排序、分页做在数据库侧。
 *
 * <p><b>为什么需要它</b>：此前 {@code TaskQuery} 与 {@code findPendingByUser} 都是
 * 「把整张 {@code wf_task} 拉进内存，再用 stream 过滤」。任务表一到真实量级，
 * 「查我的待办」这条最高频的查询就要把全库任务读一遍。
 *
 * <p><b>两类条件，两种命运</b>：
 * <ul>
 *   <li><b>可下推</b>：实例 id / 状态 / 节点 id / 候选人 —— {@code wf_task} 表里有这些列，
 *       SQL 能直接过滤；</li>
 *   <li><b>不可下推</b>：流程 key、定义版本、流程变量 —— 它们长在 {@code wf_instance}
 *       上，任务表没有冗余这些列，只能回到内存里靠实例补齐。</li>
 * </ul>
 *
 * <p><b>候选人为什么是「粗筛」</b>：候选人存在一个 JSON 列里，没有结构化索引，
 * 所以 SQL 侧只能按 {@code candidate_json LIKE '%"u1"%'} 近似匹配 —— 叫它粗筛，
 * 是因为它可能中假阳性（用户名与候选组名恰好同名的极端情形）。
 * 假阳性一律由 {@link #matches(TaskInstance)} 在内存里兜掉，
 * <b>所以粗筛只影响性能，不影响正确性</b>。
 *
 * <p>JSON 字符串值自带引号，这是粗筛能用的关键：{@code u1} 不会匹配到 {@code u10}。
 *
 * <p><b>统一的实现契约</b>：{@link TaskRepository#findPaged(TaskFilter)} 返回的是
 * 已经「过滤 + 排序 + 分页」完成的结果。内存实现直接全量套 {@link #matches}；
 * 数据库实现先让 SQL 粗筛，再套同一份 {@link #matches} 精筛，最后
 * {@link #finish(List)} 收尾。三套实现因此共享同一个语义裁判，不会各写一份过滤逻辑。
 */
public final class TaskFilter {

    private String instanceId;
    private TaskStatus status;
    private String nodeId;
    private String candidateUserId;
    private String candidateGroupId;
    private boolean orderByCreateTime;
    private boolean descending;
    private Integer limit;
    private Integer offset;

    private TaskFilter() {
    }

    public static TaskFilter create() {
        return new TaskFilter();
    }

    // ========== 可下推条件 ==========

    public TaskFilter instanceId(String value) {
        this.instanceId = value;
        return this;
    }

    public TaskFilter status(TaskStatus value) {
        this.status = value;
        return this;
    }

    public TaskFilter nodeId(String value) {
        this.nodeId = value;
        return this;
    }

    /** 候选人（用户维度）—— SQL 侧走 LIKE 粗筛。 */
    public TaskFilter candidateUser(String userId) {
        this.candidateUserId = userId;
        return this;
    }

    /** 候选组 —— 同样走 LIKE 粗筛。 */
    public TaskFilter candidateGroup(String groupId) {
        this.candidateGroupId = groupId;
        return this;
    }

    // ========== 排序与分页 ==========

    /**
     * 按创建时间排序。
     *
     * <p>刻意拆成两个具名方法而不是 {@code orderByCreateTime(boolean desc)}：
     * 后者在调用处只看得到 {@code orderByCreateTime(true)}，读不出 true 是什么意思，
     * 而这里恰恰容易把升序写成降序。<b>命名与查询构建器的公开 API 保持一致。</b>
     */
    public TaskFilter orderByCreateTimeAsc() {
        this.orderByCreateTime = true;
        this.descending = false;
        return this;
    }

    /** 按创建时间降序。 */
    public TaskFilter orderByCreateTimeDesc() {
        this.orderByCreateTime = true;
        this.descending = true;
        return this;
    }

    public TaskFilter limit(Integer value) {
        this.limit = value;
        return this;
    }

    public TaskFilter offset(Integer value) {
        this.offset = value;
        return this;
    }

    // ========== 读取 ==========

    public String getInstanceId() {
        return instanceId;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getCandidateUserId() {
        return candidateUserId;
    }

    public String getCandidateGroupId() {
        return candidateGroupId;
    }

    public boolean isOrderByCreateTime() {
        return orderByCreateTime;
    }

    public boolean isDescending() {
        return descending;
    }

    public Integer getLimit() {
        return limit;
    }

    public Integer getOffset() {
        return offset;
    }

    /** 是否有任何一个可下推条件（全无时查询等价于「取全部」）。 */
    public boolean hasAnyCondition() {
        return instanceId != null || status != null || nodeId != null
                || candidateUserId != null || candidateGroupId != null;
    }

    /** 是否存在候选人条件（即 SQL 侧只能用粗筛的那类条件）。 */
    public boolean hasCandidateCondition() {
        return candidateUserId != null || candidateGroupId != null;
    }

    /**
     * {@code limit}/{@code offset} 能否安全地下推到 SQL。
     *
     * <p>只有当 SQL 过滤是<b>精确</b>的时候才可以 —— 也就是没有候选人条件。
     * 一旦走了 LIKE 粗筛，结果里可能混着假阳性，若在 SQL 层就把行数截断到 limit，
     * 精筛掉假阳性之后真匹配就少了，调用方会看到「明明有数据却不足一页」。
     * 所以粗筛场景只在精筛之后分页。
     */
    public boolean canPushDownLimit() {
        return !hasCandidateCondition();
    }

    // ========== 内存侧：三套仓储共用的语义裁判 ==========

    /** 单条任务的过滤判定 —— 精筛，也是内存实现的全部逻辑。 */
    public boolean matches(TaskInstance task) {
        if (status != null && task.getStatus() != status) {
            return false;
        }
        if (nodeId != null && !nodeId.equals(task.getNodeId())) {
            return false;
        }
        if (instanceId != null && !instanceId.equals(task.getInstanceId())) {
            return false;
        }
        if (candidateUserId != null
                && !task.getCandidate().getUserIds().contains(candidateUserId)) {
            return false;
        }
        if (candidateGroupId != null
                && !task.getCandidate().getGroupIds().contains(candidateGroupId)) {
            return false;
        }
        return true;
    }

    /**
     * 排序 + 分页，供内存路径与「粗筛后仍需精筛」的路径收尾。
     *
     * <p>排序用 {@code createTime} 再以 {@code id} 兜底：同一毫秒内创建的多条任务
     * （并行网关分叉出来的那些就是）否则顺序不稳定，翻页时会重复或漏掉。
     */
    public List<TaskInstance> finish(List<TaskInstance> tasks) {
        Stream<TaskInstance> stream = tasks.stream();
        if (orderByCreateTime) {
            Comparator<TaskInstance> cmp =
                    Comparator.comparingLong(TaskInstance::getCreateTime)
                            .thenComparing(TaskInstance::getId);
            stream = stream.sorted(descending ? cmp.reversed() : cmp);
        }
        if (offset != null && offset > 0) {
            stream = stream.skip(offset);
        }
        if (limit != null) {
            stream = stream.limit(limit);
        }
        return stream.toList();
    }

    /**
     * 候选人 LIKE 粗筛模式。
     *
     * <p>两侧的引号是刻意的：JSON 里用户 id 一定以字符串形式出现，
     * 带上引号才能让 {@code u1} 不误配 {@code u10}。id 里若混进引号会破坏这个前提，
     * 故先剥掉。
     */
    public static String candidateLikePattern(String userId) {
        return "%\"" + userId.replace("\"", "") + "\"%";
    }
}
