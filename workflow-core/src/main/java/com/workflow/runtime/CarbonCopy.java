package com.workflow.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * 抄送记录 - 流程节点完成后知会相关人员（不需要审批）
 *
 * 字段说明:
 *  instanceId  - 所属流程实例
 *  taskId      - 触发抄送的任务（可为 null，表示手动抄送）
 *  nodeId      - 触发抄送的节点（可为 null）
 *  recipient   - 被抄送人
 *  operator    - 抄送操作人
 *  message     - 抄送附言（可为 null）
 */
public final class CarbonCopy {
    private final String id;
    private final String instanceId;
    private final String taskId;
    private final String nodeId;
    private final String recipient;
    private final String operator;
    private final String message;
    private final long createTime;
    private boolean read;

    public CarbonCopy(String instanceId, String taskId, String nodeId,
                      String recipient, String operator, String message) {
        this.id = UUID.randomUUID().toString();
        this.instanceId = Objects.requireNonNull(instanceId);
        this.taskId = taskId;
        this.nodeId = nodeId;
        this.recipient = Objects.requireNonNull(recipient);
        this.operator = Objects.requireNonNull(operator);
        this.message = message;
        this.createTime = System.currentTimeMillis();
        this.read = false;
    }

    public String getId() { return id; }
    public String getInstanceId() { return instanceId; }
    public String getTaskId() { return taskId; }
    public String getNodeId() { return nodeId; }
    public String getRecipient() { return recipient; }
    public String getOperator() { return operator; }
    public String getMessage() { return message; }
    public long getCreateTime() { return createTime; }
    public boolean isRead() { return read; }
    public void markRead() { this.read = true; }

    @Override
    public String toString() {
        return "CarbonCopy[" + id.substring(0, 8) + " to=" + recipient +
                " from=" + operator + " node=" + nodeId +
                (read ? " READ" : " UNREAD") + "]";
    }
}
