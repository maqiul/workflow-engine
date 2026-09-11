package com.workflow.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workflow.enums.TaskStatus;

/**
 * 任务实体(MyBatis-Plus 版)
 *
 * 表结构:
 *   wf_task
 *     id                       VARCHAR(64)  PRIMARY KEY
 *     instance_id              VARCHAR(64)
 *     token_id                 VARCHAR(64)
 *     node_id                  VARCHAR(64)
 *     candidate_json           CLOB         -- Candidate 序列化
 *     completed_approvers_json CLOB         -- Set<String> 序列化
 *     status                   VARCHAR(16)  -- TaskStatus
 *     create_time              BIGINT
 */
@TableName("wf_task")
public class WfTaskEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("instance_id")
    private String instanceId;

    @TableField("token_id")
    private String tokenId;

    @TableField("node_id")
    private String nodeId;

    @TableField("candidate_json")
    private String candidateJson;

    @TableField("completed_approvers_json")
    private String completedApproversJson;

    @TableField("status")
    private TaskStatus status;

    @TableField("create_time")
    private long createTime;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("arrival")
    private int arrival;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getTokenId() { return tokenId; }
    public void setTokenId(String tokenId) { this.tokenId = tokenId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getCandidateJson() { return candidateJson; }
    public void setCandidateJson(String candidateJson) { this.candidateJson = candidateJson; }
    public String getCompletedApproversJson() { return completedApproversJson; }
    public void setCompletedApproversJson(String completedApproversJson) {
        this.completedApproversJson = completedApproversJson;
    }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public int getArrival() { return arrival; }
    public void setArrival(int arrival) { this.arrival = arrival; }
}
