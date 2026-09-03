package com.workflow.persistence.jpa.entity;

import com.workflow.enums.NodeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 历史活动实例表实体 —— 对应 Flyway {@code V3__history_activity.sql}。
 *
 * <p>记录一次节点执行的时间区间。{@code end_time} 为空表示活动仍在进行：
 * UserTask 与子流程的活动会跨越两次引擎推进（进入时插入、完成后闭合），
 * 以此覆盖真实的人类等待时长。
 *
 * <p>{@code seq} 是次级排序键。{@code start_time} 只到毫秒，同毫秒内推进的节点
 * 分不出先后，而报表还原路径必须有确定顺序。
 */
@Entity
@Table(name = "wf_hist_activity", indexes = {
        @Index(name = "idx_hist_act_inst", columnList = "instance_id"),
        @Index(name = "idx_hist_act_key", columnList = "process_key,activity_id"),
        @Index(name = "idx_hist_act_open", columnList = "instance_id,token_id,activity_id"),
        @Index(name = "idx_hist_act_order", columnList = "instance_id,start_time,seq")
})
public class WfHistActivityEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "instance_id", length = 64, nullable = false)
    private String instanceId;

    /** 冗余存流程标识：聚合不 join 实例表，实例归档后历史仍能独立查询。 */
    @Column(name = "process_key", length = 64, nullable = false)
    private String processKey;

    @Column(name = "process_version", nullable = false)
    private int processVersion;

    @Column(name = "activity_id", length = 64, nullable = false)
    private String activityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", length = 32, nullable = false)
    private NodeType activityType;

    @Column(name = "token_id", length = 64, nullable = false)
    private String tokenId;

    /** UserTask 活动对应的待办 id；瞬时活动为空。 */
    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "start_time", nullable = false)
    private long startTime;

    /** 进程内单调序号，同毫秒内的次级排序键。 */
    @Column(name = "seq", nullable = false)
    private long seq;

    /** null 表示活动仍在进行。 */
    @Column(name = "end_time")
    private Long endTime;

    @Column(name = "performer", length = 64)
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
