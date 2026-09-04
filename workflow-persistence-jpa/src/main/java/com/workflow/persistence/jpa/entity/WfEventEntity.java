package com.workflow.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * 事件实体 - 消息/信号/定时器事件的持久化存储
 *
 * <p>主键由 (instanceId, nodeId) 复合组成，因为一个节点只会有一个事件。
 * 消息/信号事件按 key 查找关联实例，定时器事件按过期时间查找。
 */
@Entity
@Table(name = "wf_event", indexes = {
        @Index(name = "idx_event_message_key", columnList = "messageKey"),
        @Index(name = "idx_event_signal_name", columnList = "signalName"),
        @Index(name = "idx_event_trigger_time", columnList = "triggerTime")
})
@IdClass(WfEventEntity.EventKey.class)
public class WfEventEntity {

    /** 复合主键类 */
    public static class EventKey implements Serializable {
        private String instanceId;
        private String nodeId;

        public EventKey() {}

        public EventKey(String instanceId, String nodeId) {
            this.instanceId = instanceId;
            this.nodeId = nodeId;
        }

        public String getInstanceId() { return instanceId; }
        public String getNodeId() { return nodeId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EventKey other)) return false;
            return Objects.equals(instanceId, other.instanceId)
                    && Objects.equals(nodeId, other.nodeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(instanceId, nodeId);
        }
    }

    /** 事件类型 */
    public enum EventType {
        MESSAGE, SIGNAL, TIMER
    }

    @Id
    @Column(name = "instance_id", length = 64, nullable = false)
    private String instanceId;

    @Id
    @Column(name = "node_id", length = 64, nullable = false)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 16, nullable = false)
    private EventType eventType;

    /** MESSAGE_EVENT: messageName:correlationKey */
    @Column(name = "message_key", length = 256)
    private String messageKey;

    /** SIGNAL_EVENT: signalName */
    @Column(name = "signal_name", length = 128)
    private String signalName;

    /** TIMER_BOUNDARY: 触发时间（毫秒时间戳） */
    @Column(name = "trigger_time")
    private Long triggerTime;

    /** TIMER_BOUNDARY: 是否中断模式 */
    @Column(name = "interrupting")
    private Boolean interrupting;

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }
    public String getMessageKey() { return messageKey; }
    public void setMessageKey(String messageKey) { this.messageKey = messageKey; }
    public String getSignalName() { return signalName; }
    public void setSignalName(String signalName) { this.signalName = signalName; }
    public Long getTriggerTime() { return triggerTime; }
    public void setTriggerTime(Long triggerTime) { this.triggerTime = triggerTime; }
    public Boolean getInterrupting() { return interrupting; }
    public void setInterrupting(Boolean interrupting) { this.interrupting = interrupting; }
}