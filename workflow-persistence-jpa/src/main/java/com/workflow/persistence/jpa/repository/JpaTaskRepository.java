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
                entity.setTenantId(task.getTenantId());
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
                entity.setTenantId(task.getTenantId());
                entity.setStatus(task.getStatus());
            }
            em.flush();  // 立刻 flush,跨事务的 SELECT 可见
            return null;
        });
    }

    @Override
    public void saveBatch(List<TaskInstance> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        // 批量优化：单事务内批量插入，减少事务开销
        runInOrOpenTx(em -> {
            for (TaskInstance task : tasks) {
                WfTaskEntity entity = em.find(WfTaskEntity.class, task.getId());
                if (entity == null) {
                    entity = new WfTaskEntity();
                    entity.setId(task.getId());
                    entity.setCreateTime(System.currentTimeMillis());
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
                    entity.setTenantId(task.getTenantId());
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
                    entity.setTenantId(task.getTenantId());
                entity.setStatus(task.getStatus());
                }
            }
            em.flush();  // 批量 flush
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

    // ========== 全局查询 ==========
    //
    // findAll / findByNodeId / findByStatus 此前全部落到 TaskRepository 接口的
    // default 实现（抛 UnsupportedOperationException），所以 TaskQuery 一旦不带
    // processInstanceId 就无法在真实数据库上工作。补齐在这里。

    @Override
    public List<TaskInstance> findAll() {
        return runInOrOpenTx(em -> em.createQuery(
                        "SELECT t FROM WfTaskEntity t ORDER BY t.createTime", WfTaskEntity.class)
                .getResultList().stream().map(JpaTaskRepository::toDomain).toList());
    }

    @Override
    public List<TaskInstance> findByNodeId(String nodeId) {
        return runInOrOpenTx(em -> em.createQuery(
                        "SELECT t FROM WfTaskEntity t WHERE t.nodeId = :nid ORDER BY t.createTime",
                        WfTaskEntity.class)
                .setParameter("nid", nodeId)
                .getResultList().stream().map(JpaTaskRepository::toDomain).toList());
    }

    @Override
    public List<TaskInstance> findByStatus(TaskStatus status) {
        return runInOrOpenTx(em -> em.createQuery(
                        "SELECT t FROM WfTaskEntity t WHERE t.status = :st ORDER BY t.createTime",
                        WfTaskEntity.class)
                .setParameter("st", status)
                .getResultList().stream().map(JpaTaskRepository::toDomain).toList());
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
        // 统一走 TaskInstance.reconstruct 重建：不再用反射逐个写字段，
        // 并且把 create_time 读回来 —— 此前这个字段被丢弃，导致
        // TaskQuery.orderByCreateTime() 只能退化成按 id 排序。
        return TaskInstance.reconstruct(e.getId(), e.getInstanceId(), e.getTokenId(),
                e.getNodeId(), candidate, completed, e.getStatus(), 0L, e.getCreateTime(), e.getTenantId());
    }

    @Override
    public long countPending() {
        return countPending(null);
    }

    @Override
    public long countPending(String tenantId) {
        return runInOrOpenTx(em -> {
            String jpql = "SELECT COUNT(e) FROM WfTaskEntity e WHERE e.status = :st";
            if (tenantId != null) {
                jpql += " AND e.tenantId = :tenantId";
            }
            jakarta.persistence.TypedQuery<Long> query = em.createQuery(jpql, Long.class)
                    .setParameter("st", TaskStatus.PENDING);
            if (tenantId != null) {
                query.setParameter("tenantId", tenantId);
            }
            return query.getSingleResult();
        });
    }

    private <R> R runInOrOpenTx(Function<EntityManager, R> action) {
        EntityManager bound = jpa.currentEmOrNull();
        if (bound != null) {
            return action.apply(bound);
        }
        return jpa.inTransaction(action);
    }
}