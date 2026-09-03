package com.workflow.runtime;

import com.workflow.definition.Candidate;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.TaskStatus;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 任务实例 - 一个 UserTask 节点产生的具体待办
 *
 * 字段说明:
 *  nodeId              - 对应的 UserTask 节点
 *  candidate           - 候选人与策略(ANY/ALL)
 *  completedApprovers  - 已经完成审批的用户(会签/或签共用)
 *  status              - 当前任务整体状态(只要还有候选人未操作,就是 PENDING)
 */
public final class TaskInstance {
    private final String id;
    private final String instanceId;
    private final String tokenId;
    private final String nodeId;
    private final Candidate candidate;
    private final Set<String> completedApprovers;
    private volatile TaskStatus status;

    public TaskInstance(String instanceId, String tokenId, String nodeId, Candidate candidate) {
        this.id = UUID.randomUUID().toString();
        this.instanceId = Objects.requireNonNull(instanceId);
        this.tokenId = Objects.requireNonNull(tokenId);
        this.nodeId = Objects.requireNonNull(nodeId);
        this.candidate = Objects.requireNonNull(candidate);
        this.completedApprovers = new HashSet<>();
        this.status = TaskStatus.PENDING;
    }

    public String getId() { return id; }
    public String getInstanceId() { return instanceId; }
    public String getTokenId() { return tokenId; }
    public String getNodeId() { return nodeId; }
    public Candidate getCandidate() { return candidate; }
    public Set<String> getCompletedApprovers() {
        return Collections.unmodifiableSet(completedApprovers);
    }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }

    /**
     * 记录一个审批人的完成操作
     * @return true 表示此操作使整个任务完成(会签完成 或 或签命中)
     */
    public boolean recordCompletion(String userId) {
        Objects.requireNonNull(userId);
        if (!candidate.getUserIds().contains(userId)) {
            throw new IllegalArgumentException("用户 " + userId + " 不是本任务候选人");
        }
        completedApprovers.add(userId);

        CandidateStrategy strategy = candidate.getStrategy();
        if (strategy == CandidateStrategy.ANY) {
            // 或签:任一完成即满足
            this.status = TaskStatus.COMPLETED;
            return true;
        } else {
            // 会签:全部完成才满足
            if (completedApprovers.containsAll(candidate.getUserIds())) {
                this.status = TaskStatus.COMPLETED;
                return true;
            }
            return false;
        }
    }

    /**
     * 转办 - 把任务交给新用户处理
     * 原候选人的完成记录保留;但任务待办人换成新用户(单人会签)
     * 注:简化实现 - 转办后任务变单人 ANY 策略
     */
    public void transferTo(String newUserId) {
        Objects.requireNonNull(newUserId);
        this.status = TaskStatus.TRANSFERRED;
    }

    @Override
    public String toString() {
        return "Task[" + id.substring(0, 8) + "@" + nodeId + " " + candidate + " status=" + status + "]";
    }
}