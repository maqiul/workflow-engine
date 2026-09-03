package com.workflow.persistence.jpa.entity;

import com.workflow.enums.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 历史任务表实体 —— 对应 Flyway {@code V4__history_task.sql}。
 *
 * <p>{@code candidateUsers} / {@code completedBy} 以「前后带逗号」形式存储
 * （如 {@code ,u1,u2,}），使按人检索能用 {@code LIKE '%,u1,%'} 精确命中，
 * 避免把 u1 误配到 u11。
 */
@Entity
@Table(name = "wf_hist_task", indexes = {
        @Index(name = "idx_hist_task_inst", columnList = "instance_id"),
        @Index(name = "idx_hist_task_node", columnList = "process_key,node_id"),
        @Index(name = "idx_hist_task_time", columnList = "end_time")
})
public class WfHistTaskEntity {

    @Id
    @Column(name = "task_id", length = 64, nullable = false)
    private String taskId;

    @Column(name = "instance_id", length = 64, nullable = false)
    private String instanceId;

    @Column(name = "process_key", length = 64, nullable = false)
    private String processKey;

    @Column(name = "process_version", nullable = false)
    private int processVersion;

    @Column(name = "node_id", length = 64, nullable = false)
    private String nodeId;

    @Column(name = "candidate_users", length = 512, nullable = false)
    private String candidateUsers;

    @Column(name = "completed_by", length = 512)
    private String completedBy;

    @Column(name = "start_time", nullable = false)
    private long startTime;

    @Column(name = "end_time", nullable = false)
    private long endTime;

    /** 同毫秒内的次级排序键，保证审批链顺序确定。 */
    @Column(name = "seq", nullable = false)
    private long seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 16, nullable = false)
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
