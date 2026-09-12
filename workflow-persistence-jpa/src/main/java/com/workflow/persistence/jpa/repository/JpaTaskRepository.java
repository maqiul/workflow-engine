package com.workflow.persistence.jpa.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.workflow.definition.Candidate;
import com.workflow.definition.CandidateCodec;
import com.workflow.enums.TaskStatus;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.jpa.entity.WfTaskEntity;
import com.workflow.repository.TaskFilter;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.TaskInstance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

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
        try {
            doSave(task);
        } catch (RuntimeException ex) {
            throw JpaPersistence.asConflictIfOptimisticLock(ex, task.getId());
        }
    }

    /**
     * CAS 的实现体。
     *
     * <p>版本条件来自实体上的 {@code @Version}：Hibernate 在 UPDATE 时自动补
     * {@code WHERE revision = <读取到的值>} 并自增。会签场景下两个节点同时写同一行时，
     * 后到者的 UPDATE 影响 0 行 → 乐观锁异常 → 这里转成引擎认识的并发冲突。
     */
    private void doSave(TaskInstance task) {
        runInOrOpenTx(em -> {
            WfTaskEntity entity = em.find(WfTaskEntity.class, task.getId());
            if (entity == null) {
                entity = new WfTaskEntity();
                entity.setId(task.getId());
                entity.setRevision(FIRST_REVISION);
                                    entity.setCreateTime(task.getCreateTime());
                entity.setInstanceId(task.getInstanceId());
                entity.setTokenId(task.getTokenId());
                entity.setNodeId(task.getNodeId());
                entity.setCandidateJson(CandidateCodec.toJson(task.getCandidate()));
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
                entity.setArrival(task.getArrival());
                entity.setStatus(task.getStatus());
                em.persist(entity);
            } else {
                entity.setInstanceId(task.getInstanceId());
                entity.setTokenId(task.getTokenId());
                entity.setNodeId(task.getNodeId());
                entity.setCandidateJson(CandidateCodec.toJson(task.getCandidate()));
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
                entity.setArrival(task.getArrival());
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
        try {
            doSaveBatch(tasks);
        } catch (RuntimeException ex) {
            throw JpaPersistence.asConflictIfOptimisticLock(ex, tasks.get(0).getId());
        }
    }

    private void doSaveBatch(List<TaskInstance> tasks) {
        // 批量优化：单事务内批量插入，减少事务开销
        runInOrOpenTx(em -> {
            for (TaskInstance task : tasks) {
                WfTaskEntity entity = em.find(WfTaskEntity.class, task.getId());
                if (entity == null) {
                    entity = new WfTaskEntity();
                    entity.setId(task.getId());
                    entity.setRevision(FIRST_REVISION);
                                        entity.setCreateTime(task.getCreateTime());
                    entity.setInstanceId(task.getInstanceId());
                    entity.setTokenId(task.getTokenId());
                    entity.setNodeId(task.getNodeId());
                    entity.setCandidateJson(CandidateCodec.toJson(task.getCandidate()));
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
                entity.setArrival(task.getArrival());
                entity.setStatus(task.getStatus());
                    em.persist(entity);
                } else {
                    entity.setInstanceId(task.getInstanceId());
                    entity.setTokenId(task.getTokenId());
                    entity.setNodeId(task.getNodeId());
                    entity.setCandidateJson(CandidateCodec.toJson(task.getCandidate()));
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
                entity.setArrival(task.getArrival());
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
        return findPaged(TaskFilter.create()
                .status(TaskStatus.PENDING)
                .candidateUser(userId));
    }

    // ========== 条件下推 ==========

    @Override
    public List<TaskInstance> findPaged(TaskFilter filter) {
        return runInOrOpenTx(em -> {
            boolean pushLimit = filter.canPushDownLimit();
            List<TaskInstance> hits = selectByFilter(em, filter, pushLimit).stream()
                    .map(JpaTaskRepository::toDomain)
                    .filter(filter::matches)
                    .toList();
            // 粗筛场景下 limit 没敢下推，分页必须挪到精筛之后 ——
            // 否则被 LIKE 误命中的行会把真匹配挤出这一页
            return pushLimit ? hits : filter.finish(hits);
        });
    }

    @Override
    public long countByFilter(TaskFilter filter) {
        if (filter.hasCandidateCondition()) {
            // 候选人条件走的是 LIKE 粗筛，SQL 计数会把假阳性算进去，只能取回来精筛后数
            return runInOrOpenTx(em -> selectByFilter(em, filter, false).stream()
                    .map(JpaTaskRepository::toDomain)
                    .filter(filter::matches)
                    .count());
        }
        return runInOrOpenTx(em -> {
            StringBuilder jpql = new StringBuilder("SELECT COUNT(t) FROM WfTaskEntity t WHERE 1=1");
            appendConditions(jpql, filter);
            Query query = em.createQuery(jpql.toString(), Long.class);
            bindConditions(query, filter);
            return (Long) query.getSingleResult();
        });
    }

    /** 按 filter 取行；{@code paging} 为 true 时才把 limit/offset 交给数据库。 */
    private List<WfTaskEntity> selectByFilter(EntityManager em, TaskFilter filter,
                                              boolean paging) {
        StringBuilder jpql = new StringBuilder("SELECT t FROM WfTaskEntity t WHERE 1=1");
        appendConditions(jpql, filter);
        appendOrder(jpql, filter);
        Query query = em.createQuery(jpql.toString(), WfTaskEntity.class);
        bindConditions(query, filter);
        if (paging) {
            if (filter.getLimit() != null) {
                query.setMaxResults(filter.getLimit());
            }
            if (filter.getOffset() != null && filter.getOffset() > 0) {
                query.setFirstResult(filter.getOffset());
            }
        }
        return query.getResultList();
    }

    /**
     * 拼可下推条件。
     *
     * <p>候选人列是 CLOB（JSON），得先 {@code CAST} 成字符串才能 {@code LIKE} ——
     * 直接对 CLOB 比较在部分数据库上会因类型不匹配报错。
     */
    private static void appendConditions(StringBuilder jpql, TaskFilter f) {
        if (f.getInstanceId() != null) {
            jpql.append(" AND t.instanceId = :iid");
        }
        if (f.getStatus() != null) {
            jpql.append(" AND t.status = :st");
        }
        if (f.getNodeId() != null) {
            jpql.append(" AND t.nodeId = :nid");
        }
        if (f.getCandidateUserId() != null) {
            jpql.append(" AND CAST(t.candidateJson AS string) LIKE :cu");
        }
        if (f.getCandidateGroupId() != null) {
            jpql.append(" AND CAST(t.candidateJson AS string) LIKE :cg");
        }
    }

    /** 排序规则与 {@link TaskFilter#finish} 保持一致：createTime 同毫秒时用 id 兜底。 */
    private static void appendOrder(StringBuilder jpql, TaskFilter f) {
        if (!f.isOrderByCreateTime()) {
            return;
        }
        String dir = f.isDescending() ? "DESC" : "ASC";
        jpql.append(" ORDER BY t.createTime ").append(dir)
                .append(", t.id ").append(dir);
    }

    private static void bindConditions(Query query, TaskFilter f) {
        if (f.getInstanceId() != null) {
            query.setParameter("iid", f.getInstanceId());
        }
        if (f.getStatus() != null) {
            query.setParameter("st", f.getStatus());
        }
        if (f.getNodeId() != null) {
            query.setParameter("nid", f.getNodeId());
        }
        if (f.getCandidateUserId() != null) {
            query.setParameter("cu", TaskFilter.candidateLikePattern(f.getCandidateUserId()));
        }
        if (f.getCandidateGroupId() != null) {
            query.setParameter("cg", TaskFilter.candidateLikePattern(f.getCandidateGroupId()));
        }
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
        Candidate candidate = CandidateCodec.fromJson(e.getCandidateJson());
        Set<String> completed = JSON.parseObject(e.getCompletedApproversJson(), STRING_SET_TYPE);
        if (completed == null) completed = new HashSet<>();
        return rebuildFromEntity(e, candidate, completed);
    }

    /** Entity -> TaskInstance,供 JpaInstanceRepository 复用 */
    public static TaskInstance rebuildFromEntity(WfTaskEntity e) {
        Candidate candidate = CandidateCodec.fromJson(e.getCandidateJson());
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
                e.getNodeId(), candidate, completed, e.getStatus(),
                e.getRevision(), e.getCreateTime(), e.getTenantId(), e.getArrival());
    }

    /** 插入行的初始版本号（与内存仓储 save 后的版本号对齐） */
    private static final long FIRST_REVISION = 1L;

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