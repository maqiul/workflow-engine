package com.workflow.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 审计日志实体
 *
 * 表结构:
 *   wf_audit_log
 *     id           VARCHAR(64)  PRIMARY KEY
 *     instance_id  VARCHAR(64)
 *     task_id      VARCHAR(64)  -- 可为 null
 *     event_type   VARCHAR(32)
 *     operator     VARCHAR(64)
 *     timestamp    BIGINT
 *     detail       CLOB
 */
@Entity
@Table(name = "wf_audit_log")
public class WfAuditLogEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "instance_id", length = 64, nullable = false)
    private String instanceId;

    @Column(name = "task_id", length = 64, nullable = true)
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 32, nullable = false)
    private com.workflow.enums.AuditEventType eventType;

    @Column(name = "operator", length = 64, nullable = false)
    private String operator;

    @Column(name = "timestamp", nullable = false)
    private long timestamp;

    @Column(name = "detail", columnDefinition = "CLOB")
    private String detail;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public com.workflow.enums.AuditEventType getEventType() { return eventType; }
    public void setEventType(com.workflow.enums.AuditEventType eventType) { this.eventType = eventType; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
