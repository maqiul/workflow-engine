package com.workflow.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workflow.enums.NodeType;

/**
 * 历史活动实例表实体 —— 对应 Flyway {@code V3__history_activity.sql}。
 *
 * <p>字段含义与 JPA 版一致：{@code endTime} 为 null 表示活动仍在进行
 * （UserTask / 子流程的活动跨越两次引擎推进），{@code seq} 是同毫秒内的次级排序键。
 */
@TableName("wf_hist_activity")
public class WfHistActivityEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("instance_id")
    private String instanceId;

    @TableField("process_key")
    private String processKey;

    @TableField("process_version")
    private int processVersion;

    @TableField("activity_id")
    private String activityId;

    @TableField("activity_type")
    private NodeType activityType;

    @TableField("token_id")
    private String tokenId;

    @TableField("task_id")
    private String taskId;

    @TableField("start_time")
    private long startTime;

    @TableField("seq")
    private long seq;

    @TableField("end_time")
    private Long endTime;

    @TableField("performer")
    private String performer;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getProcessKey() { return processKey; }
    public void setProcessKey(String processKey) { this.processKey = processKey; }
    public int getProcessVersion() { return processVersion; }
    public void setProcessVersion(int processVersion) { this.processVersion = processVersion; }
    public String getActivityId() { return activityId; }
    public void setActivityId(String activityId) { this.activityId = activityId; }
    public NodeType getActivityType() { return activityType; }
    public void setActivityType(NodeType activityType) { this.activityType = activityType; }
    public String getTokenId() { return tokenId; }
    public void setTokenId(String tokenId) { this.tokenId = tokenId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public Long getEndTime() { return endTime; }
    public void setEndTime(Long endTime) { this.endTime = endTime; }
    public String getPerformer() { return performer; }
    public void setPerformer(String performer) { this.performer = performer; }
}
