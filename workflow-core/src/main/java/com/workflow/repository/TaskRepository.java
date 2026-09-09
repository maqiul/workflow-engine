package com.workflow.repository;

import com.workflow.runtime.TaskInstance;

import java.util.List;

/**
 * 任务仓储
 */
public interface TaskRepository {

    /** 保存(新增或覆盖) */
    void save(TaskInstance task);

    /** 按 id 查找 */
    TaskInstance findById(String taskId);

    /** 列出实例下的所有任务 */
    List<TaskInstance> findByInstanceId(String instanceId);

    /** 列出某用户作为候选人的待办任务 */
    List<TaskInstance> findPendingByUser(String userId);

    /** 列出所有任务（用于复杂查询） */
    default List<TaskInstance> findAll() {
        throw new UnsupportedOperationException("findAll not implemented");
    }

    /** 按节点 ID 查找任务 */
    default List<TaskInstance> findByNodeId(String nodeId) {
        throw new UnsupportedOperationException("findByNodeId not implemented");
    }

    /** 按状态查找任务 */
    default List<TaskInstance> findByStatus(com.workflow.enums.TaskStatus status) {
        throw new UnsupportedOperationException("findByStatus not implemented");
    }

    /**
     * 待办(PENDING)任务总数。
     *
     * <p>抽象方法，强制三套仓储实现：监控只要计数时不必像
     * {@code findByStatus(PENDING)} 那样把每个待办实体全量重建。
     */
    long countPending();
}