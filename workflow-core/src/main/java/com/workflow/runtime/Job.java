package com.workflow.runtime;

import com.workflow.enums.JobState;
import com.workflow.enums.JobType;

import java.util.Objects;

/**
 * 异步作业 —— 持久化的待执行单元。
 *
 * <p>与内存调度器的根本区别：作业<b>落库</b>，因此
 * <ul>
 *   <li>进程重启后作业仍在（不必像内存调度器那样"扫描任务再重算到期时刻"）；</li>
 *   <li>执行失败可以<b>重试</b>并最终进死信，而不是打完日志就丢；</li>
 *   <li>多节点部署时靠<b>原子领取 + 租约</b>保证同一作业只被一个节点执行。</li>
 * </ul>
 *
 * <p>领取语义：{@code lockOwner} 记录领取者，{@code lockExpiresAt} 是租约到期时刻。
 * 租约过期后任何节点都可重新领取 —— 节点崩溃不会让作业永久卡住。
 */
public class Job {

    private final String id;
    private final JobType type;
    private final String instanceId;
    private final String payload;
    private long dueAt;
    private final long createTime;

    private JobState state = JobState.READY;
    private int retries;
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private String lastError;
    private String lockOwner;
    private long lockExpiresAt;
    private long updateTime;

    /** 默认最大重试次数（不含首次执行） */
    public static final int DEFAULT_MAX_RETRIES = 3;

    public Job(String id, JobType type, String instanceId, String payload, long dueAt, long createTime) {
        this.id = Objects.requireNonNull(id, "作业 id 不能为空");
        this.type = Objects.requireNonNull(type, "作业类型不能为空");
        this.instanceId = instanceId;
        this.payload = payload;
        this.dueAt = dueAt;
        this.createTime = createTime;
        this.updateTime = createTime;
    }

    public String getId() { return id; }
    public JobType getType() { return type; }
    public String getInstanceId() { return instanceId; }
    public String getPayload() { return payload; }
    public long getDueAt() { return dueAt; }
    public long getCreateTime() { return createTime; }
    public JobState getState() { return state; }
    public int getRetries() { return retries; }
    public int getMaxRetries() { return maxRetries; }
    public String getLastError() { return lastError; }
    public String getLockOwner() { return lockOwner; }
    public long getLockExpiresAt() { return lockExpiresAt; }
    public long getUpdateTime() { return updateTime; }

    public void setState(JobState state) { this.state = state; }
    public void setRetries(int retries) { this.retries = retries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public void setLockOwner(String lockOwner) { this.lockOwner = lockOwner; }
    public void setLockExpiresAt(long lockExpiresAt) { this.lockExpiresAt = lockExpiresAt; }
    public void setUpdateTime(long updateTime) { this.updateTime = updateTime; }

    /** 重排期（退避重试时用）：把到期时刻推到下一次可执行时刻。 */
    public void reschedule(long newDueAt) { this.dueAt = newDueAt; }

    /** 是否还允许重试 */
    public boolean canRetry() {
        return retries < maxRetries;
    }

    /** 该作业当前是否可被领取：已到期、状态待执行、且未被他人有效持有 */
    public boolean isClaimable(long now) {
        return state == JobState.READY
                && dueAt <= now
                && (lockOwner == null || lockExpiresAt <= now);
    }

    @Override
    public String toString() {
        return "Job{id=" + id + ", type=" + type + ", instance=" + instanceId
                + ", dueAt=" + dueAt + ", state=" + state + ", retries=" + retries + "/" + maxRetries + "}";
    }
}
