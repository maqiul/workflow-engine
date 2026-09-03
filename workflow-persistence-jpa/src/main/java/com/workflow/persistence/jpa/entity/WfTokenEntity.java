package com.workflow.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Token 实体
 *
 * 表结构:
 *   wf_token
 *     id              VARCHAR(64)  PRIMARY KEY
 *     instance_id     VARCHAR(64)  -- 冗余字段,便于单表查询
 *     current_node_id VARCHAR(64)
 *     status          VARCHAR(16)  -- TokenStatus
 */
@Entity
@Table(name = "wf_token")
public class WfTokenEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "instance_id", length = 64, nullable = false)
    private String instanceId;

    @Column(name = "current_node_id", length = 64, nullable = false)
    private String currentNodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private com.workflow.enums.TokenStatus status;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(String currentNodeId) { this.currentNodeId = currentNodeId; }
    public com.workflow.enums.TokenStatus getStatus() { return status; }
    public void setStatus(com.workflow.enums.TokenStatus status) { this.status = status; }
}