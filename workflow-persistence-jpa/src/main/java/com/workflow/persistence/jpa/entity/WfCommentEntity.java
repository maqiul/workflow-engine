package com.workflow.persistence.jpa.entity;

import com.workflow.enums.CommentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 审批意见表实体 —— 对应 Flyway {@code V10__comment.sql}。
 *
 * <p>{@code taskId} / {@code nodeId} 可空：流程级意见（如发起人附言）不挂具体待办。
 * {@code createTime} + {@code seq} 是排序双键，理由见 {@code Comment} 的类注释。
 */
@Entity
@Table(name = "wf_comment", indexes = {
        @Index(name = "idx_comment_inst", columnList = "instance_id,create_time,seq"),
        @Index(name = "idx_comment_task", columnList = "task_id"),
        @Index(name = "idx_comment_user", columnList = "user_id")
})
public class WfCommentEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "instance_id", length = 64, nullable = false)
    private String instanceId;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "node_id", length = 64)
    private String nodeId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "comment_type", length = 16, nullable = false)
    private CommentType type;

    @Column(name = "message", length = 2000)
    private String message;

    @Column(name = "create_time", nullable = false)
    private long createTime;

    @Column(name = "seq", nullable = false)
    private long seq;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public CommentType getType() { return type; }
    public void setType(CommentType type) { this.type = type; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
}
