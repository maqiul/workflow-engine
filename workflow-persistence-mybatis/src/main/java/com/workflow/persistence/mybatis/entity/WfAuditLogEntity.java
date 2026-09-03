package com.workflow.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 审计日志实体
 */
@TableName("wf_audit_log")
public class WfAuditLogEntity {

    @TableId("id")
    private String id;

    @TableField("instance_id")
    private String instanceId;

    @TableField("task_id")
    private String taskId;

    @TableField("event_type")
    private com.workflow.enums.AuditEventType eventType;

    @TableField("operator")
    private String operator;

    @TableField("timestamp")
    private long timestamp;

    @TableField("detail")
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
