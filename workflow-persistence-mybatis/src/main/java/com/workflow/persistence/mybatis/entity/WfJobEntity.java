package com.workflow.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workflow.enums.JobState;
import com.workflow.enums.JobType;

/**
 * 异步作业实体（MyBatis-Plus 版）
 */
@TableName("wf_job")
public class WfJobEntity {

    @TableId("id")
    private String id;

    @TableField("type")
    private JobType type;

    @TableField("instance_id")
    private String instanceId;

    @TableField("payload")
    private String payload;

    @TableField("due_at")
    private long dueAt;

    @TableField("state")
    private JobState state;

    @TableField("retries")
    private int retries;

    @TableField("max_retries")
    private int maxRetries;

    @TableField("last_error")
    private String lastError;

    @TableField("lock_owner")
    private String lockOwner;

    @TableField("lock_expires_at")
    private long lockExpiresAt;

    @TableField("create_time")
    private long createTime;

    @TableField("update_time")
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
