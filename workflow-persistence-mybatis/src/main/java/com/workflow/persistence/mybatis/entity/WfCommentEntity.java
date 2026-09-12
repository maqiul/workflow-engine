package com.workflow.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workflow.enums.CommentType;

/**
 * 审批意见表实体（MyBatis-Plus）—— 对应 Flyway {@code V10__comment.sql}。
 *
 * <p>{@code taskId} / {@code nodeId} 可空：流程级意见不挂具体待办。
 * 枚举以 {@code name()} 落字符串列，与 DDL 的 {@code VARCHAR(16)} 对齐。
 */
@TableName("wf_comment")
public class WfCommentEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("instance_id")
    private String instanceId;

    @TableField("task_id")
    private String taskId;

    @TableField("node_id")
    private String nodeId;

    @TableField("user_id")
    private String userId;

    /** 列名不与 SQL 关键字 {@code type} 撞车，故加 comment_ 前缀。 */
    @TableField("comment_type")
    private CommentType type;

    @TableField("message")
    private String message;

    @TableField("create_time")
    private long createTime;

    /** 同毫秒内的次级排序键，保证审批链顺序确定。 */
    @TableField("seq")
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
