package com.workflow.runtime;

import com.workflow.enums.TaskStatus;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 历史任务实例 —— 一张待办从产生到落定的完整记录。
 *
 * <p>与 {@link HistoricActivityInstance} 的分工（Flowable 也是两张表，理由相同）：
 * <ul>
 *   <li><b>活动</b>关注<b>流程走到哪</b>：覆盖所有节点类型，用于路径还原与环节耗时；</li>
 *   <li><b>任务</b>关注<b>人在上面干了什么</b>：候选人是谁、实际谁批的、
 *       以什么方式结束（通过 / 驳回 / 转办 / 终止 / 撤回）—— 绩效与责任追溯只能靠这张表。</li>
 * </ul>
 *
 * <p><b>主键直接取 taskId</b>：一个任务只该有一条历史，用 taskId 作 id 让"重复记录"
 * 在数据层就不可能成立，无需额外的幂等表或去重逻辑。
 *
 * <p><b>只记录已落定的任务</b>：进行中的待办属于运行时数据，{@code wf_task} 与
 * {@code TaskQuery} 已经能查，不必在历史里再留一份"半条"记录 ——
 * 也因此不存在活动表那种 open/close 两段式与未闭合统计问题。
 *
 * <p>{@code candidateUsers} 是任务产生时的候选人<b>快照</b>：转办会改写当前待办的候选人，
 * 事后从 {@code wf_task} 反查只能看到最后那一位，追责链就断了。
 */
public final class HistoricTaskInstance {

    /**
     * 同毫秒内的次级排序键。
     *
     * <p>与 {@code HistoricActivityInstance.seq} 同一个道理：时间戳只到毫秒，
     * 连续快速完成的两个任务会落在同一毫秒，此时用 taskId（随机 UUID）定序
     * 等于把审批链顺序交给运气 —— 实测会让 manager 排到 apply 前面。
     */
    private static final AtomicLong SEQ_GEN =
            new AtomicLong();

    private final String taskId;
    private final String instanceId;
    private final String processKey;
    private final int processVersion;
    private final String nodeId;
    private final List<String> candidateUsers;
    private final List<String> completedBy;
    private final long startTime;
    private final long endTime;
    private final TaskStatus endReason;
    private final long seq;

    public HistoricTaskInstance(String taskId, String instanceId, String processKey,
                                int processVersion, String nodeId,
                                Collection<String> candidateUsers,
                                Collection<String> completedBy,
                                long startTime, long endTime, TaskStatus endReason) {
        this(taskId, instanceId, processKey, processVersion, nodeId, candidateUsers, completedBy,
                startTime, endTime, endReason, SEQ_GEN.incrementAndGet());
    }

    private HistoricTaskInstance(String taskId, String instanceId, String processKey,
                                 int processVersion, String nodeId,
                                 Collection<String> candidateUsers,
                                 Collection<String> completedBy,
                                 long startTime, long endTime, TaskStatus endReason, long seq) {
        this.taskId = Objects.requireNonNull(taskId);
        this.instanceId = Objects.requireNonNull(instanceId);
        this.processKey = Objects.requireNonNull(processKey);
        this.processVersion = processVersion;
        this.nodeId = Objects.requireNonNull(nodeId);
        this.candidateUsers = List.copyOf(candidateUsers == null ? List.of() : candidateUsers);
        this.completedBy = List.copyOf(completedBy == null ? List.of() : completedBy);
        this.startTime = startTime;
        this.endTime = endTime;
        this.endReason = Objects.requireNonNull(endReason);
        this.seq = seq;
        if (endTime < startTime) {
            throw new IllegalStateException("任务结束时间早于开始时间: task=" + taskId
                    + " start=" + startTime + " end=" + endTime);
        }
        if (endReason == TaskStatus.PENDING) {
            throw new IllegalStateException("进行中的任务不该写进历史: task=" + taskId);
        }
    }

    /** 仓储专用：从已落库的行重建。 */
    public static HistoricTaskInstance reconstruct(String taskId, String instanceId, String processKey,
                                                   int processVersion, String nodeId,
                                                   List<String> candidateUsers,
                                                   List<String> completedBy,
                                                   long startTime, long endTime,
                                                   TaskStatus endReason, long seq) {
        return new HistoricTaskInstance(taskId, instanceId, processKey, processVersion, nodeId,
                candidateUsers, completedBy, startTime, endTime, endReason, seq);
    }

    /** 由任务当前状态构造：开始时间取任务自身的创建时刻，结束时间由调用方给。 */
    public static HistoricTaskInstance of(TaskInstance task, ProcessInstance instance, long endTime) {
        return new HistoricTaskInstance(task.getId(), task.getInstanceId(),
                instance.getProcessKey(), instance.getProcessVersion(), task.getNodeId(),
                task.getCandidate().getUserIds(), task.getCompletedApprovers(),
                task.getCreateTime(), endTime, task.getStatus());
    }

    public String getTaskId() { return taskId; }
    public String getInstanceId() { return instanceId; }
    public String getProcessKey() { return processKey; }
    public int getProcessVersion() { return processVersion; }
    public String getNodeId() { return nodeId; }
    public List<String> getCandidateUsers() { return candidateUsers; }
    public List<String> getCompletedBy() { return completedBy; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
    public TaskStatus getEndReason() { return endReason; }
    public long getSeq() { return seq; }

    /** 耗时由起止算出，不单独存列 —— 少一份冗余就少一处不一致。 */
    public long getDuration() { return endTime - startTime; }

    /**
     * 单人任务的处理人；会签 / 或签多人时返回 null。
     *
     * <p>"谁手上压了多少单"这类统计要的是 {@link #getCandidateUsers()} 与
     * {@link #getCompletedBy()}，不要拿这个字段凑。
     */
    public String getAssignee() {
        List<String> who = completedBy.isEmpty() ? candidateUsers : completedBy;
        return who.size() == 1 ? who.get(0) : null;
    }

    /** 该候选人是否曾在这张待办上（用于按人统计参与量）。 */
    public boolean involves(String userId) {
        return candidateUsers.contains(userId) || completedBy.contains(userId);
    }

    @Override
    public String toString() {
        return "HistTask[" + nodeId + " task=" + taskId.substring(0, Math.min(8, taskId.length()))
                + " by=" + completedBy + " reason=" + endReason + " dur=" + getDuration() + "]";
    }
}
