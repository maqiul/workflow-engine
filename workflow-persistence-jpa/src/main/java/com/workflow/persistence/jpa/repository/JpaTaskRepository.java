package com.workflow.persistence.jpa.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.workflow.definition.Candidate;
import com.workflow.enums.TaskStatus;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.jpa.entity.WfTaskEntity;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.TaskInstance;
import jakarta.persistence.EntityManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * JPA 版 TaskRepository
 *
 * 线程绑定 EM 优先:让 engine.completeTask 在外部事务下,save(task) + save(instance)
 * 共用同一 EM,根除"实例保存了但 task 表查询为空"的 bug。
 */
public class JpaTaskRepository implements TaskRepository {

    private static final TypeReference<Set<String>> STRING_SET_TYPE = new TypeReference<Set<String>>() {};

    private final JpaPersistence jpa;

    public JpaTaskRepository(JpaPersistence jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(TaskInstance task) {
        runInOrOpenTx(em -> {
            WfTaskEntity entity = em.find(WfTaskEntity.class, task.getId());
            if (entity == null) {
                entity = new WfTaskEntity();
                entity.setId(task.getId());
                entity.setCreateTime(System.currentTimeMillis());
                entity.setInstanceId(task.getInstanceId());
                entity.setTokenId(task.getTokenId());
                entity.setNodeId(task.getNodeId());
                entity.setCandidateJson(JSON.toJSONString(task.getCandidate()));
                // 直接序列化底层 HashSet,避免 unmodifiableSet 包装类型的序列化问题
                try {
                    java.lang.reflect.Field f = TaskInstance.class.getDeclaredField("completedApprovers");
                    f.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Set<String> approvers = (Set<String>) f.get(task);
                    entity.setCompletedApproversJson(JSON.toJSONString(approvers));
                } catch (Exception e) {
                    throw new RuntimeException("序列化 completedApprovers 失败", e);
                }
                entity.setStatus(task.getStatus());
                em.persist(entity);
            } else {
                entity.setInstanceId(task.getInstanceId());
                entity.setTokenId(task.getTokenId());
                entity.setNodeId(task.getNodeId());
                entity.setCandidateJson(JSON.toJSONString(task.getCandidate()));
                try {
                    java.lang.reflect.Field f = TaskInstance.class.getDeclaredField("completedApprovers");
                    f.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Set<String> approvers = (Set<String>) f.get(task);
                    entity.setCompletedApproversJson(JSON.toJSONString(approvers));
                } catch (Exception e) {
                    throw new RuntimeException("序列化 completedApprovers 失败", e);
                }
                entity.setStatus(task.getStatus());
            }
            em.flush();  // 立刻 flush,跨事务的 SELECT 可见
            return null;
        });
    }

    @Override
    public TaskInstance findById(String taskId) {
        return runInOrOpenTx(em -> {
            WfTaskEntity e = em.find(WfTaskEntity.class, taskId);
            if (e == null) {
                throw new IllegalArgumentException("任务不存在: " + taskId);
            }
            return toDomain(e);
        });
    }

    @Override
    public List<TaskInstance> findByInstanceId(String instanceId) {
        return runInOrOpenTx(em -> {
            List<WfTaskEntity> list = em.createQuery(
                    "SELECT t FROM WfTaskEntity t WHERE t.instanceId = :iid ORDER BY t.createTime",
                    WfTaskEntity.class)
                    .setParameter("iid", instanceId)
                    .getResultList();
            return list.stream().map(JpaTaskRepository::rebuildFromEntity).toList();
        });
    }

    @Override
    public List<TaskInstance> findPendingByUser(String userId) {
        return runInOrOpenTx(em -> {
            List<WfTaskEntity> list = em.createQuery(
                    "SELECT t FROM WfTaskEntity t WHERE t.status = :st", WfTaskEntity.class)
                    .setParameter("st", TaskStatus.PENDING)
                    .getResultList();
            return list.stream()
                    .map(JpaTaskRepository::toDomain)
                    .filter(t -> t.getCandidate().getUserIds().contains(userId))
                    .toList();
        });
    }

    private static TaskInstance toDomain(WfTaskEntity e) {
        Candidate candidate = JSON.parseObject(e.getCandidateJson(), Candidate.class);
        Set<String> completed = JSON.parseObject(e.getCompletedApproversJson(), STRING_SET_TYPE);
        if (completed == null) completed = new HashSet<>();
        return rebuildFromEntity(e, candidate, completed);
    }

    /** Entity -> TaskInstance,供 JpaInstanceRepository 复用 */
    public static TaskInstance rebuildFromEntity(WfTaskEntity e) {
        Candidate candidate = JSON.parseObject(e.getCandidateJson(), Candidate.class);
        Set<String> completed = JSON.parseObject(e.getCompletedApproversJson(), STRING_SET_TYPE);
        if (completed == null) completed = new HashSet<>();
        return rebuildFromEntity(e, candidate, completed);
    }

    private static TaskInstance rebuildFromEntity(WfTaskEntity e,
                                                  Candidate candidate,
                                                  Set<String> completed) {
        TaskInstance task = new TaskInstance(e.getInstanceId(), e.getTokenId(), e.getNodeId(), candidate);
        try {
            java.lang.reflect.Field idField = TaskInstance.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(task, e.getId());

            java.lang.reflect.Field completedField = TaskInstance.class.getDeclaredField("completedApprovers");
            completedField.setAccessible(true);
            completedField.set(task, completed);
        } catch (Exception ex) {
            throw new RuntimeException("重建 TaskInstance 失败: " + ex.getClass().getSimpleName()
                    + " - " + ex.getMessage() + " (candidate=" + candidate + ")", ex);
        }
        task.setStatus(e.getStatus());
        return task;
    }

    private <R> R runInOrOpenTx(Function<EntityManager, R> action) {
        EntityManager bound = jpa.currentEmOrNull();
        if (bound != null) {
            return action.apply(bound);
        }
        return jpa.inTransaction(action);
    }
}