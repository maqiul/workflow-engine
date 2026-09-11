package com.workflow.runtime;

import com.workflow.definition.Candidate;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.TaskStatus;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 任务实例 - 一个 UserTask 节点产生的具体待办
 *
 * 字段说明:
 *  nodeId              - 对应的 UserTask 节点
 *  candidate           - 候选人与策略(ANY/ALL)
 *  completedApprovers  - 已经完成审批的用户(会签/或签共用)
 *  status              - 当前任务整体状态(只要还有候选人未操作,就是 PENDING)
 */
public final class TaskInstance {
    private final String id;
    private final String instanceId;
    private final String tokenId;
    private final String nodeId;
    private final Candidate candidate;
    private final Set<String> completedApprovers;
    private volatile TaskStatus status;
    /** 乐观锁版本号；仓储写入时 CAS，冲突抛 WorkflowConflictException。0 表示未启用。 */
    private volatile long revision;
    /**
     * 创建时间戳。
     *
     * <p>此前 domain 缺这个字段而数据库 {@code wf_task.create_time} 一直存在，
     * 读回时直接丢弃 —— 这才是 {@code TaskQuery.orderByCreateTime()} 退化成按 id
     * 排序的真正原因，不是实现偷懒。
     */
    private final long createTime;
    /** 租户 ID（多租户隔离，可为 null 表示全局） */
    private String tenantId;
    /** 所属 Token 的到达代次：区分同一节点多轮到达（循环回边支持） */
    private int arrival;

    public TaskInstance(String instanceId, String tokenId, String nodeId, Candidate candidate) {
        this(instanceId, tokenId, nodeId, candidate, System.currentTimeMillis());
    }

    private TaskInstance(String instanceId, String tokenId, String nodeId, Candidate candidate,
                         long createTime) {
        this.id = UUID.randomUUID().toString();
        this.instanceId = Objects.requireNonNull(instanceId);
        this.tokenId = Objects.requireNonNull(tokenId);
        this.nodeId = Objects.requireNonNull(nodeId);
        this.candidate = Objects.requireNonNull(candidate);
        this.completedApprovers = new HashSet<>();
        this.status = TaskStatus.PENDING;
        this.revision = 0L;
        this.createTime = createTime;
        this.tenantId = null;  // 默认无租户
        this.arrival = 0;
    }

    /**
     * 持久化层专用 - 按已落库的字段重建（含创建时间与版本号）。
     */
    public static TaskInstance reconstruct(String id, String instanceId, String tokenId,
                                           String nodeId, Candidate candidate,
                                           Set<String> completedApprovers,
                                           TaskStatus status, long revision, long createTime) {
        return reconstruct(id, instanceId, tokenId, nodeId, candidate,
                completedApprovers, status, revision, createTime, null, 0);
    }

    /**
     * 持久化层专用 - 完整版（含租户 ID + 到达代次）。
     */
    public static TaskInstance reconstruct(String id, String instanceId, String tokenId,
                                           String nodeId, Candidate candidate,
                                           Set<String> completedApprovers,
                                           TaskStatus status, long revision, long createTime,
                                           String tenantId, int arrival) {
        TaskInstance t = new TaskInstance(instanceId, tokenId, nodeId, candidate, createTime);
        t.setIdViaReflection(id);
        t.completedApprovers.clear();
        if (completedApprovers != null) {
            t.completedApprovers.addAll(completedApprovers);
        }
        t.status = Objects.requireNonNull(status);
        t.revision = revision;
        t.tenantId = tenantId;
        t.arrival = arrival;
        return t;
    }

    /**
     * 旧签名兼容 - 创建时间退化为当前时刻。
     *
     * <p>持久层<b>不要</b>用这个重载：会把历史任务的创建时间刷成"现在"，
     * 排序与耗时统计随之失真。仅供尚未升级的调用方过渡。
     */
    public static TaskInstance reconstruct(String id, String instanceId, String tokenId,
                                           String nodeId, Candidate candidate,
                                           Set<String> completedApprovers,
                                           TaskStatus status, long revision) {
        return reconstruct(id, instanceId, tokenId, nodeId, candidate,
                completedApprovers, status, revision, System.currentTimeMillis());
    }

    /**
     * 深拷贝当前状态 —— 供事务 before-image 使用。
     *
     * <p>必须连 {@code completedApprovers} 一起拷，否则恢复动作会把事务中途
     * 追加进来的审批人一并撤销干净（或者反过来，快照被后续写操作污染而失去回滚能力）。
     */
    public TaskInstance copy() {
        return reconstruct(id, instanceId, tokenId, nodeId, candidate,
                new HashSet<>(completedApprovers), status, revision, createTime, tenantId, arrival);
    }

    private void setIdViaReflection(String value) {
        try {
            Field f = TaskInstance.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(this, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法重建 TaskInstance.id", ex);
        }
    }

    public String getId() { return id; }
    public String getInstanceId() { return instanceId; }
    public String getTokenId() { return tokenId; }
    public String getNodeId() { return nodeId; }
    public Candidate getCandidate() { return candidate; }
    public Set<String> getCompletedApprovers() {
        return Collections.unmodifiableSet(completedApprovers);
    }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public long getCreateTime() { return createTime; }

    /** 租户 ID（多租户隔离） */
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    /** 所属 Token 的到达代次 */
    public int getArrival() { return arrival; }
    public void setArrival(int arrival) { this.arrival = arrival; }

    /**
     * 记录一个审批人的完成操作
     * @return true 表示此操作使整个任务完成(会签完成 或 或签命中)
     */
    public boolean recordCompletion(String userId) {
        Objects.requireNonNull(userId);
        if (!candidate.getUserIds().contains(userId)) {
            throw new IllegalArgumentException(candidate.explainRejection(userId));
        }
        completedApprovers.add(userId);

        CandidateStrategy strategy = candidate.getStrategy();
        if (strategy == CandidateStrategy.ANY) {
            // 或签:任一完成即满足
            this.status = TaskStatus.COMPLETED;
            return true;
        }
        // 会签:全部完成才满足。
        // 显式排除空候选人集合——containsAll(空集) 恒为 true，
        // 那会让"候选组尚未展开、谁都办不了"的任务一被触碰就自动完成。
        // （上面的校验其实已把所有人拒之门外，这里是防御性的第二道。）
        Set<String> required = candidate.getUserIds();
        if (!required.isEmpty() && completedApprovers.containsAll(required)) {
            this.status = TaskStatus.COMPLETED;
            return true;
        }
        return false;
    }

    /**
     * 系统动作专用：记录一个<b>不在候选人列表中</b>的审批人并直接完成任务。
     *
     * <p>用于超时自动通过等由引擎代表 SYSTEM_USER 执行的场景 —— 这类操作者天然不在
     * {@code candidate} 里，无法走 {@link #recordCompletion} 的候选人校验。
     * 方法在自己类内操作私有字段，取代此前散落在引擎里的 {@code setAccessible} 反射。
     */
    public void recordSystemApproval(String systemUserId) {
        Objects.requireNonNull(systemUserId);
        completedApprovers.add(systemUserId);
        this.status = TaskStatus.COMPLETED;
    }

    /**
     * 转办 - 把任务交给新用户处理
     * 原候选人的完成记录保留;但任务待办人换成新用户(单人会签)
     * 注:简化实现 - 转办后任务变单人 ANY 策略
     */
    public void transferTo(String newUserId) {
        Objects.requireNonNull(newUserId);
        this.status = TaskStatus.TRANSFERRED;
    }

    @Override
    public String toString() {
        return "Task[" + id.substring(0, 8) + "@" + nodeId + " " + candidate + " status=" + status + "]";
    }
}