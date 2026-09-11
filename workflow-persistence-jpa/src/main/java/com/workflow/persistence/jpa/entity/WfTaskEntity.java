package com.workflow.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 任务实体
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
@Entity
@Table(name = "wf_task")
public class WfTaskEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "instance_id", length = 64, nullable = false)
    private String instanceId;

    @Column(name = "token_id", length = 64, nullable = false)
    private String tokenId;

    @Column(name = "node_id", length = 64, nullable = false)
    private String nodeId;

    @Lob
    @Column(name = "candidate_json", nullable = false)
    private String candidateJson;

    @Lob
    @Column(name = "completed_approvers_json", nullable = false)
    private String completedApproversJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private com.workflow.enums.TaskStatus status;

    @Column(name = "create_time", nullable = false)
    private long createTime;

    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "arrival")
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
    public com.workflow.enums.TaskStatus getStatus() { return status; }
    public void setStatus(com.workflow.enums.TaskStatus status) { this.status = status; }
    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public int getArrival() { return arrival; }
    public void setArrival(int arrival) { this.arrival = arrival; }
}