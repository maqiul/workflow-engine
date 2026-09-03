package com.workflow.runtime;

import com.workflow.enums.AuditEventType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 审计日志 - 记录引擎关键状态变更
 *
 * 不可变对象，创建后字段不可修改。
 */
public final class AuditLog {
    private final String id;
    private final String instanceId;
    private final String taskId;       // 可为 null（如流程发起/终止）
    private final AuditEventType eventType;
    private final String operator;     // 操作人（用户 ID 或 __system__）
    private final Instant timestamp;
    private final String detail;       // 附加信息（JSON 或纯文本）

    public AuditLog(String instanceId, String taskId, AuditEventType eventType,
                    String operator, String detail) {
        this.id = UUID.randomUUID().toString();
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId 不能为空");
        this.taskId = taskId;  // 可为 null
        this.eventType = Objects.requireNonNull(eventType, "eventType 不能为空");
        this.operator = Objects.requireNonNull(operator, "operator 不能为空");
        this.timestamp = Instant.now();
        this.detail = detail;
    }

    public String getId() { return id; }
    public String getInstanceId() { return instanceId; }
    public String getTaskId() { return taskId; }
    public AuditEventType getEventType() { return eventType; }
    public String getOperator() { return operator; }
    public Instant getTimestamp() { return timestamp; }
    public String getDetail() { return detail; }

    @Override
    public String toString() {
        return String.format("AuditLog[%s instance=%s task=%s event=%s op=%s time=%s]",
                id.substring(0, 8), instanceId.substring(0, 8),
                taskId != null ? taskId.substring(0, 8) : "null",
                eventType, operator, timestamp);
    }
}
