package com.workflow.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workflow.enums.TaskStatus;

/**
 * 历史任务表实体 —— 对应 Flyway {@code V4__history_task.sql}。
 *
 * <p>主键即 taskId，一个任务一条历史。人员列以 {@code ,u1,u2,} 形式存储，
 * 便于用 {@code LIKE '%,u1,%'} 精确按人检索。
 */
@TableName("wf_hist_task")
public class WfHistTaskEntity {

    @TableId(value = "task_id", type = IdType.INPUT)
    private String taskId;

    @TableField("instance_id")
    private String instanceId;

    @TableField("process_key")
    private String processKey;

    @TableField("process_version")
    private int processVersion;

    @TableField("node_id")
    private String nodeId;

    @TableField("candidate_users")
    private String candidateUsers;

    @TableField("completed_by")
    private String completedBy;

    @TableField("start_time")
    private long startTime;

    @TableField("end_time")
    private long endTime;

    /** 同毫秒内的次级排序键，保证审批链顺序确定。 */
    @TableField("seq")
    private long seq;

    @TableField("end_reason")
    private TaskStatus endReason;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getProcessKey() { return processKey; }
    public void setProcessKey(String processKey) { this.processKey = processKey; }
    public int getProcessVersion() { return processVersion; }
    public void setProcessVersion(int processVersion) { this.processVersion = processVersion; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getCandidateUsers() { return candidateUsers; }
    public void setCandidateUsers(String candidateUsers) { this.candidateUsers = candidateUsers; }
    public String getCompletedBy() { return completedBy; }
    public void setCompletedBy(String completedBy) { this.completedBy = completedBy; }
    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }
    public long getEndTime() { return endTime; }
    public void setEndTime(long endTime) { this.endTime = endTime; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public TaskStatus getEndReason() { return endReason; }
    public void setEndReason(TaskStatus endReason) { this.endReason = endReason; }
}
