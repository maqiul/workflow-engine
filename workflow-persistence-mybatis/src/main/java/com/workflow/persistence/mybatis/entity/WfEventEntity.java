package com.workflow.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 事件实体（MyBatis-Plus 版）
 *
 * 表结构:
 *   wf_event
 *     instance_id   VARCHAR(64)  \
 *     node_id       VARCHAR(64)  / PRIMARY KEY (复合)
 *     event_type    VARCHAR(16)  -- MESSAGE / SIGNAL / TIMER
 *     message_key   VARCHAR(256) -- MESSAGE: messageName:correlationKey
 *     signal_name   VARCHAR(128) -- SIGNAL: signalName
 *     trigger_time  BIGINT       -- TIMER: 触发时间（毫秒时间戳）
 *     interrupting  BOOLEAN      -- TIMER: 是否中断模式
 *
 * 注：复合主键不能使用 BaseMapper，所有操作手写 SQL。
 */
@TableName("wf_event")
public class WfEventEntity {

    @TableField("instance_id")
    private String instanceId;

    @TableField("node_id")
    private String nodeId;

    @TableField("event_type")
    private String eventType;

    @TableField("message_key")
    private String messageKey;

    @TableField("signal_name")
    private String signalName;

    @TableField("trigger_time")
    private Long triggerTime;

    @TableField("interrupting")
    private Boolean interrupting;

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getMessageKey() { return messageKey; }
    public void setMessageKey(String messageKey) { this.messageKey = messageKey; }
    public String getSignalName() { return signalName; }
    public void setSignalName(String signalName) { this.signalName = signalName; }
    public Long getTriggerTime() { return triggerTime; }
    public void setTriggerTime(Long triggerTime) { this.triggerTime = triggerTime; }
    public Boolean getInterrupting() { return interrupting; }
    public void setInterrupting(Boolean interrupting) { this.interrupting = interrupting; }
}
