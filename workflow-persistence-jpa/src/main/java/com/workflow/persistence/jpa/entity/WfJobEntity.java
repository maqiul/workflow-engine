package com.workflow.persistence.jpa.entity;

import com.workflow.enums.JobState;
import com.workflow.enums.JobType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 异步作业实体
 *
 * 表结构:
 *   wf_job
 *     id              VARCHAR(64)  PRIMARY KEY
 *     type            VARCHAR(32)  -- JobType
 *     instance_id     VARCHAR(64)  -- 关联流程实例（排他执行 + 结束清理）
 *     payload         CLOB         -- 作业参数（JSON）
 *     due_at          BIGINT       -- 到期时刻
 *     state           VARCHAR(16)  -- JobState
 *     retries         INT          -- 已重试次数
 *     max_retries     INT          -- 最大重试次数
 *     last_error      VARCHAR(1024)
 *     lock_owner      VARCHAR(64)  -- 领取者
 *     lock_expires_at BIGINT       -- 租约到期时刻
 *     create_time     BIGINT
 *     update_time     BIGINT
 */
@Entity
@Table(name = "wf_job")
public class WfJobEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 32, nullable = false)
    private JobType type;

    @Column(name = "instance_id", length = 64)
    private String instanceId;

    @Column(name = "payload", columnDefinition = "CLOB")
    private String payload;

    @Column(name = "due_at", nullable = false)
    private long dueAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 16, nullable = false)
    private JobState state;

    @Column(name = "retries", nullable = false)
    private int retries;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Column(name = "lock_owner", length = 64)
    private String lockOwner;

    @Column(name = "lock_expires_at", nullable = false)
    private long lockExpiresAt;

    @Column(name = "create_time", nullable = false)
    private long createTime;

    @Column(name = "update_time", nullable = false)
    private long updateTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public JobType getType() { return type; }
    public void setType(JobType type) { this.type = type; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public long getDueAt() { return dueAt; }
    public void setDueAt(long dueAt) { this.dueAt = dueAt; }
    public JobState getState() { return state; }
    public void setState(JobState state) { this.state = state; }
    public int getRetries() { return retries; }
    public void setRetries(int retries) { this.retries = retries; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getLockOwner() { return lockOwner; }
    public void setLockOwner(String lockOwner) { this.lockOwner = lockOwner; }
    public long getLockExpiresAt() { return lockExpiresAt; }
    public void setLockExpiresAt(long lockExpiresAt) { this.lockExpiresAt = lockExpiresAt; }
    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
    public long getUpdateTime() { return updateTime; }
    public void setUpdateTime(long updateTime) { this.updateTime = updateTime; }
}
