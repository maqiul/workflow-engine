package com.workflow.runtime;

import com.workflow.enums.NodeType;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 历史活动实例 —— 流程图中某个节点的一次执行。
 *
 * <p>与审计日志的分工：{@code AuditLog} 是<b>事件流水</b>（谁在何时干了什么，append-only），
 * 本类是<b>活动区间</b>（一个节点从进入至离开的时间跨度）。
 * 效能报表要的"某节点平均耗时""瓶颈在谁手上"只能由区间算出来，
 * 拿事件流水去差分既脆弱又昂贵。
 *
 * <p><b>两段式生命周期</b>：{@code endTime == null} 表示该活动仍在进行。
 * 网关 / START / END 属瞬时活动，{@code open} 与 {@code close} 发生在同一次推进内，
 * duration 接近 0，价值在于还原实际走过的路径；
 * 而 UserTask 的 duration 必须覆盖<b>人类等待时长</b>，因此它的开与闭分属两次推进
 * （创建待办时开、审批完成并推进时闭），由 {@code findOpen} 把两半接上。
 */
public final class HistoricActivityInstance {

    private final String id;
    private final String instanceId;
    /** 冗余存流程标识：宽表免 join，按 key+节点聚合是直接需求。 */
    private final String processKey;
    private final int processVersion;
    private final String activityId;
    private final NodeType activityType;
    private final String tokenId;
    /** UserTask 活动对应的任务 id；瞬时活动为 null，创建任务后由 {@link #attachTask} 补上。 */
    private volatile String taskId;
    private final long startTime;
    /**
     * 同实例内的因果序号。
     *
     * <p>{@code startTime} 只到毫秒，同一毫秒内推进的多个节点（串行流程的
     * START→USER_TASK、并行 fork 的多支）无法靠时间分先后；用 id 兜底更不行 ——
     * UUID 是随机的，实测会把 START 排到 USER_TASK 之后，报表还原出的路径就是错的。
     *
     * <p>取进程内单调递增；重启归零无碍，因为新旧数据 startTime 相差极远，
     * 主排序键已能区分。<b>多实例部署须改用数据库序列</b>（见 V3 DDL 注释）。
     */
    private final long seq;
    private volatile Long endTime;
    /** 完成该活动的操作者（UserTask 用审批人；瞬时活动记触发者）。 */
    private volatile String performer;

    private static final AtomicLong SEQ_GEN =
            new AtomicLong();

    public HistoricActivityInstance(String instanceId, String processKey, int processVersion,
                                    String activityId, NodeType activityType,
                                    String tokenId, String taskId, long startTime) {
        this(instanceId, processKey, processVersion, activityId, activityType,
                tokenId, taskId, startTime, SEQ_GEN.incrementAndGet());
    }

    private HistoricActivityInstance(String instanceId, String processKey, int processVersion,
                                     String activityId, NodeType activityType,
                                     String tokenId, String taskId, long startTime, long seq) {
        this.id = UUID.randomUUID().toString();
        this.instanceId = Objects.requireNonNull(instanceId);
        this.processKey = Objects.requireNonNull(processKey);
        this.processVersion = processVersion;
        this.activityId = Objects.requireNonNull(activityId);
        this.activityType = Objects.requireNonNull(activityType);
        this.tokenId = Objects.requireNonNull(tokenId);
        this.taskId = taskId;
        this.startTime = startTime;
        this.seq = seq;
    }

    /** 持久层重建专用（保持同一 id 与已闭合的时间）。 */
    public static HistoricActivityInstance reconstruct(String id, String instanceId, String processKey,
                                                       int processVersion, String activityId,
                                                       NodeType activityType, String tokenId,
                                                       String taskId, long startTime,
                                                       Long endTime, String performer, long seq) {
        HistoricActivityInstance a = new HistoricActivityInstance(
                instanceId, processKey, processVersion, activityId, activityType,
                tokenId, taskId, startTime, seq);
        a.setIdViaReflection(id);
        a.endTime = endTime;
        a.performer = performer;
        return a;
    }

    private void setIdViaReflection(String value) {
        try {
            Field f = HistoricActivityInstance.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(this, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法重建 HistoricActivityInstance.id", ex);
        }
    }

    /**
     * 挂上任务 id。
     *
     * <p>UserTask 的活动在<b>进入节点</b>时开启，而任务是在节点处理过程中才创建的，
     * 所以只能事后补挂，不能放进构造器。
     */
    public void attachTask(String taskId) {
        this.taskId = taskId;
    }

    /**
     * 闭合该活动。
     *
     * <p>重复闭合直接抛错而不是静默覆盖：一条活动被闭两次意味着埋点逻辑错了，
     * 会让 duration 统计凭空多出一段，属于必须暴露的缺陷。
     */
    public void close(long endTime, String performer) {
        if (this.endTime != null) {
            throw new IllegalStateException("活动已闭合，不能重复关闭: activity="
                    + activityId + " id=" + id);
        }
        if (endTime < startTime) {
            throw new IllegalStateException("活动结束时间早于开始时间: activity=" + activityId
                    + " start=" + startTime + " end=" + endTime);
        }
        this.endTime = endTime;
        if (performer != null) {
            this.performer = performer;
        }
    }

    /** 仍在进行返回 null —— 报表须显式决定如何对待未完成的活动。 */
    public Long getDuration() {
        return endTime == null ? null : endTime - startTime;
    }

    public boolean isOpen() { return endTime == null; }

    public String getId() { return id; }
    public String getInstanceId() { return instanceId; }
    public String getProcessKey() { return processKey; }
    public int getProcessVersion() { return processVersion; }
    public String getActivityId() { return activityId; }
    public NodeType getActivityType() { return activityType; }
    public String getTokenId() { return tokenId; }
    public String getTaskId() { return taskId; }
    public long getStartTime() { return startTime; }
    public long getSeq() { return seq; }
    public Long getEndTime() { return endTime; }
    public String getPerformer() { return performer; }

    /** 深拷贝 —— 仓储按拷贝语义交换对象，与其余仓储保持一致契约。 */
    public HistoricActivityInstance copy() {
        return reconstruct(id, instanceId, processKey, processVersion, activityId,
                activityType, tokenId, taskId, startTime, endTime, performer, seq);
    }

    @Override
    public String toString() {
        return "HistAct[" + activityType + ":" + activityId
                + " inst=" + instanceId.substring(0, Math.min(8, instanceId.length()))
                + " dur=" + getDuration() + (isOpen() ? "/OPEN" : "") + "]";
    }
}
